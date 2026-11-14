package com.orion.controller.storage;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Table "ip_group": name -> list of IPs/subnets. */
public final class GroupRepository {

    private final Database db;

    public GroupRepository(Database db) {
        this.db = db;
    }

    public Map<String, List<String>> findAll() {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, members FROM ip_group ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                Map<String, List<String>> out = new LinkedHashMap<>();
                while (rs.next()) {
                    Array arr = rs.getArray("members");
                    out.put(rs.getString("name"), Arrays.asList((String[]) arr.getArray()));
                }
                return out;
            }
        });
    }

    public void save(String name, List<String> members) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ip_group (name, members) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET members = EXCLUDED.members")) {
                ps.setString(1, name);
                ps.setArray(2, c.createArrayOf("text", members.toArray()));
                return ps.executeUpdate();
            }
        });
    }

    public boolean delete(String name) {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM ip_group WHERE name = ?")) {
                ps.setString(1, name);
                return ps.executeUpdate() > 0;
            }
        });
    }
}
