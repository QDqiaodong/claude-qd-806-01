package com.fungus.farm.repository;

import com.fungus.farm.domain.BagBatch;
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
public class BagRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private final BeanPropertyRowMapper<BagBatch> mapper = BeanPropertyRowMapper.newInstance(BagBatch.class);

    public BagRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(dataSource).withTableName("bag_batch").usingGeneratedKeyColumns("id");
    }

    public List<BagBatch> findAll(String status) {
        String sql = "SELECT * FROM bag_batch" + (status == null || status.isBlank() ? "" : " WHERE status=:status") + " ORDER BY batch_no";
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            params.addValue("status", status);
        }
        return jdbc.query(sql, params, mapper);
    }

    public Optional<BagBatch> findById(Long id) {
        return jdbc.query("SELECT * FROM bag_batch WHERE id=:id", Map.of("id", id), mapper).stream().findFirst();
    }

    /** 事务内行锁：同一菌包批次的并发分配/撤回在此串行，杜绝超分 */
    public Optional<BagBatch> findByIdForUpdate(Long id) {
        return jdbc.query("SELECT * FROM bag_batch WHERE id=:id FOR UPDATE", Map.of("id", id), mapper).stream().findFirst();
    }

    public Long save(BagBatch batch) {
        return insert.executeAndReturnKey(new BeanPropertySqlParameterSource(batch)).longValue();
    }

    public void updateStatus(Long id, String status) {
        jdbc.update("UPDATE bag_batch SET status=:status WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status));
    }

    public int decreaseAvailable(Long id, int quantity) {
        return jdbc.update("""
                UPDATE bag_batch SET available_quantity=available_quantity-:quantity
                WHERE id=:id AND available_quantity>=:quantity
                """, new MapSqlParameterSource().addValue("id", id).addValue("quantity", quantity));
    }

    /** 撤回在制入房分配时归还数量 */
    public void increaseAvailable(Long id, int quantity) {
        jdbc.update("UPDATE bag_batch SET available_quantity=available_quantity+:quantity WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("quantity", quantity));
    }

    public List<Map<String, Object>> statusSummary() {
        return jdbc.queryForList("SELECT status, SUM(total_quantity) quantity FROM bag_batch GROUP BY status ORDER BY status", Map.of());
    }

    /** 各菌包批次在培养架上的占用量：与分批入房共用 available_quantity，对账与去向都要算进去 */
    public List<Map<String, Object>> shelvedQuantities() {
        return jdbc.queryForList(
                "SELECT bag_batch_id, SUM(quantity) AS quantity FROM rack_slot GROUP BY bag_batch_id",
                Map.of());
    }
}
