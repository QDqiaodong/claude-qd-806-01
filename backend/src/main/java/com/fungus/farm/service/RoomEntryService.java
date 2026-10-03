package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.BagBatch;
import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.domain.GrowRoom;
import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.domain.RoomEntry;
import com.fungus.farm.dto.RoomEntryRequest;
import com.fungus.farm.repository.BagRepository;
import com.fungus.farm.repository.FruitingRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import com.fungus.farm.repository.RoomEntryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 菌包开袋后的分批入房。
 *
 * 口径：
 * - 只有“已开袋”菌包能分配；养菌/满袋（未开袋）、污染、报废一律拒绝。
 * - 一次分配 = 一条 room_entry + 一个一一对应的 fruiting_batch，整批或拆次均可；
 *   可用量条件扣减 + 菌包行锁兜底并发，任何一步失败整笔事务回滚，不会只扣数量或留下半条批次。
 * - 分配数量同步从 bag_batch.available_quantity 扣减，撤回在制记录时归还，页面与菇房批次始终对账一致。
 * - fruiting_batch 没有对应 room_entry 的批次视为历史数据，只读展示、不可撤回、不补造记录。
 */
@Service
public class RoomEntryService {
    /** 每包投产的预计产量（kg），用于新建出菇批次的 expected_yield（与历史手工口径保持一致的经验值） */
    private static final BigDecimal EXPECTED_KG_PER_BAG = BigDecimal.valueOf(0.5);
    private static final DateTimeFormatter ENTRY_FMT = DateTimeFormatter.ofPattern("yyMMdd");

    private final RoomEntryRepository entryRepository;
    private final BagRepository bagRepository;
    private final GrowRoomRepository roomRepository;
    private final FruitingRepository fruitingRepository;

    public RoomEntryService(RoomEntryRepository entryRepository, BagRepository bagRepository,
            GrowRoomRepository roomRepository, FruitingRepository fruitingRepository) {
        this.entryRepository = entryRepository;
        this.bagRepository = bagRepository;
        this.roomRepository = roomRepository;
        this.fruitingRepository = fruitingRepository;
    }

    // ---------------- 查询：原始总量 / 已分配 / 剩余可用 / 去向 ----------------

    public Map<String, Object> assignments() {
        List<BagBatch> bags = bagRepository.findAll(null);
        List<Map<String, Object>> rows = entryRepository.findAllRowsWithRoom();
        List<Map<String, Object>> legacyRows = entryRepository.findLegacyFruitingRows();

        Map<Long, List<Map<String, Object>>> entriesByBag = new LinkedHashMap<>();
        Map<Long, Integer> activeByBag = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long bagId = ((Number) row.get("bag_batch_id")).longValue();
            entriesByBag.computeIfAbsent(bagId, key -> new java.util.ArrayList<>()).add(row);
            if ("在制".equals(row.get("status"))) {
                activeByBag.merge(bagId, ((Number) row.get("quantity")).intValue(), Integer::sum);
            }
        }
        // 撤回资格（采收/库存/环境处置关联）一次取齐
        Map<Long, Map<String, Object>> links = new LinkedHashMap<>();
        for (Map<String, Object> link : entryRepository.findActiveLinkCounts()) {
            links.put(((Number) link.get("entry_id")).longValue(), link);
        }
        Map<Long, List<Map<String, Object>>> legacyByBag = new LinkedHashMap<>();
        for (Map<String, Object> legacy : legacyRows) {
            legacyByBag.computeIfAbsent(((Number) legacy.get("bag_batch_id")).longValue(), key -> new java.util.ArrayList<>())
                    .add(legacy);
        }
        // 培养架占用与分批入房共用同一 available_quantity，对账时必须一并计入已分配
        Map<Long, Integer> shelvedByBag = new LinkedHashMap<>();
        for (Map<String, Object> row : bagRepository.shelvedQuantities()) {
            shelvedByBag.put(((Number) row.get("bag_batch_id")).longValue(), ((Number) row.get("quantity")).intValue());
        }

        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (BagBatch bag : bags) {
            int entered = activeByBag.getOrDefault(bag.getId(), 0);
            int shelved = shelvedByBag.getOrDefault(bag.getId(), 0);
            int allocated = entered + shelved;
            int available = bag.getAvailableQuantity() == null ? 0 : bag.getAvailableQuantity();
            List<Map<String, Object>> entries = entriesByBag.getOrDefault(bag.getId(), List.of());
            for (Map<String, Object> entry : entries) {
                if ("在制".equals(entry.get("status"))) {
                    entry.put("withdrawable", true);
                    entry.put("withdrawReason", "");
                    applyWithdrawBlockers(entry, links.get(((Number) entry.get("id")).longValue()));
                } else {
                    entry.put("withdrawable", false);
                    entry.put("withdrawReason", "该入房记录已撤回，数量已归还");
                }
            }
            // 对账：available 与入房、上架同池。历史种子数据含历史出菇批次，不强制整体配平；
            // 这里只保证“剩余 + 已分配（入房在制+上架）”不超过原始总量（超了即数据异常），
            // 且剩余可用始终等于各菇房在制入房批次可继续占用的量。
            boolean consistent = available + allocated <= bag.getTotalQuantity()
                    && available >= 0 && entered >= 0 && shelved >= 0;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", bag.getId());
            item.put("batchNo", bag.getBatchNo());
            item.put("formula", bag.getFormula());
            item.put("status", bag.getStatus());
            item.put("inoculationDate", bag.getInoculationDate());
            item.put("fullDate", bag.getFullDate());
            item.put("totalQuantity", bag.getTotalQuantity());
            item.put("enteredQuantity", entered);
            item.put("shelvedQuantity", shelved);
            item.put("allocatedQuantity", allocated);
            item.put("availableQuantity", available);
            item.put("consistent", consistent);
            item.put("entries", entries);
            item.put("legacyBatches", legacyByBag.getOrDefault(bag.getId(), List.of()));
            result.add(item);
        }

