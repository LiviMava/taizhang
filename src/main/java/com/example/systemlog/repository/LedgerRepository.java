package com.example.systemlog.repository;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final RowMapper<Map<String,Object>> ROW = (rs, n) -> {
        Map<String,Object> row = new LinkedHashMap<>();
        for (int i=1; i<=rs.getMetaData().getColumnCount(); i++) {
            Object value = rs.getObject(i);
            if (value instanceof Timestamp timestamp)
                value = timestamp.toInstant().atOffset(ZoneOffset.ofHours(8));
            row.put(rs.getMetaData().getColumnLabel(i), value);
        }
        return row;
    };
    public LedgerRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> query(String sql, Map<String,?> params) {
        return jdbc.query(sql, params, ROW);
    }
    public Map<String,Object> find(String sql, Map<String,?> params) {
        List<Map<String,Object>> rows = query(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public long count(String sql, Map<String,?> params) {
        return jdbc.queryForObject(sql, params, Long.class);
    }
    public int execute(String sql, Map<String,?> params) { return jdbc.update(sql, params); }
    public Map<String,Object> insert(String table, Map<String,Object> values, String actor) {
        Map<String,Object> params = new LinkedHashMap<>(values);
        params.put("created_by", actor); params.put("updated_by", actor);
        return find("INSERT INTO " + table + " (" + String.join(",", params.keySet()) + ") VALUES ("
            + String.join(",", params.keySet().stream().map(key -> ":"+key).toList()) + ") RETURNING *", params);
    }
    public Map<String,Object> update(String table, long id, Map<String,Object> values, String actor) {
        Map<String,Object> params = new LinkedHashMap<>(values);
        params.put("updated_by", actor);
        String assignments = String.join(",", params.keySet().stream().map(key -> key+"=:"+key).toList());
        params.put("row_id", id);
        return find("UPDATE " + table + " SET " + assignments
            + ",updated_at=clock_timestamp() WHERE id=:row_id RETURNING *", params);
    }
}
