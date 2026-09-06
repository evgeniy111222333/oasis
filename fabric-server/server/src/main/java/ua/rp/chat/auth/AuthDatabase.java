package ua.rp.chat.auth;

import java.io.File;
import java.sql.*;
import java.util.Locale;
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
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS characters (
                    character_key TEXT PRIMARY KEY,
                    owner_login_name TEXT,
                    current_uuid TEXT,
                    rp_name TEXT UNIQUE NOT NULL,
                    appearance_model TEXT,
                    appearance_hash TEXT,
                    appearance_url TEXT,
                    appearance_storage_key TEXT,
                    appearance_updated_at INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
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
            try {
                stmt.execute("CREATE TABLE IF NOT EXISTS characters ("
                        + "character_key TEXT PRIMARY KEY,"
                        + "owner_login_name TEXT,"
                        + "current_uuid TEXT,"
                        + "rp_name TEXT UNIQUE NOT NULL,"
                        + "appearance_model TEXT,"
                        + "appearance_hash TEXT,"
                        + "appearance_url TEXT,"
                        + "appearance_storage_key TEXT,"
                        + "appearance_updated_at INTEGER,"
                        + "created_at INTEGER NOT NULL,"
                        + "updated_at INTEGER NOT NULL)");
            } catch (SQLException ignored) {}
            try {
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_characters_rp_name ON characters(rp_name COLLATE NOCASE)");
            } catch (SQLException ignored) {}
            migratePlayerCharacters();
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to upgrade database: " + e.getMessage());
        }
    }

    private void migratePlayerCharacters() {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, login_name, rp_name, appearance_model, appearance_hash, appearance_url, appearance_storage_key, appearance_updated_at, registered_at "
                        + "FROM players WHERE rp_name IS NOT NULL AND TRIM(rp_name) <> ''");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String rpName = rs.getString("rp_name");
                String characterKey = characterKey(rpName);
                if (characterKey.isBlank()) {
                    continue;
                }
                upsertCharacter(
                        characterKey,
                        rs.getString("login_name"),
                        rs.getString("uuid"),
                        rpName,
                        rs.getString("appearance_model"),
                        rs.getString("appearance_hash"),
                        rs.getString("appearance_url"),
                        rs.getString("appearance_storage_key"),
                        rs.getLong("appearance_updated_at"),
                        Math.max(rs.getLong("registered_at"), 1L)
                );
            }
        } catch (SQLException e) {
            logger.warning("AuthDB: Failed to migrate player characters: " + e.getMessage());
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

    public PlayerAccount getAccountByLoginName(String loginName) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid, login_name, rp_name, password_hash FROM players WHERE login_name = ? COLLATE NOCASE")) {
            ps.setString(1, loginName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return new PlayerAccount(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("login_name"),
                        rs.getString("rp_name"),
                        rs.getString("password_hash")
                );
            }
        } catch (SQLException | IllegalArgumentException e) {
            logger.severe("AuthDB: Failed to get account by login " + loginName + ": " + e.getMessage());
        }
        return null;
    }

    public boolean rebindAccountUuid(String loginName, UUID newUuid) {
        PlayerAccount account = getAccountByLoginName(loginName);
        if (account == null) {
            return false;
        }
        if (account.uuid().equals(newUuid)) {
            return true;
        }
        if (isRegistered(newUuid)) {
            logger.warning("AuthDB: Refusing to rebind " + loginName + " to already registered uuid " + newUuid);
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET uuid = ? WHERE login_name = ? COLLATE NOCASE")) {
            ps.setString(1, newUuid.toString());
            ps.setString(2, loginName);
            boolean updated = ps.executeUpdate() > 0;
            if (updated) {
                updateCharacterOwner(account.rpName(), account.loginName(), newUuid);
            }
            return updated;
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to rebind account uuid for " + loginName + ": " + e.getMessage());
            return false;
        }
    }

    public AppearanceProfile getAppearanceProfile(UUID uuid) {
        String rpName = getRpName(uuid);
        AppearanceProfile characterProfile = getAppearanceProfileByRpName(rpName);
        if (characterProfile != null) {
            return characterProfile;
        }
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

    public AppearanceProfile getAppearanceProfileByRpName(String rpName) {
        String key = characterKey(rpName);
        if (key.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT appearance_model, appearance_hash, appearance_url, appearance_storage_key, appearance_updated_at FROM characters WHERE character_key = ?")) {
            ps.setString(1, key);
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
            logger.severe("AuthDB: Failed to get appearance profile for character " + rpName + ": " + e.getMessage());
        }
        return null;
    }

    public boolean updateAppearance(UUID uuid, String model, String hash) {
        return updateAppearance(uuid, model, hash, null, null);
    }

    public boolean updateAppearance(UUID uuid, String model, String hash, String url, String storageKey) {
        String rpName = getRpName(uuid);
        boolean characterUpdated = updateCharacterAppearance(rpName, model, hash, url, storageKey);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE players SET appearance_model = ?, appearance_hash = ?, appearance_url = ?, appearance_storage_key = ?, appearance_updated_at = ? WHERE uuid = ?")) {
            ps.setString(1, model);
            ps.setString(2, hash);
            ps.setString(3, blankToNull(url));
            ps.setString(4, blankToNull(storageKey));
            ps.setLong(5, System.currentTimeMillis());
            ps.setString(6, uuid.toString());
            return ps.executeUpdate() > 0 || characterUpdated;
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to update appearance for " + uuid + ": " + e.getMessage());
            return characterUpdated;
        }
    }

    public boolean updateCharacterAppearance(String rpName, String model, String hash, String url, String storageKey) {
        String key = characterKey(rpName);
        if (key.isBlank()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE characters SET appearance_model = ?, appearance_hash = ?, appearance_url = ?, appearance_storage_key = ?, appearance_updated_at = ?, updated_at = ? WHERE character_key = ?")) {
            long now = System.currentTimeMillis();
            ps.setString(1, model);
            ps.setString(2, hash);
            ps.setString(3, blankToNull(url));
            ps.setString(4, blankToNull(storageKey));
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setString(7, key);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to update character appearance for " + rpName + ": " + e.getMessage());
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

    public boolean isRpNameTaken(String rpName) {
        String key = characterKey(rpName);
        if (key.isBlank()) {
            return false;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM characters WHERE character_key = ? UNION SELECT 1 FROM players WHERE rp_name = ? COLLATE NOCASE")) {
            ps.setString(1, key);
            ps.setString(2, rpName);
            return ps.executeQuery().next();
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to check RP name usage: " + e.getMessage());
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
            long now = System.currentTimeMillis();
            ps.setLong(6, now);
            ps.executeUpdate();
            createCharacterForAccount(loginName, uuid, rpName, now);
            return true;
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to register " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    private boolean createCharacterForAccount(String loginName, UUID uuid, String rpName, long createdAt) {
        return upsertCharacter(characterKey(rpName), loginName, uuid.toString(), rpName,
                null, null, null, null, 0L, createdAt);
    }

    public boolean deleteAccount(UUID uuid) {
        String rpName = getRpName(uuid);
        String key = characterKey(rpName);
        try {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement deleteCharacter = connection.prepareStatement(
                    "DELETE FROM characters WHERE character_key = ? AND current_uuid = ?");
                 PreparedStatement deletePlayer = connection.prepareStatement(
                         "DELETE FROM players WHERE uuid = ?")) {
                deleteCharacter.setString(1, key);
                deleteCharacter.setString(2, uuid.toString());
                deleteCharacter.executeUpdate();

                deletePlayer.setString(1, uuid.toString());
                int deletedPlayers = deletePlayer.executeUpdate();

                connection.commit();
                connection.setAutoCommit(previousAutoCommit);
                return deletedPlayers > 0;
            } catch (SQLException e) {
                connection.rollback();
                connection.setAutoCommit(previousAutoCommit);
                throw e;
            }
        } catch (SQLException e) {
            logger.severe("AuthDB: Failed to delete account " + uuid + ": " + e.getMessage());
            return false;
        }
    }

    private void updateCharacterOwner(String rpName, String loginName, UUID uuid) {
        String key = characterKey(rpName);
        if (key.isBlank()) {
            return;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE characters SET owner_login_name = ?, current_uuid = ?, updated_at = ? WHERE character_key = ?")) {
            ps.setString(1, loginName);
            ps.setString(2, uuid.toString());
            ps.setLong(3, System.currentTimeMillis());
            ps.setString(4, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warning("AuthDB: Failed to update character owner for " + rpName + ": " + e.getMessage());
        }
    }

    private boolean upsertCharacter(
            String characterKey,
            String loginName,
            String uuid,
            String rpName,
            String model,
            String hash,
            String url,
            String storageKey,
            long appearanceUpdatedAt,
            long createdAt
    ) {
        if (characterKey == null || characterKey.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long safeCreatedAt = createdAt > 0 ? createdAt : now;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO characters (
                    character_key, owner_login_name, current_uuid, rp_name,
                    appearance_model, appearance_hash, appearance_url, appearance_storage_key,
                    appearance_updated_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(character_key) DO UPDATE SET
                    owner_login_name = COALESCE(excluded.owner_login_name, characters.owner_login_name),
                    current_uuid = COALESCE(excluded.current_uuid, characters.current_uuid),
                    rp_name = excluded.rp_name,
                    appearance_model = COALESCE(excluded.appearance_model, characters.appearance_model),
                    appearance_hash = COALESCE(excluded.appearance_hash, characters.appearance_hash),
                    appearance_url = COALESCE(excluded.appearance_url, characters.appearance_url),
                    appearance_storage_key = COALESCE(excluded.appearance_storage_key, characters.appearance_storage_key),
                    appearance_updated_at = CASE
                        WHEN excluded.appearance_updated_at > 0 THEN excluded.appearance_updated_at
                        ELSE characters.appearance_updated_at
                    END,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, characterKey);
            ps.setString(2, blankToNull(loginName));
            ps.setString(3, blankToNull(uuid));
            ps.setString(4, rpName);
            ps.setString(5, blankToNull(model));
            ps.setString(6, blankToNull(hash));
            ps.setString(7, blankToNull(url));
            ps.setString(8, blankToNull(storageKey));
            ps.setLong(9, appearanceUpdatedAt);
            ps.setLong(10, safeCreatedAt);
            ps.setLong(11, now);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            logger.warning("AuthDB: Failed to upsert character " + rpName + ": " + e.getMessage());
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

    public static String characterKey(String rpName) {
        if (rpName == null) {
            return "";
        }
        return rpName.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public record PlayerAccount(UUID uuid, String loginName, String rpName, String passwordHash) {}

    public record AppearanceProfile(String model, String hash, String url, String storageKey, long updatedAt) {}
}