        return Map.of("bags", result);
    }

    private void applyWithdrawBlockers(Map<String, Object> entry, Map<String, Object> link) {
        String fruitingStatus = (String) entry.get("fruiting_status");
        if ("环境暂停".equals(fruitingStatus)) {
            entry.put("withdrawable", false);
            entry.put("withdrawReason", "该批次正被环境越限处置暂停，处置单解除前不能撤回");
            return;
        }
        if (link == null) {
            // 理论上不会发生：在制记录必然配对一个出菇批次；缺了就按数据不一致拦截
            entry.put("withdrawable", false);
            entry.put("withdrawReason", "未找到对应的出菇批次，数据不一致，请联系管理员核对");
            return;
        }
        if (intValue(link, "harvest_count") > 0 || intValue(link, "inventory_count") > 0) {
            entry.put("withdrawable", false);
            entry.put("withdrawReason", "该批次已开始采收（存在采收录入或库存流水），不能撤回");
        } else if (intValue(link, "ticket_count") > 0) {
            entry.put("withdrawable", false);
            entry.put("withdrawReason", "该批次曾被环境越限处置单暂停，已产生环境处置关联记录，不能撤回");
        }
    }

    // ---------------- 分配：整批或拆次，一次提交一条入房 + 一个出菇批次 ----------------

    @Transactional
    public Map<String, Object> assign(Long bagId, RoomEntryRequest request) {
        if (request == null) {
            throw new BizException("请填写入房信息");
        }
        if (request.roomId() == null) {
            throw new BizException("请选择入房菇房");
        }
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new BizException("入房数量必须是大于 0 的整数");
        }
        if (request.plannedEntryDate() == null) {
            throw new BizException("请选择计划入房日");
        }
        if (request.manager() == null || request.manager().isBlank()) {
            throw new BizException("请填写负责人");
        }
        if (request.manager().trim().length() > 40) {
            throw new BizException("负责人不能超过 40 个字");
        }
        GrowRoom room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new BizException("所选菇房不存在"));
        // 先锁菌包批次行：同一批的并发分配串行，后面的条件扣减再兜一道，绝不超分
        BagBatch bag = bagRepository.findByIdForUpdate(bagId)
                .orElseThrow(() -> new BizException("菌包批次不存在"));
        if (!"已开袋".equals(bag.getStatus())) {
            throw new BizException("只有“已开袋”菌包才能分批入房，当前状态：" + bag.getStatus());
        }
        int available = bag.getAvailableQuantity() == null ? 0 : bag.getAvailableQuantity();
        if (request.quantity() > available) {
            throw new BizException("入房数量超过剩余可用量，当前最多可入房 " + available + " 包");
        }
        String manager = request.manager().trim();

        FruitingBatch fruiting = new FruitingBatch();
        String batchNo = nextFruitingBatchNo(bag.getId(), request.plannedEntryDate());
        fruiting.setBatchNo(batchNo);
        fruiting.setRoomId(room.getId());
        fruiting.setBagBatchId(bag.getId());
        fruiting.setSpecies(room.getSpecies());
        fruiting.setEntryDate(request.plannedEntryDate());
        fruiting.setExpectedYield(BigDecimal.valueOf(request.quantity()).multiply(EXPECTED_KG_PER_BAG));
        fruiting.setHarvestedYield(BigDecimal.ZERO);
        fruiting.setStatus("待采收");
        Long fruitingId = fruitingRepository.saveBatch(fruiting);
        fruiting.setId(fruitingId);

        // 预置一/二/三潮待采收占位（与历史批次口径一致，采收时间轴立即可见、可点击录入）
        for (int flushNo = 1; flushNo <= 3; flushNo++) {
            HarvestFlush flush = new HarvestFlush();
            flush.setBatchId(fruitingId);
            flush.setFlushNo(flushNo);
            flush.setYieldKg(BigDecimal.ZERO);
            flush.setStatus("待采收");
            fruitingRepository.saveFlush(flush);
        }

        RoomEntry entry = new RoomEntry();
        entry.setEntryNo(nextEntryNo(bag.getId()));
        entry.setBagBatchId(bag.getId());
        entry.setRoomId(room.getId());
        entry.setFruitingBatchId(fruitingId);
        entry.setQuantity(request.quantity());
        entry.setPlannedEntryDate(request.plannedEntryDate());
        entry.setManager(manager);
        entry.setStatus("在制");
        entry.setWithdrawnBy("");
        entry.setWithdrawnAt(null);
        entry.setFruitingBatchNo(batchNo);
        entry.setCreatedAt(LocalDateTime.now());
        entryRepository.save(entry);

        // 条件扣减：即使并发下可用量被别人先占用，也不会扣成负数；失败则整笔回滚（含上面的批次/记录）
        if (bagRepository.decreaseAvailable(bag.getId(), request.quantity()) == 0) {
            throw new BizException("菌包剩余可用量已变化，请刷新后重试");
        }

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("entryId", entry.getId());
        created.put("fruitingBatchId", fruitingId);
        created.put("fruitingBatchNo", batchNo);
        created.put("roomCode", room.getRoomCode());
        created.put("quantity", request.quantity());
        return created;
    }

    // ---------------- 撤回：仅限未开始出菇的在制分配，归还数量并删除出菇批次 ----------------

    @Transactional
    public void withdraw(Long entryId, String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BizException("请填写撤回操作人");
        }
        if (operator.trim().length() > 40) {
            throw new BizException("撤回操作人不能超过 40 个字");
        }
        // 锁入房记录，防同一笔被两人同时撤回；锁袋顺序（entry→bag）与分配（bag→entry）交叉，
        // 但撤回只锁自己的单行，且分配不锁 entry，故不会形成环路死锁。
        RoomEntry entry = entryRepository.findByIdForUpdate(entryId)
                .orElseThrow(() -> new BizException("入房记录不存在"));
        if (!"在制".equals(entry.getStatus())) {
            throw new BizException("该入房记录已撤回，不能重复撤回");
        }
        FruitingBatch fruiting = fruitingRepository.findByIdForUpdate(entry.getFruitingBatchId())
                .orElseThrow(() -> new BizException("对应的出菇批次不存在或已被删除，无法撤回"));
        if ("环境暂停".equals(fruiting.getStatus())) {
            throw new BizException("该批次正被环境越限处置暂停，处置单解除前不能撤回");
        }
        if (fruitingRepository.hasHarvestOrInventory(fruiting.getId())) {
            throw new BizException("该批次已开始采收（存在采收录入或库存流水），不能撤回");
        }
        if (fruitingRepository.countTicketLinks(fruiting.getId()) > 0) {
            throw new BizException("该批次曾被环境越限处置单暂停，已产生环境处置关联记录，不能撤回");
        }

        bagRepository.findByIdForUpdate(entry.getBagBatchId());
        // 此时已确认没有已采收潮次、库存流水和处置单关联，删掉预置的待采收占位行后才能删批次
        fruitingRepository.deletePendingFlushes(fruiting.getId());
        if (fruitingRepository.deleteById(fruiting.getId()) == 0) {
            throw new BizException("出菇批次状态已变化，请刷新后重试");
        }
        if (entryRepository.markWithdrawn(entry.getId(), operator.trim()) == 0) {
            throw new BizException("入房记录状态已变化，请刷新后重试");
        }
        bagRepository.increaseAvailable(entry.getBagBatchId(), entry.getQuantity());
    }

    // ---------------- 编号 ----------------

    /** 出菇批次号：FB-yyMMdd-袋批3位-本批序号2位；锁内单调序号 + batch_no 唯一索引双保险，绝不重复 */
    private String nextFruitingBatchNo(Long bagId, LocalDate date) {
        int seq = entryRepository.countAllByBag(bagId) + 1;
        return String.format("FB-%s-%03d%02d", date.format(ENTRY_FMT), bagId, seq);
    }

    /** 入房记录号：RF-yyMMdd-袋批3位-本批序号2位（含已撤回累计递增，编号永不复用） */
    private String nextEntryNo(Long bagId) {
        int seq = entryRepository.countAllByBag(bagId) + 1;
        return String.format("RF-%s-%03d%02d", LocalDate.now().format(ENTRY_FMT), bagId, seq);
    }

    private int intValue(Map<String, Object> map, String key) {
        Number value = (Number) map.get(key);
        return value == null ? 0 : value.intValue();
    }
}
