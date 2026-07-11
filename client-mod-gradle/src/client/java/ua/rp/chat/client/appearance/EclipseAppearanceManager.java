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
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EclipseAppearanceManager {
    private static final long FIRST_LOAD_RETRY_MS = 2500L;
    private static final long PROFILE_REFRESH_MS = 120000L;
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
        long refreshDelay = entry.skin == null ? FIRST_LOAD_RETRY_MS : PROFILE_REFRESH_MS;
        if (!entry.loading.get() && now - entry.lastAttemptMs > refreshDelay) {
            entry.lastAttemptMs = now;
            entry.loading.set(true);
            CompletableFuture.supplyAsync(() -> fetchProfile(uuid))
                    .whenComplete((profile, throwable) -> {
                        if (throwable != null) {
                            EclipseClientMod.LOGGER.debug("Appearance profile request failed for " + uuid, throwable);
                            entry.loading.set(false);
                            return;
                        }
                        if (profile == null || !profile.hasAppearance) {
                            entry.loading.set(false);
                            entry.missing = true;
                            entry.skin = null;
                            entry.hash = "";
                            return;
                        }
                        if (profile.hash.equals(entry.hash) && entry.skin != null) {
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
        String body = get(EclipseApiClient.resolve("/api/appearance/profile?uuid=" + uuid));
        if (body == null || body.isBlank()) {
            return null;
        }

        boolean hasAppearance = body.contains("\"hasAppearance\":true");
        String model = extractJsonString(body, "model");
        String hash = extractJsonString(body, "hash");
        String textureUrl = extractJsonString(body, "textureUrl");
        if (!hasAppearance || hash == null || textureUrl == null) {
            return new AppearanceProfile(false, "classic", "", "");
        }
        return new AppearanceProfile(true, model == null ? "classic" : model, hash, textureUrl);
    }

    private static void fetchAndRegisterTexture(Minecraft client, UUID uuid, AppearanceProfile profile, Entry entry) {
        CompletableFuture.supplyAsync(() -> loadTextureBytes(client, profile))
                .whenComplete((bytes, throwable) -> {
                    if (throwable != null || bytes == null || bytes.length == 0) {
                        EclipseClientMod.LOGGER.debug("Appearance texture request failed for " + uuid, throwable);
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
                        } catch (IOException e) {
                            EclipseClientMod.LOGGER.warn("Could not read Eclipse appearance texture for " + uuid, e);
                        } finally {
                            entry.loading.set(false);
                        }
                    });
                });
    }

    private static String get(String url) {
        byte[] bytes = getBytes(url);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(700);
            connection.setReadTimeout(1400);
            int code = connection.getResponseCode();
            if (code != 200) {
                return null;
            }
            return connection.getInputStream().readAllBytes();
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] loadTextureBytes(Minecraft client, AppearanceProfile profile) {
        Path cacheFile = textureCacheFile(client, profile.hash);
        if (cacheFile != null && Files.isRegularFile(cacheFile)) {
            try {
                byte[] cached = Files.readAllBytes(cacheFile);
                if (cached.length > 0) {
                    return cached;
                }
            } catch (IOException ignored) {
                // A damaged cache entry is replaced by a fresh download below.
            }
        }

        byte[] downloaded = getBytes(EclipseApiClient.resolve(profile.textureUrl));
        if (downloaded == null || downloaded.length == 0 || cacheFile == null) {
            return downloaded;
        }
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.write(cacheFile, downloaded);
        } catch (IOException e) {
            EclipseClientMod.LOGGER.debug("Could not cache Eclipse appearance " + profile.hash, e);
        }
        return downloaded;
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

    private record AppearanceProfile(boolean hasAppearance, String model, String hash, String textureUrl) {}

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
    }
}
