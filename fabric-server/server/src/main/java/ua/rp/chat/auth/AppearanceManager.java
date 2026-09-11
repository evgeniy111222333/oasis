package ua.rp.chat.auth;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

public class AppearanceManager {
    public static final int MAX_APPEARANCE_BYTES = 512 * 1024;

    private final File appearanceFolder;
    private final AuthDatabase database;
    private final Logger logger;
    private final R2AppearanceStorage storage;

    public AppearanceManager(File dataFolder, AuthDatabase database, Logger logger) {
        this(dataFolder, database, logger, null);
    }

    public AppearanceManager(File dataFolder, AuthDatabase database, Logger logger, R2AppearanceStorage storage) {
        this.appearanceFolder = new File(dataFolder, "appearances");
        this.database = database;
        this.logger = logger;
        this.storage = storage == null ? R2AppearanceStorage.fromConfig(new ua.rp.chat.SimpleConfig(new File(dataFolder, "config.yml")), logger) : storage;
        if (!appearanceFolder.exists()) {
            appearanceFolder.mkdirs();
        }
    }

    public SaveResult saveAppearance(UUID uuid, String model, String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            return SaveResult.empty();
        }

        String normalizedModel = normalizeModel(model);
        SaveResult validation = validateAppearance(dataUrl);
        if (!validation.success()) {
            return validation;
        }

        byte[] bytes;
        try {
            bytes = decodeDataUrl(dataUrl);
        } catch (IllegalArgumentException e) {
            return SaveResult.error("Файл образа поврежден или имеет неверный формат.");
        }

        if (bytes.length > MAX_APPEARANCE_BYTES) {
            return SaveResult.error("Файл образа слишком большой. Максимум 512 KB.");
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return SaveResult.error("Облик должен быть PNG-файлом.");
            }
            if (!isValidAppearanceSize(image.getWidth(), image.getHeight())) {
                return SaveResult.error("Размер образа должен быть 64x64 или 64x32.");
            }

            byte[] storedBytes = normalizeAppearanceBytes(image, bytes);
            String hash = sha1(storedBytes);
            File target = getAppearanceFile(uuid);
            Files.write(target.toPath(), storedBytes);

            String publicUrl = null;
            String storageKey = null;
            if (storage.isEnabled()) {
                try {
                    String characterKey = AuthDatabase.characterKey(database.getRpName(uuid));
                    R2AppearanceStorage.UploadResult upload = characterKey.isBlank()
                            ? storage.uploadSkin(uuid, hash, storedBytes)
                            : storage.uploadCharacterSkin(characterKey, hash, storedBytes);
                    publicUrl = upload.publicUrl();
                    storageKey = upload.key();
                } catch (IOException | InterruptedException e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    logger.warning("R2 appearance upload failed for " + uuid + ": " + e.getMessage());
                    if (storage.isRequired()) {
                        return SaveResult.error("Не удалось загрузить образ в облачное хранилище.");
                    }
                }
            }

