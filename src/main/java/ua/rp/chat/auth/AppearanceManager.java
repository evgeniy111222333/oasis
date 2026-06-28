package ua.rp.chat.auth;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.logging.Logger;

public class AppearanceManager {
    public static final int MAX_APPEARANCE_BYTES = 512 * 1024;

    private final File appearanceFolder;
    private final AuthDatabase database;
    private final Logger logger;

    public AppearanceManager(File dataFolder, AuthDatabase database, Logger logger) {
        this.appearanceFolder = new File(dataFolder, "appearances");
        this.database = database;
        this.logger = logger;
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
            if (!database.updateAppearance(uuid, normalizedModel, hash)) {
                return SaveResult.error("Не удалось привязать облик к персонажу.");
            }
            return SaveResult.saved(normalizedModel, hash);
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
        return profile != null && getAppearanceFile(uuid).isFile();
    }

    public static String normalizeModel(String model) {
        return "slim".equalsIgnoreCase(model) ? "slim" : "classic";
    }

    private static boolean isValidAppearanceSize(int width, int height) {
        return width == 64 && (height == 64 || height == 32);
    }

    private static byte[] normalizeAppearanceBytes(BufferedImage image, byte[] originalBytes) throws IOException {
        if (image.getWidth() == 64 && image.getHeight() == 64) {
            return originalBytes;
        }

        BufferedImage normalized = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = normalized.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(normalized, "png", output);
        return output.toByteArray();
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

    public record SaveResult(boolean uploaded, boolean success, String message, String model, String hash) {
        public static SaveResult empty() {
            return new SaveResult(false, true, "", "classic", "");
        }

        public static SaveResult saved(String model, String hash) {
            return new SaveResult(true, true, "", model, hash);
        }

        public static SaveResult error(String message) {
            return new SaveResult(true, false, message, "classic", "");
        }
    }
}
