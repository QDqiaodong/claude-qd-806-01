package com.fungus.farm.repository;

import com.fungus.farm.domain.ContaminationRecord;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class ContaminationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public ContaminationRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(dataSource).withTableName("contamination_record").usingGeneratedKeyColumns("id");
    }

    public List<Map<String, Object>> findAll() {
        String sql = """
                SELECT cr.id, cr.bag_batch_id, bb.batch_no, cr.contamination_type,
                       cr.discovered_date, cr.treatment, cr.loss_quantity, cr.note,
                       bb.status AS bag_status, cr.created_at
                FROM contamination_record cr
                JOIN bag_batch bb ON bb.id=cr.bag_batch_id
                ORDER BY cr.discovered_date DESC, cr.id DESC
                """;
        return jdbc.queryForList(sql, Map.of());
    }

    public Long save(ContaminationRecord record) {
        return insert.executeAndReturnKey(new BeanPropertySqlParameterSource(record)).longValue();
    }

    public boolean hasTreatment(Long bagBatchId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM contamination_record
                WHERE bag_batch_id=:bagBatchId AND treatment IS NOT NULL AND treatment<>''
                """, Map.of("bagBatchId", bagBatchId), Integer.class);
        return count != null && count > 0;
    }

    public List<Map<String, Object>> summary() {
        String sql = """
                SELECT contamination_type,
                       COUNT(*) AS record_count,
                       SUM(loss_quantity) AS total_loss,
                       ROUND(AVG(loss_quantity),1) AS average_loss,
                       SUM(CASE WHEN treatment='剔除' THEN loss_quantity ELSE 0 END) AS removed_loss,
                       SUM(CASE WHEN treatment='隔离' THEN loss_quantity ELSE 0 END) AS isolated_loss,
                       SUM(CASE WHEN treatment='药剂' THEN loss_quantity ELSE 0 END) AS treated_loss
                FROM contamination_record
                GROUP BY contamination_type
                ORDER BY total_loss DESC
                """;
        return jdbc.queryForList(sql, new MapSqlParameterSource());
    }
}
