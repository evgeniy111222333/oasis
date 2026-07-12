package ua.rp.chat.client.appearance;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import ua.rp.chat.client.EclipseApiClient;
import ua.rp.chat.client.EclipseClientMod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EclipseAppearanceManager {
    private static final long FIRST_LOAD_RETRY_MS = 2500L;
    private static final long MAX_FIRST_LOAD_RETRY_MS = 30000L;
    private static final long PROFILE_REFRESH_MS = 120000L;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 5000;
    private static final int HTTP_READ_TIMEOUT_MS = 10000;
    private static final int HTTP_ATTEMPTS = 3;
    private static final int MAX_PROFILE_BYTES = 64 * 1024;
    private static final int MAX_TEXTURE_BYTES = 512 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();
    private static long lastSweepMs = 0L;

    private EclipseAppearanceManager() {
    }

    public static PlayerSkin getSkin(UUID uuid) {
        Minecraft client = Minecraft.getInstance();
        if (uuid == null || client == null || client.level == null) {
            return null;
        }

        Entry entry = CACHE.computeIfAbsent(uuid, ignored -> new Entry());

        long now = System.currentTimeMillis();
        long refreshDelay = entry.skin == null ? entry.retryDelayMs : PROFILE_REFRESH_MS;
        if (!entry.loading.get() && now - entry.lastAttemptMs > refreshDelay) {
            entry.lastAttemptMs = now;
            entry.loading.set(true);
            CompletableFuture.supplyAsync(() -> fetchProfile(uuid))
                    .whenComplete((profile, throwable) -> {
                        if (throwable != null) {
                            EclipseClientMod.LOGGER.warn("Appearance profile task failed for " + uuid, throwable);
                            entry.recordFailure();
                            entry.loading.set(false);
                            return;
                        }
                        if (profile == null) {
                            entry.recordFailure();
                            entry.loading.set(false);
                            return;
                        }
                        if (!profile.hasAppearance) {
                            entry.loading.set(false);
                            entry.missing = true;
                            entry.skin = null;
                            entry.hash = "";
                            entry.retryDelayMs = PROFILE_REFRESH_MS;
                            return;
                        }
                        if (profile.hash.equals(entry.hash) && entry.skin != null) {
                            entry.recordSuccess();
                            entry.loading.set(false);
                            return;
                        }
                        fetchAndRegisterTexture(client, uuid, profile, entry);
                    });
        }

        sweepCache(now);
        return entry.skin;
    }

    private static AppearanceProfile fetchProfile(UUID uuid) {
        EclipseClientMod.LOGGER.info("Requesting appearance profile for player {}...", uuid);
        String body = getWithRetry(
                EclipseApiClient.resolve("/api/appearance/profile?uuid=" + uuid),
                "appearance profile " + uuid,
                MAX_PROFILE_BYTES
        );
        if (body == null || body.isBlank()) {
            EclipseClientMod.LOGGER.warn("Appearance profile response is empty for player {}", uuid);
            return null;
        }

        boolean hasAppearance = body.contains("\"hasAppearance\":true");
        String model = extractJsonString(body, "model");
        String hash = extractJsonString(body, "hash");
        String textureUrl = extractJsonString(body, "textureUrl");
        String fallbackTextureUrl = extractJsonString(body, "fallbackTextureUrl");
        if (!hasAppearance || hash == null || textureUrl == null) {
            EclipseClientMod.LOGGER.info("Player {} has no custom appearance configured", uuid);
            return new AppearanceProfile(false, "classic", "", "", "");
        }
        EclipseClientMod.LOGGER.info("Player {} has custom appearance: model={}, hash={}", uuid, model, hash);
        if (fallbackTextureUrl == null || fallbackTextureUrl.isBlank()) {
            fallbackTextureUrl = "/api/appearance/texture/" + uuid + ".png?v=" + hash;
        }
        return new AppearanceProfile(true, model == null ? "classic" : model, hash, textureUrl, fallbackTextureUrl);
    }

    private static void fetchAndRegisterTexture(Minecraft client, UUID uuid, AppearanceProfile profile, Entry entry) {
        CompletableFuture.supplyAsync(() -> loadTextureBytes(client, uuid, profile))
                .whenComplete((bytes, throwable) -> {
                    if (throwable != null || bytes == null || bytes.length == 0) {
                        if (throwable != null) {
                            EclipseClientMod.LOGGER.warn("Appearance texture task failed for " + uuid, throwable);
                        }
                        entry.recordFailure();
                        entry.loading.set(false);
                        return;
                    }

                    client.execute(() -> {
                        try {
                            NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                            Identifier id = Identifier.fromNamespaceAndPath("eclipseclient", "appearance/" + uuid.toString().replace("-", "_") + "_" + profile.hash);
                            DynamicTexture texture = new DynamicTexture(() -> "Eclipse appearance " + uuid, image);
                            if (entry.textureId != null && !entry.textureId.equals(id)) {
                                client.getTextureManager().release(entry.textureId);
                            }
                            client.getTextureManager().register(id, texture);
                            PlayerModelType model = "slim".equalsIgnoreCase(profile.model) ? PlayerModelType.SLIM : PlayerModelType.WIDE;
                            entry.skin = PlayerSkin.insecure(new EclipseTextureAsset(id), null, null, model);
                            entry.textureId = id;
                            entry.hash = profile.hash;
                            entry.model = profile.model;
                            entry.debugSkinPath = exportDebugTexture(client, uuid, profile, bytes);
                            entry.missing = false;
                            entry.recordSuccess();
                            EclipseClientMod.LOGGER.info("Appearance texture ready for {} (hash={})", uuid, profile.hash);
                        } catch (IOException e) {
                            EclipseClientMod.LOGGER.warn("Could not read Eclipse appearance texture for " + uuid, e);
                            entry.recordFailure();
                        } finally {
                            entry.loading.set(false);
                        }
                    });
                });
    }

    private static String getWithRetry(String url, String label, int maxBytes) {
        byte[] bytes = getBytesWithRetry(url, label, maxBytes, HTTP_ATTEMPTS);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] getBytesWithRetry(String url, String label, int maxBytes, int attempts) {
        String lastFailure = "unknown error";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpURLConnection connection = null;
            long startedAt = System.currentTimeMillis();
            try {
                connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
                connection.setRequestProperty("Accept", label.startsWith("appearance texture") ? "image/png" : "application/json");
                connection.setRequestProperty("User-Agent", "EclipseRolePlayClient/1.1");
                int code = connection.getResponseCode();
                if (code != 200) {
                    lastFailure = "HTTP " + code;
                } else {
                    long declaredLength = connection.getContentLengthLong();
                    if (declaredLength > maxBytes) {
                        lastFailure = "response too large (" + declaredLength + " bytes)";
                    } else {
                        try (InputStream stream = connection.getInputStream()) {
                            byte[] bytes = stream.readNBytes(maxBytes + 1);
                            if (bytes.length > maxBytes) {
                                lastFailure = "response exceeded " + maxBytes + " bytes";
                            } else if (bytes.length == 0) {
                                lastFailure = "empty response";
                            } else {
                                EclipseClientMod.LOGGER.debug("Downloaded {} on attempt {} in {} ms", label, attempt,
                                        System.currentTimeMillis() - startedAt);
                                return bytes;
                            }
                        }
                    }
                }
            } catch (IOException | IllegalArgumentException e) {
                lastFailure = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
                EclipseClientMod.LOGGER.debug("Download attempt {}/{} failed for {}: {}", attempt, attempts, label, lastFailure);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }

            if (attempt < attempts && !sleepBeforeRetry(attempt)) {
                lastFailure = "interrupted";
                break;
            }
        }
        EclipseClientMod.LOGGER.warn("Failed to download {} after {} attempts: {}", label, attempts, lastFailure);
        return null;
    }

    private static boolean sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(attempt == 1 ? 250L : 750L);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static byte[] loadTextureBytes(Minecraft client, UUID uuid, AppearanceProfile profile) {
        Path cacheFile = textureCacheFile(client, profile.hash);
        if (cacheFile != null && Files.isRegularFile(cacheFile)) {
            try {
                byte[] cached = Files.readAllBytes(cacheFile);
                if (isValidTexture(cached, profile.hash)) {
                    EclipseClientMod.LOGGER.debug("Loaded appearance texture {} from disk cache", profile.hash);
                    return cached;
                }
                EclipseClientMod.LOGGER.warn("Discarding invalid cached appearance texture {}", profile.hash);
                Files.deleteIfExists(cacheFile);
            } catch (IOException e) {
                EclipseClientMod.LOGGER.warn("Could not read cached appearance texture " + profile.hash, e);
            }
        }

        List<TextureSource> sources = textureSources(profile);
        byte[] downloaded = null;
        String sourceName = "none";
        for (TextureSource source : sources) {
            byte[] candidate = getBytesWithRetry(source.url, "appearance texture " + uuid + " via " + source.name,
                    MAX_TEXTURE_BYTES, source.attempts);
            if (candidate == null) {
                continue;
            }
            if (!isValidTexture(candidate, profile.hash)) {
                EclipseClientMod.LOGGER.warn("Rejected appearance texture for {} from {} because PNG signature or SHA-1 is invalid", uuid, source.name);
                continue;
            }
            downloaded = candidate;
            sourceName = source.name;
            break;
        }

        if (downloaded == null) {
            EclipseClientMod.LOGGER.warn("All appearance texture sources failed for {} (hash={})", uuid, profile.hash);
            return null;
        }
        EclipseClientMod.LOGGER.info("Downloaded appearance texture for {} via {}", uuid, sourceName);
        if (cacheFile == null) {
            return downloaded;
        }

        Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(temporary, downloaded);
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EclipseClientMod.LOGGER.warn("Could not cache Eclipse appearance " + profile.hash, e);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best effort cleanup of an incomplete temporary file.
            }
        }
        return downloaded;
    }

    private static List<TextureSource> textureSources(AppearanceProfile profile) {
        Set<String> uniqueUrls = new LinkedHashSet<>();
        uniqueUrls.add(EclipseApiClient.resolve(profile.textureUrl));
        uniqueUrls.add(EclipseApiClient.resolve(profile.fallbackTextureUrl));
        List<TextureSource> sources = new ArrayList<>();
        int index = 0;
        for (String url : uniqueUrls) {
            if (url != null && !url.isBlank()) {
                boolean primaryCdn = index++ == 0;
                sources.add(new TextureSource(primaryCdn ? "CDN" : "API fallback", url,
                        primaryCdn ? 1 : HTTP_ATTEMPTS));
            }
        }
        return sources;
    }

    private static boolean isValidTexture(byte[] bytes, String expectedHash) {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length || bytes.length > MAX_TEXTURE_BYTES
                || expectedHash == null || !expectedHash.matches("[a-fA-F0-9]{40}")) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        try {
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
            return actualHash.equalsIgnoreCase(expectedHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is unavailable", e);
        }
    }

    private static Path textureCacheFile(Minecraft client, String hash) {
        if (client == null || hash == null || !hash.matches("[a-fA-F0-9]{40}")) {
            return null;
        }
        return client.gameDirectory.toPath()
                .resolve("eclipse-cache")
                .resolve("skins")
                .resolve(hash.toLowerCase() + ".png");
    }

    private static String extractJsonString(String body, String propertyName) {
        String marker = "\"" + propertyName + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            return null;
        }

        StringBuilder value = new StringBuilder();
        for (int i = start + marker.length(); i < body.length(); i++) {
            char current = body.charAt(i);
            if (current == '"') {
                return value.toString();
            }
            if (current == '\\' && i + 1 < body.length()) {
                char escaped = body.charAt(++i);
                if (escaped == 'u' && i + 4 < body.length()) {
                    String hex = body.substring(i + 1, i + 5);
                    try {
                        value.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } catch (NumberFormatException ignored) {
                        value.append('u');
                    }
                } else {
                    value.append(escaped);
                }
            } else {
                value.append(current);
            }
        }
        return null;
    }

    private static void sweepCache(long now) {
        if (now - lastSweepMs < 60000L) {
            return;
        }
        lastSweepMs = now;
        CACHE.entrySet().removeIf(entry -> entry.getValue().missing && now - entry.getValue().lastAttemptMs > 120000L);
    }

    public static DebugSkinInfo getDebugSkinInfo(UUID uuid) {
        Entry entry = uuid == null ? null : CACHE.get(uuid);
        if (entry == null || entry.debugSkinPath == null || entry.hash == null || entry.hash.isBlank()) {
            return null;
        }
        return new DebugSkinInfo(entry.debugSkinPath, entry.hash, entry.model == null ? "classic" : entry.model);
    }

    private static String exportDebugTexture(Minecraft client, UUID uuid, AppearanceProfile profile, byte[] bytes) {
        if (client == null || uuid == null || bytes == null) {
            return null;
        }
        try {
            Path debugDir = client.gameDirectory.toPath().resolve("eclipse-debug").resolve("skins");
            Files.createDirectories(debugDir);
            Path skinPath = debugDir.resolve(uuid + ".png");
            Files.write(skinPath, bytes);
            return skinPath.toAbsolutePath().toString().replace('\\', '/');
        } catch (IOException e) {
            EclipseClientMod.LOGGER.debug("Could not export Eclipse debug skin " + uuid, e);
            return null;
        }
    }

    private record AppearanceProfile(boolean hasAppearance, String model, String hash, String textureUrl,
                                     String fallbackTextureUrl) {}

    private record TextureSource(String name, String url, int attempts) {}

    public record DebugSkinInfo(String path, String hash, String model) {}

    private static final class Entry {
        private final AtomicBoolean loading = new AtomicBoolean(false);
        private volatile PlayerSkin skin;
        private volatile Identifier textureId;
        private volatile String hash = "";
        private volatile String model = "classic";
        private volatile String debugSkinPath;
        private volatile boolean missing = false;
        private volatile long lastAttemptMs = 0L;
        private volatile long retryDelayMs = FIRST_LOAD_RETRY_MS;

        private void recordFailure() {
            retryDelayMs = Math.min(MAX_FIRST_LOAD_RETRY_MS, Math.max(FIRST_LOAD_RETRY_MS, retryDelayMs * 2L));
        }

        private void recordSuccess() {
            retryDelayMs = FIRST_LOAD_RETRY_MS;
        }
    }
}
