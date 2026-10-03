package com.fungus.farm.repository;

import com.fungus.farm.domain.BagAllocation;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class BagAllocationRepository {
    private static final String BOARD_SELECT = """
            SELECT ba.id, ba.allocation_no, ba.bag_batch_id, ba.room_id, ba.fruiting_batch_id,
                   ba.fruiting_batch_no, ba.quantity, ba.planned_entry_date, ba.manager, ba.status,
                   ba.created_at, ba.revoked_at, ba.revoked_by, ba.revoke_note,
                   gr.room_code, fb.status AS fruiting_status, fb.harvested_yield
            FROM bag_allocation ba
            JOIN grow_room gr ON gr.id = ba.room_id
            LEFT JOIN fruiting_batch fb ON fb.id = ba.fruiting_batch_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private final BeanPropertyRowMapper<BagAllocation> mapper = BeanPropertyRowMapper.newInstance(BagAllocation.class);

    public BagAllocationRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(dataSource).withTableName("bag_allocation").usingGeneratedKeyColumns("id");
    }

    public Optional<BagAllocation> findByIdForUpdate(Long id) {
        return jdbc.query("SELECT * FROM bag_allocation WHERE id=:id FOR UPDATE", Map.of("id", id), mapper)
                .stream().findFirst();
    }

    public Optional<BagAllocation> findByRequestKey(String requestKey) {
        return jdbc.query("SELECT * FROM bag_allocation WHERE request_key=:key", Map.of("key", requestKey), mapper)
                .stream().findFirst();
    }

    public Long save(BagAllocation allocation) {
        return insert.executeAndReturnKey(new BeanPropertySqlParameterSource(allocation)).longValue();
    }

    /** 撤回：置状态、清空批次引用并留下撤回轨迹；只有仍是已入房的记录才能被更新 */
    public int markRevoked(Long id, String operator, String note, LocalDateTime revokedAt) {
        return jdbc.update("""
                UPDATE bag_allocation
                SET status='已撤回', fruiting_batch_id=NULL, revoked_at=:at, revoked_by=:by, revoke_note=:note
                WHERE id=:id AND status='已入房'
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("at", revokedAt)
                .addValue("by", operator)
                .addValue("note", note));
    }

    /** 看板行：关联菇房与出菇批次当前状态，刷新即与菇房出菇批次一致 */
    public List<Map<String, Object>> findBoardRows() {
        return jdbc.queryForList(BOARD_SELECT + " ORDER BY ba.id DESC", Map.of());
    }

    public Optional<Map<String, Object>> findBoardRow(Long id) {
        return jdbc.queryForList(BOARD_SELECT + " WHERE ba.id=:id", Map.of("id", id)).stream().findFirst();
    }

    /** 各出菇批次挂过的环境处置单（只要关联过处置单就须保留原状，不能撤回） */
    public Map<Long, Long> envTicketIdsByBatch(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return toMap(jdbc.queryForList("""
                SELECT fruiting_batch_id AS fid, MIN(ticket_id) AS ref FROM environment_ticket_batch
                WHERE fruiting_batch_id IN (:ids) GROUP BY fruiting_batch_id
                """, new MapSqlParameterSource("ids", batchIds)));
    }

    public Map<Long, Long> harvestedFlushCounts(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return toMap(jdbc.queryForList("""
                SELECT batch_id AS fid, COUNT(*) AS ref FROM harvest_flush
                WHERE batch_id IN (:ids) AND status='已采收' GROUP BY batch_id
                """, new MapSqlParameterSource("ids", batchIds)));
    }

    public Map<Long, Long> inventoryCounts(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        return toMap(jdbc.queryForList("""
                SELECT fruiting_batch_id AS fid, COUNT(*) AS ref FROM harvest_inventory
                WHERE fruiting_batch_id IN (:ids) GROUP BY fruiting_batch_id
                """, new MapSqlParameterSource("ids", batchIds)));
    }

    private Map<Long, Long> toMap(List<Map<String, Object>> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            result.put(((Number) row.get("fid")).longValue(), ((Number) row.get("ref")).longValue());
        }
        return result;
    }
}
