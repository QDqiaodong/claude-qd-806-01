package com.fungus.farm.repository;

import com.fungus.farm.domain.Rack;
import com.fungus.farm.domain.RackSlot;
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
public class RackRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert rackInsert;
    private final SimpleJdbcInsert slotInsert;
    private final BeanPropertyRowMapper<Rack> rackMapper = BeanPropertyRowMapper.newInstance(Rack.class);

    public RackRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.rackInsert = new SimpleJdbcInsert(dataSource).withTableName("rack").usingGeneratedKeyColumns("id");
        this.slotInsert = new SimpleJdbcInsert(dataSource).withTableName("rack_slot").usingGeneratedKeyColumns("id");
    }

    public List<Rack> findByRoomId(Long roomId) {
        return jdbc.query("SELECT * FROM rack WHERE room_id=:roomId ORDER BY rack_code", Map.of("roomId", roomId), rackMapper);
    }

    public Optional<Rack> findById(Long id) {
        return jdbc.query("SELECT * FROM rack WHERE id=:id", Map.of("id", id), rackMapper).stream().findFirst();
    }

    public List<Map<String, Object>> findSlots(Long rackId) {
        String sql = """
                SELECT rs.id, rs.rack_id, rs.level_no, rs.slot_no, rs.quantity, rs.shelved_at,
                       b.id AS bag_batch_id, b.batch_no, b.status AS bag_status
                FROM rack_slot rs
                JOIN bag_batch b ON b.id=rs.bag_batch_id
                WHERE rs.rack_id=:rackId
                ORDER BY rs.level_no DESC, rs.slot_no
                """;
        return jdbc.queryForList(sql, Map.of("rackId", rackId));
    }

    public boolean isOccupied(Long rackId, Integer levelNo, Integer slotNo) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rack_slot WHERE rack_id=:rackId AND level_no=:levelNo AND slot_no=:slotNo
                """, new MapSqlParameterSource().addValue("rackId", rackId).addValue("levelNo", levelNo).addValue("slotNo", slotNo), Integer.class);
        return count != null && count > 0;
    }

    public Long save(Rack rack) {
        return rackInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(rack)).longValue();
    }

    public Long saveSlot(RackSlot slot) {
        return slotInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(slot)).longValue();
    }
}
