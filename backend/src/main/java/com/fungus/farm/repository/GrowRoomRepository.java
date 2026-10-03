package com.fungus.farm.repository;

import com.fungus.farm.domain.GrowRoom;
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
public class GrowRoomRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private final BeanPropertyRowMapper<GrowRoom> mapper = BeanPropertyRowMapper.newInstance(GrowRoom.class);

    public GrowRoomRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(dataSource).withTableName("grow_room").usingGeneratedKeyColumns("id");
    }

    public List<GrowRoom> findAll() {
        return jdbc.query("SELECT * FROM grow_room ORDER BY room_code", Map.of(), mapper);
    }

    public Optional<GrowRoom> findById(Long id) {
        return jdbc.query("SELECT * FROM grow_room WHERE id=:id", Map.of("id", id), mapper).stream().findFirst();
    }

    public List<Map<String, Object>> findOverview() {
        String sql = """
                SELECT gr.id, gr.room_code, gr.orientation, gr.area, gr.species, gr.status,
                       COUNT(DISTINCT r.id) AS rack_count,
                       COUNT(DISTINCT fb.id) AS fruiting_batch_count,
                       CASE WHEN er.over_limit = 1 THEN 1 ELSE 0 END AS alert_count,
                       (SELECT t.id FROM environment_ticket t
                        WHERE t.room_id = gr.id AND t.status <> '已解除' LIMIT 1) AS ticket_id,
                       (SELECT t.status FROM environment_ticket t
                        WHERE t.room_id = gr.id AND t.status <> '已解除' LIMIT 1) AS ticket_status
                FROM grow_room gr
                LEFT JOIN rack r ON r.room_id = gr.id
                LEFT JOIN fruiting_batch fb ON fb.room_id = gr.id AND fb.status <> '已结束'
                LEFT JOIN environment_record er ON er.id = (
                    SELECT e2.id FROM environment_record e2
                    WHERE e2.room_id = gr.id ORDER BY e2.recorded_at DESC LIMIT 1
                )
                GROUP BY gr.id, gr.room_code, gr.orientation, gr.area, gr.species, gr.status, er.over_limit
                ORDER BY gr.room_code
                """;
        return jdbc.queryForList(sql, Map.of());
    }

    public Long save(GrowRoom room) {
        Number key = insert.executeAndReturnKey(new BeanPropertySqlParameterSource(room));
        return key.longValue();
    }

    public void updateStatus(Long id, String status) {
        jdbc.update("UPDATE grow_room SET status=:status WHERE id=:id",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status));
    }

    public int countRooms() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM grow_room", Map.of(), Integer.class);
    }

    public int countFruitingBatches() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM fruiting_batch WHERE status <> '已结束'", Map.of(), Integer.class);
    }
}
