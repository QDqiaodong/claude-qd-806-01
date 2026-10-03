package com.fungus.farm.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fungus.farm.common.BizException;
import com.fungus.farm.common.EnvironmentLimits;
import com.fungus.farm.domain.EnvironmentRecord;
import com.fungus.farm.domain.EnvironmentTicket;
import com.fungus.farm.domain.EnvironmentTicketBatch;
import com.fungus.farm.domain.EnvironmentTicketEvent;
import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.domain.GrowRoom;
import com.fungus.farm.domain.RoomEnvState;
import com.fungus.farm.dto.TicketClaimRequest;
import com.fungus.farm.dto.TicketNoteRequest;
import com.fungus.farm.dto.TicketResolveRequest;
import com.fungus.farm.repository.EnvironmentRepository;
import com.fungus.farm.repository.EnvironmentTicketRepository;
import com.fungus.farm.repository.FruitingRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 环境越限处置闭环：
 * 连续 3 条越限开单并暂停在产批次 → 值班接单/记录处理 → 连续 6 条恢复进入恢复待确认
 * → 确认解除后只恢复本单暂停的批次。迟到/乱序读数只存档，不推进状态机。
 */
@Service
public class EnvironmentTicketService {
    public static final String STATUS_PENDING = "待接单";
    public static final String STATUS_PROCESSING = "处理中";
    public static final String STATUS_RECOVERING = "恢复待确认";
    public static final String STATUS_RESOLVED = "已解除";

    private static final Set<String> STATUSES = Set.of(STATUS_PENDING, STATUS_PROCESSING, STATUS_RECOVERING, STATUS_RESOLVED);
    private static final int TRIGGER_STREAK = 3;
    private static final int RECOVER_STREAK = 6;
    private static final int RECENT_ID_KEEP = 6;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EnvironmentTicketRepository ticketRepository;
    private final EnvironmentRepository environmentRepository;
    private final FruitingRepository fruitingRepository;
    private final GrowRoomRepository roomRepository;
    private final ObjectMapper objectMapper;

    public EnvironmentTicketService(EnvironmentTicketRepository ticketRepository,
            EnvironmentRepository environmentRepository, FruitingRepository fruitingRepository,
            GrowRoomRepository roomRepository, ObjectMapper objectMapper) {
        this.ticketRepository = ticketRepository;
        this.environmentRepository = environmentRepository;
        this.fruitingRepository = fruitingRepository;
        this.roomRepository = roomRepository;
        this.objectMapper = objectMapper;
    }

    // ---------------- 查询 ----------------

