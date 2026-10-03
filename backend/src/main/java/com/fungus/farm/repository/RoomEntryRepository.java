package com.fungus.farm.repository;

import com.fungus.farm.domain.RoomEntry;
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
public class RoomEntryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private final BeanPropertyRowMapper<RoomEntry> mapper = BeanPropertyRowMapper.newInstance(RoomEntry.class);

    public RoomEntryRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(dataSource).withTableName("room_entry").usingGeneratedKeyColumns("id");
    }

    public Long save(RoomEntry entry) {
        return insert.executeAndReturnKey(new BeanPropertySqlParameterSource(entry)).longValue();
    }

    public Optional<RoomEntry> findById(Long id) {
        return jdbc.query("SELECT * FROM room_entry WHERE id=:id", Map.of("id", id), mapper).stream().findFirst();
    }

    /** 撤回操作的行锁：同一入房记录的并发撤回在此串行，只有一笔能成功 */
    public Optional<RoomEntry> findByIdForUpdate(Long id) {
        return jdbc.query("SELECT * FROM room_entry WHERE id=:id FOR UPDATE", Map.of("id", id), mapper).stream().findFirst();
    }

    /**
     * 该菌包批次累计的入房记录数（含已撤回，在 bag 行锁内调用）。
     * 作为入房/出菇批次的本批序号：撤回只删 fruiting_batch、room_entry 留痕，
     * 因此序号单调递增，编号永不复用、不会与历史或已撤回记录撞号。
     */
    public int countAllByBag(Long bagBatchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM room_entry WHERE bag_batch_id=:bagId",
                Map.of("bagId", bagBatchId), Integer.class);
        return count == null ? 0 : count;
    }

    public int markWithdrawn(Long id, String operator) {
        return jdbc.update("""
                UPDATE room_entry SET status='已撤回', withdrawn_by=:operator, withdrawn_at=NOW(),
                    fruiting_batch_id=NULL
                WHERE id=:id AND status='在制'
                """, new MapSqlParameterSource().addValue("id", id).addValue("operator", operator));
    }

    /**
     * 全量入房记录（含已撤回留痕），带上菇房与出菇批次当前状态，供页面展示去向。
     * 出菇批次状态用于撤回资格判定；已撤回记录的 fruiting_batch_id 已置空，编号取快照列。
     */
    public List<Map<String, Object>> findAllRowsWithRoom() {
        String sql = """
                SELECT re.id, re.entry_no, re.bag_batch_id, re.room_id, re.fruiting_batch_id,
                       re.quantity, re.planned_entry_date, re.manager, re.status,
                       re.withdrawn_by, re.withdrawn_at, re.fruiting_batch_no, re.created_at,
                       gr.room_code, gr.species AS room_species,
                       fb.status AS fruiting_status
                FROM room_entry re
                JOIN grow_room gr ON gr.id = re.room_id
                LEFT JOIN fruiting_batch fb ON fb.id = re.fruiting_batch_id
                ORDER BY re.id
                """;
        return jdbc.queryForList(sql, Map.of());
    }

    /** 每条在制入房记录的下游关联计数（采收潮次、库存流水、环境处置单暂停快照），一次取齐避免 N+1 */
    public List<Map<String, Object>> findActiveLinkCounts() {
        String sql = """
                SELECT re.id AS entry_id,
                       (SELECT COUNT(*) FROM harvest_flush hf
                          WHERE hf.batch_id = re.fruiting_batch_id AND hf.status='已采收') AS harvest_count,
                       (SELECT COUNT(*) FROM harvest_inventory hi
                          WHERE hi.fruiting_batch_id = re.fruiting_batch_id) AS inventory_count,
                       (SELECT COUNT(*) FROM environment_ticket_batch etb
                          WHERE etb.fruiting_batch_id = re.fruiting_batch_id) AS ticket_count
                FROM room_entry re
                WHERE re.status='在制'
                """;
        return jdbc.queryForList(sql, Map.of());
    }

    /**
     * 历史出菇批次：fruiting_batch 中没有任何 room_entry 配对的批次，
     * 新流程不强制补造入房记录，页面仅在“去向（历史批次）”里只读展示。
     */
    public List<Map<String, Object>> findLegacyFruitingRows() {
        String sql = """
                SELECT fb.id, fb.batch_no, fb.bag_batch_id, fb.room_id, fb.species,
                       fb.entry_date, fb.status, gr.room_code
                FROM fruiting_batch fb
                JOIN grow_room gr ON gr.id = fb.room_id
                LEFT JOIN room_entry re ON re.fruiting_batch_id = fb.id
                WHERE re.id IS NULL
                ORDER BY fb.id
                """;
        return jdbc.queryForList(sql, Map.of());
    }
}
