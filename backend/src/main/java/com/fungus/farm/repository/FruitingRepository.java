package com.fungus.farm.repository;

import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.domain.HarvestFlush;
import com.fungus.farm.domain.HarvestInventory;
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
public class FruitingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert batchInsert;
    private final SimpleJdbcInsert flushInsert;
    private final SimpleJdbcInsert inventoryInsert;
    private final BeanPropertyRowMapper<FruitingBatch> batchMapper = BeanPropertyRowMapper.newInstance(FruitingBatch.class);
    private final BeanPropertyRowMapper<HarvestFlush> flushMapper = BeanPropertyRowMapper.newInstance(HarvestFlush.class);

    public FruitingRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.batchInsert = new SimpleJdbcInsert(dataSource).withTableName("fruiting_batch").usingGeneratedKeyColumns("id");
        this.flushInsert = new SimpleJdbcInsert(dataSource).withTableName("harvest_flush").usingGeneratedKeyColumns("id");
        this.inventoryInsert = new SimpleJdbcInsert(dataSource).withTableName("harvest_inventory").usingGeneratedKeyColumns("id");
    }

    public List<Map<String, Object>> findAllWithHarvests() {
        String sql = """
                SELECT fb.id, fb.batch_no, fb.species, fb.entry_date, fb.expected_yield,
                       fb.harvested_yield, fb.status, gr.room_code, bb.batch_no AS bag_batch_no,
                       COALESCE(SUM(CASE WHEN hf.flush_no=1 THEN hf.yield_kg ELSE 0 END),0) AS flush_one,
                       COALESCE(SUM(CASE WHEN hf.flush_no=2 THEN hf.yield_kg ELSE 0 END),0) AS flush_two,
                       COALESCE(SUM(CASE WHEN hf.flush_no=3 THEN hf.yield_kg ELSE 0 END),0) AS flush_three
                FROM fruiting_batch fb
                JOIN grow_room gr ON gr.id=fb.room_id
                JOIN bag_batch bb ON bb.id=fb.bag_batch_id
                LEFT JOIN harvest_flush hf ON hf.batch_id=fb.id
                GROUP BY fb.id, fb.batch_no, fb.species, fb.entry_date, fb.expected_yield,
                         fb.harvested_yield, fb.status, gr.room_code, bb.batch_no
                ORDER BY fb.entry_date DESC, fb.id
                """;
        return jdbc.queryForList(sql, Map.of());
    }

    public Optional<FruitingBatch> findById(Long id) {
        return jdbc.query("SELECT * FROM fruiting_batch WHERE id=:id", Map.of("id", id), batchMapper).stream().findFirst();
    }

    /** 仍在生产的批次：已结束、环境暂停、人工暂停都不算在产 */
    public List<FruitingBatch> findProductionByRoomId(Long roomId) {
        return jdbc.query("""
                SELECT * FROM fruiting_batch
                WHERE room_id=:roomId AND status NOT IN ('已结束', '环境暂停', '人工暂停')
                ORDER BY id
                """, Map.of("roomId", roomId), batchMapper);
    }

    public void updateStatus(Long batchId, String status) {
        jdbc.update("UPDATE fruiting_batch SET status=:status WHERE id=:id",
                new MapSqlParameterSource().addValue("id", batchId).addValue("status", status));
    }

    /** 解除处置单时精确恢复：只有当前仍是环境暂停的批次才回到原状态 */
    public int restoreStatusIfEnvPaused(Long batchId, String prevStatus) {
        return jdbc.update("UPDATE fruiting_batch SET status=:status WHERE id=:id AND status='环境暂停'",
                new MapSqlParameterSource().addValue("id", batchId).addValue("status", prevStatus));
    }

    public List<HarvestFlush> findFlushes(Long batchId) {
        return jdbc.query("SELECT * FROM harvest_flush WHERE batch_id=:batchId ORDER BY flush_no", Map.of("batchId", batchId), flushMapper);
    }

    public Optional<HarvestFlush> findFlush(Long batchId, Integer flushNo) {
        return jdbc.query("SELECT * FROM harvest_flush WHERE batch_id=:batchId AND flush_no=:flushNo",
                new MapSqlParameterSource().addValue("batchId", batchId).addValue("flushNo", flushNo), flushMapper).stream().findFirst();
    }

    public Long saveBatch(FruitingBatch batch) {
        return batchInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(batch)).longValue();
    }

    public Long saveFlush(HarvestFlush flush) {
        return flushInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(flush)).longValue();
    }

    public void updateFlush(HarvestFlush flush) {
        jdbc.update("""
                UPDATE harvest_flush SET harvest_date=:harvestDate, grade_a_yield=:gradeAYield,
                    grade_b_yield=:gradeBYield, grade_c_yield=:gradeCYield, yield_kg=:yieldKg,
                    harvested_by=:harvestedBy, status=:status WHERE id=:id
                """, new BeanPropertySqlParameterSource(flush));
    }

    public void updateBatchYield(Long batchId, java.math.BigDecimal harvestedYield) {
        jdbc.update("UPDATE fruiting_batch SET harvested_yield=:yield WHERE id=:id",
                new MapSqlParameterSource().addValue("id", batchId).addValue("yield", harvestedYield));
    }

    public Long saveInventory(HarvestInventory inventory) {
        return inventoryInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(inventory)).longValue();
    }

    public List<Map<String, Object>> yieldStatsByRoom() {
        String sql = """
                SELECT gr.id AS room_id, gr.room_code, gr.species,
                       COALESCE(SUM(CASE WHEN hf.flush_no=1 THEN hf.yield_kg ELSE 0 END),0) AS flush_one_yield,
                       COALESCE(SUM(CASE WHEN hf.flush_no=2 THEN hf.yield_kg ELSE 0 END),0) AS flush_two_yield,
                       COALESCE(SUM(CASE WHEN hf.flush_no=3 THEN hf.yield_kg ELSE 0 END),0) AS flush_three_yield,
                       COALESCE(SUM(hf.yield_kg),0) AS total_yield,
                       COALESCE(SUM(DISTINCT fb.expected_yield),0) AS expected_yield
                FROM grow_room gr
                LEFT JOIN fruiting_batch fb ON fb.room_id=gr.id
                LEFT JOIN harvest_flush hf ON hf.batch_id=fb.id
                GROUP BY gr.id, gr.room_code, gr.species
                ORDER BY gr.room_code
                """;
        return jdbc.queryForList(sql, Map.of());
    }
}
