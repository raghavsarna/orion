package com.orion.controller.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orion.controller.intent.Intent;
import com.orion.controller.intent.IntentSpec;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Table "intent". */
public final class IntentRepository {

    private final Database db;
    private final ObjectMapper json = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public IntentRepository(Database db) {
        this.db = db;
    }

    public Intent insert(Intent i) {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO intent (id, type, body, status, reason, created_at) VALUES (?, ?, ?::jsonb, ?, ?, ?)")) {
                ps.setObject(1, i.id());
                ps.setString(2, i.spec().type());
                ps.setString(3, toJson(i.spec()));
                ps.setString(4, i.status());
                ps.setString(5, i.reason());
                ps.setTimestamp(6, Timestamp.from(i.createdAt()));
                ps.executeUpdate();
                return i;
            }
        });
    }

    public List<Intent> findAll() {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, body, status, reason, created_at FROM intent ORDER BY created_at");
                 ResultSet rs = ps.executeQuery()) {
                List<Intent> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Intent((UUID) rs.getObject("id"), fromJson(rs.getString("body")), rs.getString("status"),
                            rs.getString("reason"), rs.getTimestamp("created_at").toInstant()));
                }
                return out;
            }
        });
    }

    public void updateStatus(UUID id, String status, String reason) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE intent SET status = ?, reason = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setString(2, reason);
                ps.setObject(3, id);
                return ps.executeUpdate();
            }
        });
    }

    public boolean delete(UUID id) {
        return db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM intent WHERE id = ?")) {
                ps.setObject(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    private String toJson(IntentSpec spec) {
        try {
            return json.writeValueAsString(spec);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private IntentSpec fromJson(String text) {
        try {
            return json.readValue(text, IntentSpec.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad intent body in database: " + e.getMessage(), e);
        }
    }
}
