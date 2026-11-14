package com.orion.controller.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Table "manual_switch": switches added through the API (id -> address). */
public final class ManualSwitchRepository {

    private final Database db;

    public ManualSwitchRepository(Database db) {
        this.db = db;
    }

    public Map<String, String> findAll() {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT switch_id, address FROM manual_switch");
                 ResultSet rs = ps.executeQuery()) {
                Map<String, String> out = new LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString("switch_id"), rs.getString("address"));
                }
                return out;
            }
        });
    }

    public void save(String switchId, String address) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO manual_switch (switch_id, address) VALUES (?, ?) ON CONFLICT (switch_id) DO UPDATE SET address = EXCLUDED.address")) {
                ps.setString(1, switchId);
                ps.setString(2, address);
                return ps.executeUpdate();
            }
        });
    }

    public void delete(String switchId) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM manual_switch WHERE switch_id = ?")) {
                ps.setString(1, switchId);
                return ps.executeUpdate();
            }
        });
    }
}
