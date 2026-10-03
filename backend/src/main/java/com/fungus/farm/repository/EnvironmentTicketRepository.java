package com.fungus.farm.repository;

import com.fungus.farm.domain.EnvironmentTicket;
import com.fungus.farm.domain.EnvironmentTicketBatch;
import com.fungus.farm.domain.EnvironmentTicketEvent;
import com.fungus.farm.domain.RoomEnvState;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class EnvironmentTicketRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert eventInsert;
    private final SimpleJdbcInsert ticketBatchInsert;
    private final BeanPropertyRowMapper<EnvironmentTicket> ticketMapper = BeanPropertyRowMapper.newInstance(EnvironmentTicket.class);
    private final BeanPropertyRowMapper<EnvironmentTicketEvent> eventMapper = BeanPropertyRowMapper.newInstance(EnvironmentTicketEvent.class);
    private final BeanPropertyRowMapper<EnvironmentTicketBatch> ticketBatchMapper = BeanPropertyRowMapper.newInstance(EnvironmentTicketBatch.class);
    private final BeanPropertyRowMapper<RoomEnvState> stateMapper = BeanPropertyRowMapper.newInstance(RoomEnvState.class);

    public EnvironmentTicketRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.eventInsert = new SimpleJdbcInsert(dataSource).withTableName("environment_ticket_event").usingGeneratedKeyColumns("id");
        this.ticketBatchInsert = new SimpleJdbcInsert(dataSource).withTableName("environment_ticket_batch").usingGeneratedKeyColumns("id");
    }

    // ---------------- 菇房水位线（每房一行，SELECT FOR UPDATE 串行化该房的判定） ----------------

    /**
     * 取出并锁定该菇房的水位线行，不存在则先建行。调用方必须处于事务中。
     * 先锁定读再补建行：避免 INSERT IGNORE 在已有行上加 S 锁、随后 FOR UPDATE 升 X 锁造成死锁。
     */
    public RoomEnvState lockState(Long roomId) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("roomId", roomId);
        Optional<RoomEnvState> existing = jdbc.query(
                "SELECT * FROM room_env_state WHERE room_id=:roomId FOR UPDATE", params, stateMapper)
                .stream().findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            jdbc.update("INSERT INTO room_env_state (room_id) VALUES (:roomId)", params);
        } catch (DuplicateKeyException e) {
            // 并发建行：以已存在的行为准，重新锁定读取
        }
        return jdbc.query("SELECT * FROM room_env_state WHERE room_id=:roomId FOR UPDATE", params, stateMapper).get(0);
    }

    public void updateState(RoomEnvState state) {
        jdbc.update("""
                UPDATE room_env_state SET last_recorded_at=:lastRecordedAt, over_streak=:overStreak,
                    recover_streak=:recoverStreak, recent_ids=:recentIds
                WHERE room_id=:roomId
                """, new BeanPropertySqlParameterSource(state));
    }

    public int countStates() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_env_state", Map.of(), Integer.class);
    }

    // ---------------- 处置单 ----------------

    public Long save(EnvironmentTicket ticket) {
        String sql = """
                INSERT INTO environment_ticket (room_id, status, trigger_metric, trigger_readings, trigger_at,
                    over_count, recover_count, last_recorded_at, claimed_by, claimed_at, expected_finish_at,
                    resolved_by, resolved_at, created_at)
                VALUES (:roomId, :status, :triggerMetric, :triggerReadings, :triggerAt,
                    :overCount, :recoverCount, :lastRecordedAt, :claimedBy, :claimedAt, :expectedFinishAt,
                    :resolvedBy, :resolvedAt, :createdAt)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, new BeanPropertySqlParameterSource(ticket), keyHolder, new String[] {"id"});
        return keyHolder.getKey().longValue();
    }

    public Optional<EnvironmentTicket> findById(Long id) {
        return jdbc.query("SELECT * FROM environment_ticket WHERE id=:id", Map.of("id", id), ticketMapper)
                .stream().findFirst();
    }

    public Optional<EnvironmentTicket> findByIdForUpdate(Long id) {
        return jdbc.query("SELECT * FROM environment_ticket WHERE id=:id FOR UPDATE", Map.of("id", id), ticketMapper)
                .stream().findFirst();
    }

    /** 每房最多一张未解除单（有 active_room_id 唯一索引兜底） */
    public Optional<EnvironmentTicket> findActiveByRoomId(Long roomId) {
        return jdbc.query("SELECT * FROM environment_ticket WHERE room_id=:roomId AND status<>'已解除' LIMIT 1",
                Map.of("roomId", roomId), ticketMapper).stream().findFirst();
    }

    /** 读数驱动的进度推进：状态、累计越限、连续恢复、水位线 */
    public void updateProgress(EnvironmentTicket ticket) {
        jdbc.update("""
                UPDATE environment_ticket SET status=:status, over_count=:overCount,
                    recover_count=:recoverCount, last_recorded_at=:lastRecordedAt
                WHERE id=:id
                """, new BeanPropertySqlParameterSource(ticket));
    }

    /** 条件更新接单：只有待接单的单子才能被接走，并发下只有一人返回 1 */
    public int claim(Long id, String operator, LocalDateTime expectedFinishAt) {
        return jdbc.update("""
                UPDATE environment_ticket SET status='处理中', claimed_by=:operator, claimed_at=NOW(),
                    expected_finish_at=:expectedFinishAt
                WHERE id=:id AND status='待接单'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("operator", operator)
                .addValue("expectedFinishAt", expectedFinishAt));
    }

    /** 条件更新解除：只有恢复待确认的单子才能解除 */
    public int resolve(Long id, String operator) {
        return jdbc.update("""
                UPDATE environment_ticket SET status='已解除', resolved_by=:operator, resolved_at=NOW()
                WHERE id=:id AND status='恢复待确认'
                """, new MapSqlParameterSource().addValue("id", id).addValue("operator", operator));
    }

    public List<Map<String, Object>> findAllWithRoom(String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.room_id, t.status, t.trigger_metric, t.trigger_readings, t.trigger_at,
                       t.over_count, t.recover_count, t.last_recorded_at,
                       t.claimed_by, t.claimed_at, t.expected_finish_at,
                       t.resolved_by, t.resolved_at, t.created_at, t.updated_at,
                       gr.room_code, gr.species
                FROM environment_ticket t
                JOIN grow_room gr ON gr.id = t.room_id
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" WHERE t.status=:status");
            params.addValue("status", status);
        }
        sql.append(" ORDER BY CASE WHEN t.status='已解除' THEN 1 ELSE 0 END, t.id DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    // ---------------- 时间线事件 ----------------

    public Long saveEvent(EnvironmentTicketEvent event) {
        return eventInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(event)).longValue();
    }

    public Map<Long, List<EnvironmentTicketEvent>> findEventsByTicketIds(List<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.query("SELECT * FROM environment_ticket_event WHERE ticket_id IN (:ids) ORDER BY id",
                        new MapSqlParameterSource("ids", ticketIds), eventMapper)
                .stream()
                .collect(Collectors.groupingBy(EnvironmentTicketEvent::getTicketId, LinkedHashMap::new, Collectors.toList()));
    }

    // ---------------- 处置单暂停的批次 ----------------

    public Long saveTicketBatch(EnvironmentTicketBatch item) {
        return ticketBatchInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(item)).longValue();
    }

    public List<EnvironmentTicketBatch> findPendingBatches(Long ticketId) {
        return jdbc.query("SELECT * FROM environment_ticket_batch WHERE ticket_id=:ticketId AND restored=0 ORDER BY id",
                Map.of("ticketId", ticketId), ticketBatchMapper);
    }

    public void markBatchRestored(Long id) {
        jdbc.update("UPDATE environment_ticket_batch SET restored=1 WHERE id=:id", Map.of("id", id));
    }

    public Map<Long, List<Map<String, Object>>> findBatchRowsByTicketIds(List<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return Map.of();
        }
        String sql = """
                SELECT tb.id, tb.ticket_id, tb.fruiting_batch_id, tb.prev_status, tb.restored,
                       fb.batch_no, fb.status AS current_status
                FROM environment_ticket_batch tb
                JOIN fruiting_batch fb ON fb.id = tb.fruiting_batch_id
                WHERE tb.ticket_id IN (:ids)
                ORDER BY tb.id
                """;
        return jdbc.queryForList(sql, new MapSqlParameterSource("ids", ticketIds))
                .stream()
                .collect(Collectors.groupingBy(row -> ((Number) row.get("ticket_id")).longValue(),
                        LinkedHashMap::new, Collectors.toList()));
    }
}
