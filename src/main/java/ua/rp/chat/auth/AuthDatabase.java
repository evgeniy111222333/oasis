package ua.rp.chat.auth;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite database for player authentication.
 * Stores: uuid, password_hash, last_ip, last_login, registered_at
 */
public class AuthDatabase {
    private Connection connection;
    private final File dbFile;
    private final Logger logger;

    public AuthDatabase(File dataFolder, Logger logger) {
        this.logger = logger;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        this.dbFile = new File(dataFolder, "auth.db");
    }

    public void connect() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

        // Enable WAL mode for better concurrency
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL");
        }

        createTables();
        upgradeDatabase();
        logger.info("AuthDatabase connected: " + dbFile.getAbsolutePath());
    }

    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid TEXT PRIMARY KEY,
                    login_name TEXT UNIQUE,
                    rp_name TEXT,
                    email TEXT,
                    password_hash TEXT NOT NULL,
                    last_ip TEXT,
                    last_login INTEGER,
                    appearance_model TEXT,
                    appearance_hash TEXT,
                    appearance_url TEXT,
                    appearance_storage_key TEXT,
                    appearance_updated_at INTEGER,
                    registered_at INTEGER NOT NULL
                )
            """);
        }
    }

    private void upgradeDatabase() {
        try (Statement stmt = connection.createStatement()) {
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN login_name TEXT");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_players_login ON players(login_name)");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN rp_name TEXT");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN email TEXT");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN appearance_model TEXT");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN appearance_hash TEXT");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN appearance_url TEXT");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN appearance_storage_key TEXT");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("ALTER TABLE players ADD COLUMN appearance_updated_at INTEGER");
            } catch (SQLException ignored) {}
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to upgrade database: " + e.getMessage());
        }
    }

    /**
     * Checks if a player has a registered account.
     */
    public boolean isRegistered(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeQuery().next();
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to check registration for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the stored login name for a player.
     */
    public String getLoginName(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT login_name FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("login_name");
            }
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to get login name for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Gets the stored RP name for a player.
     */
    public String getRpName(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT rp_name FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("rp_name");
            }
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to get RP name for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    public AppearanceProfile getAppearanceProfile(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT appearance_model, appearance_hash, appearance_url, appearance_storage_key, appearance_updated_at FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String model = rs.getString("appearance_model");
                String hash = rs.getString("appearance_hash");
                String url = rs.getString("appearance_url");
                String storageKey = rs.getString("appearance_storage_key");
                long updatedAt = rs.getLong("appearance_updated_at");
                if (model == null || hash == null || updatedAt <= 0) {
                    return null;
                }
                return new AppearanceProfile(model, hash, url, storageKey, updatedAt);
            }
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to get appearance profile for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    public boolean updateAppearance(UUID uuid, String model, String hash) {
        return updateAppearance(uuid, model, hash, null, null);
    }

    public boolean updateAppearance(UUID uuid, String model, String hash, String url, String storageKey) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET appearance_model = ?, appearance_hash = ?, appearance_url = ?, appearance_storage_key = ?, appearance_updated_at = ? WHERE uuid = ?")) {
            ps.setString(1, model);
            ps.setString(2, hash);
            ps.setString(3, blankToNull(url));
            ps.setString(4, blankToNull(storageKey));
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to update appearance for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks if a login name is already registered by another user.
     */
    public boolean isLoginNameTaken(String loginName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM players WHERE login_name = ? COLLATE NOCASE")) {
            ps.setString(1, loginName);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to check login name usage: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the stored password hash for a player.
     */
    public String getPasswordHash(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT password_hash FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("password_hash");
            }
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to get password hash for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Registers a new player with the given details.
     */
    public boolean register(UUID uuid, String loginName, String rpName, String email, String passwordHash) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO players (uuid, login_name, rp_name, email, password_hash, registered_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, loginName);
            ps.setString(3, rpName);
            ps.setString(4, email);
            ps.setString(5, passwordHash);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to register " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates the last login IP and timestamp.
     */
    public void updateLogin(UUID uuid, String ip) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET last_ip = ?, last_login = ? WHERE uuid = ?")) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to update login for " + uuid + ": " + e.getMessage());
        }
    }

    /**
     * Gets the last login IP for session-based auto-login.
     */
    public String getLastIp(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT last_ip FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getString("last_ip");
            }
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to get last IP for " + uuid + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Changes a player's password.
     */
    public boolean changePassword(UUID uuid, String newPasswordHash) {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET password_hash = ? WHERE uuid = ?")) {
            ps.setString(1, newPasswordHash);
            ps.setString(2, uuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to change password for " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    public void disconnect() {
        if (connection != null) {
            try {
                connection.close();
                logger.info("AuthDatabase disconnected.");
            } catch (SQLException e) {
                logger.warning("AuthDB: Error closing connection: " + e.getMessage());
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record AppearanceProfile(String model, String hash, String url, String storageKey, long updatedAt) {}
}
