package ua.rp.chat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.net.URI;

public final class OasisApiClient {
    private static final String DEFAULT_BASE_URL = "http://localhost:25580";
    private static volatile String rememberedBaseUrl = "";

    private OasisApiClient() {
    }

    public static void rememberFromUrl(String url) {
        String base = baseFromUrl(url);
        if (!base.isBlank()) {
            rememberedBaseUrl = base;
        }
    }

    public static String baseUrl() {
        String override = System.getProperty("oasis.apiUrl", "").trim();
        if (!override.isBlank()) {
            return trimTrailingSlash(override);
        }

        if (!rememberedBaseUrl.isBlank()) {
            return rememberedBaseUrl;
        }

        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            ServerData server = client.getCurrentServer();
            if (server != null && server.ip != null && !server.ip.isBlank()) {
                String host = server.ip.trim();
                int colon = host.lastIndexOf(':');
                if (colon > 0 && host.indexOf(']') < 0) {
                    host = host.substring(0, colon);
                }
                if (!host.isBlank()) {
                    return "http://" + host + ":25580";
                }
            }
        }

        return DEFAULT_BASE_URL;
    }

    public static String resolve(String pathOrUrl) {
        if (pathOrUrl == null || pathOrUrl.isBlank()) {
            return baseUrl();
        }
        String value = pathOrUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        return baseUrl() + value;
    }

    private static String baseFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                return "";
            }
            StringBuilder result = new StringBuilder();
            result.append(scheme).append("://").append(host);
            if (port >= 0) {
                result.append(':').append(port);
            }
            return result.toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
