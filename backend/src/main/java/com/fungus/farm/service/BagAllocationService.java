package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.BagAllocation;
import com.fungus.farm.domain.BagBatch;
import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.domain.GrowRoom;
import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.dto.AllocationRequest;
import com.fungus.farm.dto.AllocationRevokeRequest;
import com.fungus.farm.repository.BagAllocationRepository;
import com.fungus.farm.repository.BagRepository;
import com.fungus.farm.repository.FruitingRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 菌包开袋后的分批入房：
 * 一次分配 = 锁菌包行校验状态与剩余 + 原子扣减 + 发号 + 一条入房记录 + 一个出菇批次（含三潮计划行），
 * 全部在同一事务内落库，任何一步失败整体回滚，不会只扣数量或留下半条批次。
 * request_key 幂等防重复提交，number_seq 发号防重号；
 * 出菇尚未开始（无采收、无环境处置关联）的分配可撤回，归还数量并删除对应批次。
 */
@Service
public class BagAllocationService {
    public static final String STATUS_ACTIVE = "已入房";
    public static final String STATUS_REVOKED = "已撤回";
    private static final String BAG_STATUS_OPENED = "已开袋";
    /** 单包预估产量（kg），用于生成出菇批次预产量 */
    private static final BigDecimal YIELD_PER_BAG = new BigDecimal("0.50");
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter VIEW_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BagAllocationRepository allocationRepository;
    private final BagRepository bagRepository;
    private final FruitingRepository fruitingRepository;
    private final GrowRoomRepository roomRepository;

    public BagAllocationService(BagAllocationRepository allocationRepository, BagRepository bagRepository,
            FruitingRepository fruitingRepository, GrowRoomRepository roomRepository) {
        this.allocationRepository = allocationRepository;
        this.bagRepository = bagRepository;
        this.fruitingRepository = fruitingRepository;
        this.roomRepository = roomRepository;
    }

    // ---------------- 看板：原始总量 / 已分配量 / 剩余可用量 / 去向 ----------------

    public Map<String, Object> board() {
        List<BagBatch> bags = bagRepository.findAll(null);
        List<Map<String, Object>> rows = allocationRepository.findBoardRows();
        List<Long> activeBatchIds = rows.stream()
                .filter(row -> STATUS_ACTIVE.equals(row.get("status")) && row.get("fruiting_batch_id") != null)
                .map(row -> ((Number) row.get("fruiting_batch_id")).longValue())
                .toList();
        Map<Long, Long> envTickets = allocationRepository.envTicketIdsByBatch(activeBatchIds);
        Map<Long, Long> harvestedFlushes = allocationRepository.harvestedFlushCounts(activeBatchIds);
        Map<Long, Long> inventories = allocationRepository.inventoryCounts(activeBatchIds);

        Map<Long, List<Map<String, Object>>> allocationsByBag = new HashMap<>();
        Map<Long, Integer> allocatedByBag = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Long bagId = ((Number) row.get("bag_batch_id")).longValue();
            String blockReason = null;
            if (STATUS_ACTIVE.equals(row.get("status"))) {
                Long batchId = ((Number) row.get("fruiting_batch_id")).longValue();
                allocatedByBag.merge(bagId, ((Number) row.get("quantity")).intValue(), Integer::sum);
                blockReason = revokeBlockReason((String) row.get("fruiting_status"),
                        (BigDecimal) row.get("harvested_yield"), envTickets.get(batchId),
                        harvestedFlushes.getOrDefault(batchId, 0L), inventories.getOrDefault(batchId, 0L));
            }
            allocationsByBag.computeIfAbsent(bagId, key -> new ArrayList<>()).add(toBoardItem(row, blockReason));
        }