            if (!database.updateAppearance(uuid, normalizedModel, hash, publicUrl, storageKey)) {
                return SaveResult.error("Не удалось привязать облик к персонажу.");
            }
            return SaveResult.saved(normalizedModel, hash, publicUrl);
        } catch (IOException e) {
            logger.warning("Failed to save appearance for " + uuid + ": " + e.getMessage());
            return SaveResult.error("Не удалось сохранить облик персонажа.");
        }
    }

    public SaveResult validateAppearance(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            return SaveResult.empty();
        }

        byte[] bytes;
        try {
            bytes = decodeDataUrl(dataUrl);
        } catch (IllegalArgumentException e) {
            return SaveResult.error("Файл образа поврежден или имеет неверный формат.");
        }

        if (bytes.length > MAX_APPEARANCE_BYTES) {
            return SaveResult.error("Файл образа слишком большой. Максимум 512 KB.");
        }

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return SaveResult.error("Облик должен быть PNG-файлом.");
            }
            if (!isValidAppearanceSize(image.getWidth(), image.getHeight())) {
                return SaveResult.error("Размер образа должен быть 64x64 или 64x32.");
            }
            return SaveResult.empty();
        } catch (IOException e) {
            return SaveResult.error("Не удалось прочитать облик персонажа.");
        }
    }

    public File getAppearanceFile(UUID uuid) {
        return new File(appearanceFolder, uuid + ".png");
    }

    public boolean hasAppearance(UUID uuid) {
        AuthDatabase.AppearanceProfile profile = database.getAppearanceProfile(uuid);
        return profile != null && (!isBlank(profile.url()) || getAppearanceFile(uuid).isFile());
    }

    public String textureUrl(UUID uuid, AuthDatabase.AppearanceProfile profile) {
        if (profile != null && !isBlank(profile.url())) {
            return profile.url();
        }
        if (profile == null || isBlank(profile.hash())) {
            return "";
        }
        return "/api/appearance/texture/" + uuid + ".png?v=" + profile.hash();
    }

    public static String normalizeModel(String model) {
        return "slim".equalsIgnoreCase(model) ? "slim" : "classic";
    }

    private static boolean isValidAppearanceSize(int width, int height) {
        return width == 64 && (height == 64 || height == 32);
    }

    private static byte[] normalizeAppearanceBytes(BufferedImage image, byte[] originalBytes) throws IOException {
        if (image.getWidth() == 64 && image.getHeight() == 64 && !isBrokenLegacySkin(image)) {
            return originalBytes;
        }
        return encodePng(toModernSkin(image));
    }

    /**
     * Converts a legacy 64x32 skin (or a 64x64 file whose bottom half was left blank by the old
     * converter) to the modern layout exactly like Minecraft itself: the absent left arm and left
     * leg are horizontal mirror copies of the right limbs. A plain paste leaves those regions
     * transparent, so the left arm and leg render blank or with the wrong colours. A modern 64x64
     * skin is returned untouched. Pure and unit-testable.
     */
    public static BufferedImage toModernSkin(BufferedImage image) {
        if (image.getWidth() != 64) {
            return image;
        }
        if (image.getHeight() == 64 && !isBrokenLegacySkin(image)) {
            return image;
        }
        BufferedImage modern = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        // Both a 64x32 legacy skin and a mis-converted 64x64 file hold the real data in rows 0..31.
        int sourceRows = Math.min(32, image.getHeight());
        for (int y = 0; y < sourceRows; y++) {
            for (int x = 0; x < 64; x++) {
                modern.setRGB(x, y, image.getRGB(x, y));
            }
        }
        // Right arm  (40,16,16x16) -> left arm  (32,48,16x16), mirrored across X.
        mirrorRegion(modern, image, 40, 16, 32, 48, 16, 16);
        // Right leg  ( 0,16,16x16) -> left leg  (16,48,16x16), mirrored across X.
        mirrorRegion(modern, image, 0, 16, 16, 48, 16, 16);
        return modern;
    }

    /**
     * True when a 64x64 file looks like a legacy skin that the old converter pasted without
     * mirroring: the whole bottom half is transparent while the top half still carries the right
     * leg. A genuine modern skin keeps its left limbs in the bottom half, so it never matches.
     */
    public static boolean isBrokenLegacySkin(BufferedImage image) {
        if (image.getWidth() != 64 || image.getHeight() != 64) {
            return false;
        }
        for (int y = 32; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return false;
                }
            }
        }
        for (int y = 16; y < 32; y++) {
            for (int x = 0; x < 16; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * One-time repair of already-stored skins that the old converter left with blank left arms
     * and legs. For every appearance PNG matching {@link #isBrokenLegacySkin}, the original is
     * backed up, the file is rewritten with the mirrored limbs, its SHA-1 recomputed, and the
     * profile hash (plus the R2 object) updated so clients actually fetch the repaired skin.
     * Idempotent and guarded by a marker file. Returns the number of repaired skins.
     */
    public synchronized int migrateLegacyAppearances() {
        File marker = new File(appearanceFolder, ".legacy-mirror-migration-v1");
        if (marker.isFile()) {
            return 0;
        }
        File[] files = appearanceFolder.listFiles(
                (dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null) {
            touch(marker);
            return 0;
        }
        File backupDir = new File(appearanceFolder, "legacy-backup");
        int migrated = 0;
        for (File file : files) {
            String name = file.getName();
            UUID uuid;
            try {
                uuid = UUID.fromString(name.substring(0, name.length() - 4));
            } catch (IllegalArgumentException notASkin) {
                continue;
            }
            try {
                BufferedImage image = ImageIO.read(file);
                if (image == null || !isBrokenLegacySkin(image)) {
                    continue;
                }
                byte[] fixedBytes = encodePng(toModernSkin(image));
                String hash = sha1(fixedBytes);
                if (!backupDir.exists() && !backupDir.mkdirs()) {
                    logger.warning("Legacy appearance migration could not create a backup folder.");
                    continue;
                }
                Files.copy(file.toPath(), new File(backupDir, name).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                Files.write(file.toPath(), fixedBytes);

                AuthDatabase.AppearanceProfile profile = database.getAppearanceProfile(uuid);
                String model = profile == null ? "classic" : normalizeModel(profile.model());
                String url = "";
                String storageKey = "";
                if (storage.isEnabled()) {
                    try {
                        String characterKey = AuthDatabase.characterKey(database.getRpName(uuid));
                        R2AppearanceStorage.UploadResult upload = characterKey.isBlank()
                                ? storage.uploadSkin(uuid, hash, fixedBytes)
                                : storage.uploadCharacterSkin(characterKey, hash, fixedBytes);
                        url = upload.publicUrl();
                        storageKey = upload.key();
                    } catch (IOException | InterruptedException e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        logger.warning("Legacy appearance migration could not re-upload "
                                + uuid + ": " + e.getMessage());
                    }
                }
                if (!database.updateAppearance(uuid, model, hash, url, storageKey)) {
                    // The hash and the file must never drift apart: restore the backup so the
                    // stored profile still matches the stored bytes.
                    Files.copy(new File(backupDir, name).toPath(), file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                    logger.warning("Legacy appearance migration rolled back " + name
                            + " because the profile could not be updated.");
                    continue;
                }
                migrated++;
            } catch (IOException | RuntimeException e) {
                logger.warning("Legacy appearance migration skipped " + name + ": " + e.getMessage());
            }
        }
        touch(marker);
        if (migrated > 0) {
            logger.info("Legacy appearance migration repaired " + migrated + " skin(s).");
        }
        return migrated;
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static void touch(File marker) {
        try {
            if (!marker.exists()) {
                marker.createNewFile();
            }
        } catch (IOException ignored) {
            // Best effort; the next startup simply re-scans.
        }
    }

    private static void mirrorRegion(BufferedImage target, BufferedImage source,
                                     int sourceX, int sourceY, int targetX, int targetY,
                                     int width, int height) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                target.setRGB(targetX + column, targetY + row,
                        source.getRGB(sourceX + (width - 1 - column), sourceY + row));
            }
        }
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        String payload = dataUrl;
        int comma = dataUrl.indexOf(',');
        if (comma >= 0) {
            String prefix = dataUrl.substring(0, comma).toLowerCase();
            if (!prefix.startsWith("data:image/png;base64")) {
                throw new IllegalArgumentException("Unsupported data URL.");
            }
            payload = dataUrl.substring(comma + 1);
        }
        return Base64.getDecoder().decode(payload);
    }

    private static String sha1(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SaveResult(boolean uploaded, boolean success, String message, String model, String hash, String url) {
        public static SaveResult empty() {
            return new SaveResult(false, true, "", "classic", "", "");
        }

        public static SaveResult saved(String model, String hash) {
            return saved(model, hash, "");
        }

        public static SaveResult saved(String model, String hash, String url) {
            return new SaveResult(true, true, "", model, hash, url == null ? "" : url);
        }

        public static SaveResult error(String message) {
            return new SaveResult(true, false, message, "classic", "", "");
        }
    }
}