    public List<Map<String, Object>> tickets(String status) {
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new BizException("处置单状态必须为待接单、处理中、恢复待确认或已解除");
        }
        List<Map<String, Object>> tickets = ticketRepository.findAllWithRoom(status);
        if (tickets.isEmpty()) {
            return tickets;
        }
        List<Long> ids = tickets.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        Map<Long, List<EnvironmentTicketEvent>> events = ticketRepository.findEventsByTicketIds(ids);
        Map<Long, List<Map<String, Object>>> batches = ticketRepository.findBatchRowsByTicketIds(ids);
        for (Map<String, Object> ticket : tickets) {
            Long id = ((Number) ticket.get("id")).longValue();
            ticket.put("events", events.getOrDefault(id, List.of()));
            ticket.put("batches", batches.getOrDefault(id, List.of()));
            ticket.put("triggerReadingList", parseTriggerReadings((String) ticket.get("trigger_readings")));
        }
        return tickets;
    }

    // ---------------- 读数驱动的状态机（与 EnvironmentService.add 同事务） ----------------

    /**
     * 把一条新读数折算进该菇房的判定。只有比水位线更新的读数才推进状态机；
     * 迟到或乱序读数直接忽略，避免误触发、重复开单或冲掉较新的判断。
     */
    @Transactional
    public void processReading(GrowRoom room, EnvironmentRecord record) {
        RoomEnvState state = ticketRepository.lockState(room.getId());
        LocalDateTime watermark = state.getLastRecordedAt();
        if (watermark != null && !record.getRecordedAt().isAfter(watermark)) {
            return;
        }
        state.setLastRecordedAt(record.getRecordedAt());
        state.setRecentIds(pushRecent(state.getRecentIds(), record.getId()));
        boolean overLimit = Boolean.TRUE.equals(record.getOverLimit());
        EnvironmentTicket ticket = ticketRepository.findActiveByRoomId(room.getId()).orElse(null);
        if (overLimit) {
            state.setOverStreak(state.getOverStreak() + 1);
            state.setRecoverStreak(0);
            if (ticket == null) {
                if (state.getOverStreak() >= TRIGGER_STREAK) {
                    createTicket(room, record, state);
                }
            } else {
                accumulateOverLimit(ticket, room, record);
            }
        } else {
            state.setRecoverStreak(state.getRecoverStreak() + 1);
            state.setOverStreak(0);
            if (ticket != null) {
                accumulateRecover(ticket, record, state.getRecoverStreak());
            }
        }
        ticketRepository.updateState(state);
    }

    /** 连续越限达到阈值：开出该房唯一一张进行中处置单，并暂停仍在生产的出菇批次 */
    private void createTicket(GrowRoom room, EnvironmentRecord record, RoomEnvState state) {
        EnvironmentTicket ticket = new EnvironmentTicket();
        ticket.setRoomId(room.getId());
        ticket.setStatus(STATUS_PENDING);
        ticket.setTriggerMetric(String.join("；", EnvironmentLimits.violations(room.getSpecies(), record)));
        ticket.setTriggerReadings(writeTriggerReadings(triggerReadings(room.getId(), state.getRecentIds())));
        ticket.setTriggerAt(record.getRecordedAt());
        ticket.setOverCount(state.getOverStreak());
        ticket.setRecoverCount(0);
        ticket.setLastRecordedAt(record.getRecordedAt());
        ticket.setCreatedAt(LocalDateTime.now());
        Long ticketId = ticketRepository.save(ticket);
        addEvent(ticketId, "触发", null,
                "连续 " + state.getOverStreak() + " 条读数越限：" + ticket.getTriggerMetric(), record.getRecordedAt());
        List<FruitingBatch> batches = fruitingRepository.findProductionByRoomId(room.getId());
        for (FruitingBatch batch : batches) {
            EnvironmentTicketBatch item = new EnvironmentTicketBatch();
            item.setTicketId(ticketId);
            item.setFruitingBatchId(batch.getId());
            item.setPrevStatus(batch.getStatus());
            item.setRestored(false);
            ticketRepository.saveTicketBatch(item);
            fruitingRepository.updateStatus(batch.getId(), "环境暂停");
        }
        if (!batches.isEmpty()) {
            String batchNos = batches.stream().map(FruitingBatch::getBatchNo).collect(Collectors.joining("、"));
            addEvent(ticketId, "暂停批次", null,
                    "环境暂停 " + batches.size() + " 个在产出菇批次：" + batchNos, record.getRecordedAt());
        }
    }

    /** 异常继续恶化：累积到原处置单；恢复待确认期间再次越限则退回处理中，重新累计恢复读数 */
    private void accumulateOverLimit(EnvironmentTicket ticket, GrowRoom room, EnvironmentRecord record) {
        ticket.setOverCount(ticket.getOverCount() + 1);
        ticket.setRecoverCount(0);
        ticket.setLastRecordedAt(record.getRecordedAt());
        String metric = String.join("；", EnvironmentLimits.violations(room.getSpecies(), record));
        String detail = "第 " + ticket.getOverCount() + " 条越限读数（采集于 "
                + record.getRecordedAt().format(FMT) + "）：" + metric;
        if (STATUS_RECOVERING.equals(ticket.getStatus())) {
            ticket.setStatus(ticket.getClaimedBy() == null ? STATUS_PENDING : STATUS_PROCESSING);
            ticketRepository.updateProgress(ticket);
            addEvent(ticket.getId(), "反复", null,
                    "恢复确认前再次越限，退回" + ticket.getStatus() + "。" + detail, record.getRecordedAt());
        } else {
            ticketRepository.updateProgress(ticket);
            addEvent(ticket.getId(), "恶化", null, detail, record.getRecordedAt());
        }
    }

    /** 连续恢复读数到齐 6 条后，处置单进入恢复待确认 */
    private void accumulateRecover(EnvironmentTicket ticket, EnvironmentRecord record, int recoverStreak) {
        ticket.setRecoverCount(recoverStreak);
        ticket.setLastRecordedAt(record.getRecordedAt());
        if (recoverStreak >= RECOVER_STREAK && !STATUS_RECOVERING.equals(ticket.getStatus())) {
            ticket.setStatus(STATUS_RECOVERING);
            ticketRepository.updateProgress(ticket);
            addEvent(ticket.getId(), "恢复待确认", null,
                    "连续 " + recoverStreak + " 条读数恢复到适宜范围，可确认解除", record.getRecordedAt());
        } else {
            ticketRepository.updateProgress(ticket);
        }
    }

    // ---------------- 值班操作（与读数处理共用房间水位线锁，保证串行） ----------------

    @Transactional
    public void claim(Long ticketId, TicketClaimRequest request) {
        if (request.operator() == null || request.operator().isBlank()) {
            throw new BizException("接单人不能为空");
        }
        if (request.expectedFinishAt() == null) {
            throw new BizException("预计完成时间不能为空");
        }
        EnvironmentTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException("处置单不存在"));
        ticketRepository.lockState(ticket.getRoomId());
        String operator = request.operator().trim();
        int updated = ticketRepository.claim(ticketId, operator, request.expectedFinishAt());
        if (updated == 0) {
            throw new BizException("该处置单已被其他值班人员接单或状态已变化，请刷新查看");
        }
        addEvent(ticketId, "接单", operator,
                "预计完成时间 " + request.expectedFinishAt().format(FMT), LocalDateTime.now());
    }

    @Transactional
    public void addNote(Long ticketId, TicketNoteRequest request) {
        if (request.operator() == null || request.operator().isBlank()) {
            throw new BizException("经办人不能为空");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new BizException("处理记录不能为空");
        }
        EnvironmentTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException("处置单不存在"));
        ticketRepository.lockState(ticket.getRoomId());
        EnvironmentTicket fresh = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new BizException("处置单不存在"));
        if (STATUS_RESOLVED.equals(fresh.getStatus())) {
            throw new BizException("处置单已解除，不能再填写处理记录");
        }
        addEvent(ticketId, "处理记录", request.operator().trim(), request.content().trim(), LocalDateTime.now());
    }

    @Transactional
    public void resolve(Long ticketId, TicketResolveRequest request) {
        if (request.operator() == null || request.operator().isBlank()) {
            throw new BizException("解除人不能为空");
        }
        EnvironmentTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BizException("处置单不存在"));
        ticketRepository.lockState(ticket.getRoomId());
        String operator = request.operator().trim();
        int updated = ticketRepository.resolve(ticketId, operator);
        if (updated == 0) {
            throw new BizException("只有恢复待确认状态的处置单才能解除（可能已解除或再次越限退回处理中），请刷新查看");
        }
        // 只恢复本单暂停且当前仍处于环境暂停的批次；原本人工暂停或已结束的批次不动
        int restored = 0;
        for (EnvironmentTicketBatch item : ticketRepository.findPendingBatches(ticketId)) {
            if (fruitingRepository.restoreStatusIfEnvPaused(item.getFruitingBatchId(), item.getPrevStatus()) > 0) {
                ticketRepository.markBatchRestored(item.getId());
                restored++;
            }
        }
        addEvent(ticketId, "解除", operator, "恢复 " + restored + " 个出菇批次至原状态", LocalDateTime.now());
    }

    // ---------------- 历史数据回填 ----------------

    /** 首次启动按采集时间重放历史读数，补齐水位线与越限处置单（只在水位线为空时执行一次） */
    @Transactional
    public void backfill() {
        if (ticketRepository.countStates() > 0 || environmentRepository.countAll() == 0) {
            return;
        }
        for (GrowRoom room : roomRepository.findAll()) {
            for (EnvironmentRecord record : environmentRepository.findByRoomOrdered(room.getId())) {
                processReading(room, record);
            }
        }
    }

    // ---------------- 内部工具 ----------------

    private void addEvent(Long ticketId, String type, String actor, String detail, LocalDateTime eventTime) {
        EnvironmentTicketEvent event = new EnvironmentTicketEvent();
        event.setTicketId(ticketId);
        event.setEventType(type);
        event.setActor(actor);
        event.setDetail(detail);
        event.setCreatedAt(eventTime == null ? LocalDateTime.now() : eventTime);
        ticketRepository.saveEvent(event);
    }

    /** 触发快照取推进水位线的最近 3 条读数，而不是库里按时间倒序的 3 条，避免混入迟到读数 */
    private List<EnvironmentRecord> triggerReadings(Long roomId, String recentIds) {
        List<Long> ids = parseRecent(recentIds);
        if (ids.size() > TRIGGER_STREAK) {
            ids = ids.subList(ids.size() - TRIGGER_STREAK, ids.size());
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return environmentRepository.findByIds(roomId, ids);
    }

    private String pushRecent(String recentIds, Long recordId) {
        List<Long> ids = parseRecent(recentIds);
        ids.add(recordId);
        if (ids.size() > RECENT_ID_KEEP) {
            ids = ids.subList(ids.size() - RECENT_ID_KEEP, ids.size());
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Long> parseRecent(String recentIds) {
        List<Long> ids = new ArrayList<>();
        if (recentIds != null && !recentIds.isBlank()) {
            Arrays.stream(recentIds.split(",")).filter(s -> !s.isBlank()).forEach(s -> ids.add(Long.valueOf(s.trim())));
        }
        return ids;
    }

    private String writeTriggerReadings(List<EnvironmentRecord> readings) {
        try {
            return objectMapper.writeValueAsString(readings);
        } catch (Exception e) {
            throw new BizException("触发读数快照序列化失败");
        }
    }

    private List<Map<String, Object>> parseTriggerReadings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