        List<Map<String, Object>> bagRows = new ArrayList<>();
        for (BagBatch bag : bags) {
            Map<String, Object> bagRow = new LinkedHashMap<>();
            bagRow.put("id", bag.getId());
            bagRow.put("batchNo", bag.getBatchNo());
            bagRow.put("formula", bag.getFormula());
            bagRow.put("status", bag.getStatus());
            bagRow.put("totalQuantity", bag.getTotalQuantity());
            bagRow.put("availableQuantity", bag.getAvailableQuantity());
            bagRow.put("allocatedQuantity", allocatedByBag.getOrDefault(bag.getId(), 0));
            bagRow.put("allocations", allocationsByBag.getOrDefault(bag.getId(), List.of()));
            bagRows.add(bagRow);
        }
        // 已开袋批次排在最前，其余按批次号排列
        bagRows.sort(Comparator
                .comparing((Map<String, Object> row) -> BAG_STATUS_OPENED.equals(row.get("status")) ? 0 : 1)
                .thenComparing(row -> (String) row.get("batchNo")));
        return Map.of("bags", bagRows);
    }

    // ---------------- 分配入房 ----------------

    @Transactional
    public Map<String, Object> create(AllocationRequest request) {
        if (request.requestKey() == null || request.requestKey().isBlank()) {
            throw new BizException("缺少请求标识，请刷新页面后重新提交");
        }
        if (request.bagBatchId() == null) {
            throw new BizException("菌包批次不能为空");
        }
        if (request.roomId() == null) {
            throw new BizException("请选择入房菇房");
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new BizException("分配数量必须为大于 0 的整数");
        }
        if (request.plannedEntryDate() == null) {
            throw new BizException("计划入房日不能为空");
        }
        if (request.manager() == null || request.manager().isBlank()) {
            throw new BizException("负责人不能为空");
        }
        String requestKey = request.requestKey().trim();
        if (requestKey.length() > 64) {
            throw new BizException("请求标识过长");
        }
        String manager = request.manager().trim();
        if (manager.length() > 40) {
            throw new BizException("负责人姓名不能超过 40 字");
        }

        // 锁菌包行：同一批次的分配串行，剩余数量判断与扣减不会被并发打断
        BagBatch bag = bagRepository.findByIdForUpdate(request.bagBatchId())
                .orElseThrow(() -> new BizException("菌包批次不存在"));

        // 幂等：同一请求标识只落一条入房记录，重复提交/网络重试直接返回原记录，不生成重复批次
        var existing = allocationRepository.findByRequestKey(requestKey);
        if (existing.isPresent()) {
            Map<String, Object> row = allocationRepository.findBoardRow(existing.get().getId())
                    .orElseThrow(() -> new BizException("入房记录查询失败"));
            return Map.of("allocation", toBoardItem(row, null), "duplicated", true);
        }

        if (!BAG_STATUS_OPENED.equals(bag.getStatus())) {
            throw new BizException("只有“已开袋”状态的菌包才能分配入房，当前状态：" + bag.getStatus());
        }
        GrowRoom room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new BizException("菇房不存在"));
        int quantity = request.quantity();
        if (bag.getAvailableQuantity() == null || bag.getAvailableQuantity() < quantity) {
            throw new BizException("剩余可用数量不足，当前可分配 " + bag.getAvailableQuantity() + " 包");
        }
        // 原子扣减兜底：即使并发绕过行锁也不会超出剩余数量
        if (bagRepository.decreaseAvailable(bag.getId(), quantity) == 0) {
            throw new BizException("剩余可用数量已变化，请刷新后重试");
        }

        int seq = fruitingRepository.nextFruitingSeq();
        String suffix = String.format("%02d", seq);
        String datePart = request.plannedEntryDate().format(NO_DATE);
        String batchNo = "FB-" + datePart + "-" + suffix;
        String allocationNo = "IN-" + datePart + "-" + suffix;

        FruitingBatch batch = new FruitingBatch();
        batch.setBatchNo(batchNo);
        batch.setRoomId(room.getId());
        batch.setBagBatchId(bag.getId());
        batch.setSpecies(room.getSpecies());
        batch.setEntryDate(request.plannedEntryDate());
        batch.setExpectedYield(YIELD_PER_BAG.multiply(BigDecimal.valueOf(quantity)));
        batch.setHarvestedYield(BigDecimal.ZERO);
        batch.setStatus("待采收");
        Long batchId = fruitingRepository.saveBatch(batch);
        for (int flushNo = 1; flushNo <= 3; flushNo++) {
            HarvestFlush flush = new HarvestFlush();
            flush.setBatchId(batchId);
            flush.setFlushNo(flushNo);
            flush.setGradeAYield(BigDecimal.ZERO);
            flush.setGradeBYield(BigDecimal.ZERO);
            flush.setGradeCYield(BigDecimal.ZERO);
            flush.setYieldKg(BigDecimal.ZERO);
            flush.setStatus("待采收");
            fruitingRepository.saveFlush(flush);
        }

        BagAllocation allocation = new BagAllocation();
        allocation.setAllocationNo(allocationNo);
        allocation.setRequestKey(requestKey);
        allocation.setBagBatchId(bag.getId());
        allocation.setRoomId(room.getId());
        allocation.setFruitingBatchId(batchId);
        allocation.setFruitingBatchNo(batchNo);
        allocation.setQuantity(quantity);
        allocation.setPlannedEntryDate(request.plannedEntryDate());
        allocation.setManager(manager);
        allocation.setStatus(STATUS_ACTIVE);
        allocation.setCreatedAt(LocalDateTime.now());
        Long allocationId = allocationRepository.save(allocation);

        Map<String, Object> row = allocationRepository.findBoardRow(allocationId)
                .orElseThrow(() -> new BizException("入房记录查询失败"));
        return Map.of("allocation", toBoardItem(row, null), "duplicated", false);
    }

    // ---------------- 撤回 ----------------

    @Transactional
    public void revoke(Long allocationId, AllocationRevokeRequest request) {
        if (request.operator() == null || request.operator().isBlank()) {
            throw new BizException("撤回经办人不能为空");
        }
        String operator = request.operator().trim();
        if (operator.length() > 40) {
            throw new BizException("经办人姓名不能超过 40 字");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > 255) {
            throw new BizException("撤回备注不能超过 255 字");
        }

        BagAllocation allocation = allocationRepository.findByIdForUpdate(allocationId)
                .orElseThrow(() -> new BizException("入房记录不存在"));
        if (!STATUS_ACTIVE.equals(allocation.getStatus())) {
            throw new BizException("该入房记录已撤回，不能重复操作");
        }
        FruitingBatch batch = fruitingRepository.findByIdForUpdate(allocation.getFruitingBatchId())
                .orElseThrow(() -> new BizException("关联出菇批次不存在"));

        // 与看板一致的撤回门槛：环境处置关联、采收记录、采收库存记录任一存在都必须保留原状
        Long envTicketId = fruitingRepository.findEnvTicketId(batch.getId()).orElse(null);
        int harvested = fruitingRepository.countHarvestedFlushes(batch.getId());
        int inventory = fruitingRepository.countInventory(batch.getId());
        String blockReason = revokeBlockReason(batch.getStatus(), batch.getHarvestedYield(),
                envTicketId, harvested, inventory);
        if (blockReason != null) {
            throw new BizException(blockReason);
        }

        // 归还数量与清理批次同一事务：先解除入房记录对批次的引用，再删潮次计划行与批次，最后归还数量
        if (allocationRepository.markRevoked(allocation.getId(), operator, note, LocalDateTime.now()) == 0) {
            throw new BizException("该入房记录已撤回，不能重复操作");
        }
        fruitingRepository.deleteFlushesByBatch(batch.getId());
        fruitingRepository.deleteBatchById(batch.getId());
        bagRepository.increaseAvailable(allocation.getBagBatchId(), allocation.getQuantity());
    }

    // ---------------- 内部工具 ----------------

    /** 不能撤回的原因；返回 null 表示允许撤回。看板展示与撤回校验共用同一套口径 */
    private String revokeBlockReason(String fruitingStatus, BigDecimal harvestedYield, Long envTicketId,
            long harvestedFlushCount, long inventoryCount) {
        if ("环境暂停".equals(fruitingStatus)) {
            return "批次正被环境处置单暂停，处置单解除前不能撤回";
        }
        if (envTicketId != null) {
            return "批次已关联环境处置单（单号 #" + envTicketId + "），处置档案需保留，不能撤回";
        }
        BigDecimal harvested = harvestedYield == null ? BigDecimal.ZERO : harvestedYield;
        if (harvestedFlushCount > 0 || harvested.signum() > 0) {
            return "批次已产生采收记录（累计采收 " + harvested.stripTrailingZeros().toPlainString() + " kg），不能撤回";
        }
        if (inventoryCount > 0) {
            return "批次已产生采收库存记录，不能撤回";
        }
        return null;
    }

    private Map<String, Object> toBoardItem(Map<String, Object> row, String blockReason) {
        boolean active = STATUS_ACTIVE.equals(row.get("status"));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", ((Number) row.get("id")).longValue());
        item.put("allocationNo", row.get("allocation_no"));
        item.put("bagBatchId", ((Number) row.get("bag_batch_id")).longValue());
        item.put("roomId", ((Number) row.get("room_id")).longValue());
        item.put("roomCode", row.get("room_code"));
        item.put("quantity", ((Number) row.get("quantity")).intValue());
        item.put("plannedEntryDate", row.get("planned_entry_date") == null ? null : row.get("planned_entry_date").toString());
        item.put("manager", row.get("manager"));
        item.put("status", row.get("status"));
        item.put("fruitingBatchNo", row.get("fruiting_batch_no"));
        item.put("fruitingBatchId",
                row.get("fruiting_batch_id") == null ? null : ((Number) row.get("fruiting_batch_id")).longValue());
        item.put("fruitingStatus", active ? row.get("fruiting_status") : null);
        item.put("revocable", active && blockReason == null);
        item.put("blockReason", active ? blockReason : null);
        item.put("createdAt", formatTime(row.get("created_at")));
        item.put("revokedAt", formatTime(row.get("revoked_at")));
        item.put("revokedBy", row.get("revoked_by"));
        item.put("revokeNote", row.get("revoke_note"));
        return item;
    }

    private String formatTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(VIEW_TIME);
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(VIEW_TIME);
        }
        return value.toString();
    }
}
