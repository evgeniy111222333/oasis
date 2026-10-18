package ua.rp.chat.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.net.URI;
import java.util.Locale;

public final class EclipseApiClient {
    private static final String DEFAULT_BASE_URL = "http://localhost:25580";
    private static final String PRODUCTION_GAME_HOST = "13.51.232.191";
    private static final String PRODUCTION_API_URL = "https://api.eclipse-roleplay.online";
    /** Port of the game server's embedded auth/appearance web server. */
    private static final int API_PORT = 25580;
    private static volatile String rememberedBaseUrl = "";

    private EclipseApiClient() {
    }

    public static void rememberFromUrl(String url) {
        String base = baseFromUrl(url);
        if (!base.isBlank()) {
            rememberedBaseUrl = base;
        }
    }

    public static String baseUrl() {
        String host = connectedHost();

        // A local or private game server runs its own auth/appearance backend. Resolving local
        // accounts against a remote override would silently return "no appearance", so for
        // local/private hosts the connected server always wins over -Declipse.apiUrl.
        if (isLocalHost(host)) {
            String local = apiForHost(bareHost(host));
            if (!local.isBlank()) {
                return local;
            }
        }

        String override = System.getProperty("eclipse.apiUrl", "").trim();
        if (!override.isBlank()) {
            return trimTrailingSlash(override);
        }

        if (!rememberedBaseUrl.isBlank()) {
            return rememberedBaseUrl;
        }

        if (!host.isBlank()) {
            if (PRODUCTION_GAME_HOST.equalsIgnoreCase(bareHost(host))
                    || "api.eclipse-roleplay.online".equalsIgnoreCase(bareHost(host))) {
                return PRODUCTION_API_URL;
            }
            String derived = apiForHost(bareHost(host));
            if (!derived.isBlank()) {
                return derived;
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

    /** Raw address of the server the client is connected to, empty when not in a world. */
    private static String connectedHost() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return "";
        }
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) {
            return "";
        }
        return server.ip.trim();
    }

    /** Bare host of a server address, without a trailing {@code :port} and without IPv6 brackets. */
    private static String bareHost(String serverIp) {
        if (serverIp == null) {
            return "";
        }
        String value = serverIp.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int first = value.indexOf(':');
        int last = value.lastIndexOf(':');
        if (first > 0 && first == last) {
            return value.substring(0, first);
        }
        return value;
    }

    private static String apiForHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        boolean ipv6 = host.indexOf(':') >= 0;
        return "http://" + (ipv6 ? "[" + host + "]" : host) + ":" + API_PORT;
    }

    /** True for loopback and RFC 1918 / link-local private addresses. */
    private static boolean isLocalHost(String serverIp) {
        String host = bareHost(serverIp).toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        if (host.equals("localhost") || host.equals("127.0.0.1")
                || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1")) {
            return true;
        }
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) {
            return true;
        }
        if (host.startsWith("172.")) {
            int dot = host.indexOf('.', 4);
            if (dot > 4) {
                try {
                    int second = Integer.parseInt(host.substring(4, dot));
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        return false;
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
