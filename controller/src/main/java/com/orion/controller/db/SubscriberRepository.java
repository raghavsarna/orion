package com.orion.controller.db;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Table "nib_subscriber": who the NIB notifies (name -> address). */
public final class SubscriberRepository {

    private final Database db;

    public SubscriberRepository(Database db) {
        this.db = db;
    }

    public Map<String, String> findAll() {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, address FROM nib_subscriber ORDER BY name");
                 ResultSet rs = ps.executeQuery()) {
                Map<String, String> out = new LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString("name"), rs.getString("address"));
                }
                return out;
            }
        });
    }

    public void save(String name, String address) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO nib_subscriber (name, address) VALUES (?, ?) ON CONFLICT (name) DO UPDATE SET address = EXCLUDED.address")) {
                ps.setString(1, name);
                ps.setString(2, address);
                return ps.executeUpdate();
            }
        });
    }

    public void saveIfMissing(String name, String address) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO nib_subscriber (name, address) VALUES (?, ?) ON CONFLICT (name) DO NOTHING")) {
                ps.setString(1, name);
                ps.setString(2, address);
                return ps.executeUpdate();
            }
        });
    }

    public boolean delete(String name) {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM nib_subscriber WHERE name = ?")) {
                ps.setString(1, name);
                return ps.executeUpdate() > 0;
            }
        });
    }
}
