package com.orion.controller.storage;

import com.orion.controller.ControllerException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** PostgreSQL access with plain JDBC and a HikariCP pool. Creates the tables on first connect. */
public final class Database {

    private static final Logger log = LoggerFactory.getLogger(Database.class);

    /** One JDBC unit of work. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final HikariDataSource pool;
    private volatile boolean ready;

    public Database(String url, String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(5);
        cfg.setConnectionTimeout(3000);
        cfg.setInitializationFailTimeout(-1);   // don't fail startup if the DB is not up yet
        cfg.setPoolName("orion-db");
        this.pool = new HikariDataSource(cfg);
    }

    /** Blocks until the database answers and the schema exists (retrying every 2 s). */
    public void waitUntilReady() {
        while (!ready) {
            try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
                st.execute(schemaSql());
                ready = true;
                log.info("Database ready");
            } catch (SQLException e) {
                log.warn("Database not reachable yet ({}), retrying in 2 s", e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    public boolean isReady() {
        return ready;
    }

    public <T> T run(Work<T> work) {
        try (Connection c = pool.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw ControllerException.unavailable("database unavailable: " + e.getMessage(), e);
        }
    }

    public void close() {
        pool.close();
    }

    private static String schemaSql() {
        try (InputStream in = Database.class.getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql missing from the jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
