package ua.rp.chat.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.server.level.ServerPlayer;
import ua.rp.chat.RPChat;
import ua.rp.chat.vitals.StaminaManager;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Embedded HTTP server for serving the auth UI and processing REST API endpoints.
 */
public class AuthWebServer {

    private final RPChat plugin;
    private final AuthManager authManager;
    private final StaminaManager staminaManager;
    private HttpServer server;
    private ExecutorService webExecutor;
    private final Gson gson = new Gson();
    /** Hard cap on any request body; the appearance payload is the largest legitimate one. */
    private static final int MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024;
    /** Origins allowed to read the API cross-origin; empty means same-origin only. */
    private final java.util.Set<String> allowedOrigins = new java.util.HashSet<>();
    /** Credential-attempt budgets: per remote IP and per account, to stop online brute force. */
    private final ua.rp.chat.antiabuse.ActionRateLimiter ipAttempts =
            new ua.rp.chat.antiabuse.ActionRateLimiter(0.1, 10.0);
    private final ua.rp.chat.antiabuse.ActionRateLimiter accountAttempts =
            new ua.rp.chat.antiabuse.ActionRateLimiter(0.02, 5.0);
    /** Validates a per-login token against a target player (wired to the microvoxel session). */
    private java.util.function.BiPredicate<ServerPlayer, Long> apiTokenAuthorizer;
    /** Short-lived vitals cookies: opaque id -> (uuid, expiry), set when the body page loads. */
    private final Map<String, VitalsGrant> vitalsGrants = new java.util.concurrent.ConcurrentHashMap<>();
    /** One-time body-page bootstrap codes (code -> uuid), so no bearer token ever rides a URL. */
    private final Map<String, VitalsGrant> bodyCodes = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long VITALS_GRANT_TTL_MILLIS = 10 * 60 * 1000L;
    private static final long BODY_CODE_TTL_MILLIS = 60 * 1000L;

    private record VitalsGrant(UUID uuid, long expiresAt) {
    }

    public void setApiTokenAuthorizer(java.util.function.BiPredicate<ServerPlayer, Long> authorizer) {
        this.apiTokenAuthorizer = authorizer;
    }

    /** True when {@code token} is the valid per-login token for {@code player} (fail-closed). */
    private boolean authorizeApiToken(ServerPlayer player, String token) {
        if (player == null || token == null || token.isBlank() || apiTokenAuthorizer == null) {
            return false;
        }
        try {
            return apiTokenAuthorizer.test(player, Long.parseLong(token.trim()));
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private String readCookie(HttpExchange exchange, String name) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null || header.isBlank()) return null;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** The uuid authorized for a vitals request: a token query param OR a valid vitals cookie. */
    private UUID authorizedVitalsUuid(ServerPlayer target, String token, String cookie) {
        if (target == null) return null;
        if (authorizeApiToken(target, token)) return target.getUUID();
        if (cookie == null) return null;
        VitalsGrant grant = vitalsGrants.get(cookie);
        if (grant == null) return null;
        if (grant.expiresAt() < System.currentTimeMillis()) {
            vitalsGrants.remove(cookie);
            return null;
        }
        return grant.uuid().equals(target.getUUID()) ? grant.uuid() : null;
    }

    /** The client IP, trusting X-Forwarded-For only when the request came from the proxy. */
    private String clientIp(HttpExchange exchange) {
        String remote = exchange.getRemoteAddress() == null ? null
                : exchange.getRemoteAddress().getAddress().getHostAddress();
        boolean loopback = remote != null
                && (remote.equals("127.0.0.1") || remote.equals("::1") || remote.equals("0:0:0:0:0:0:0:1"));
        if (loopback) {
            String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",", 2)[0].trim();
            }
        }
        return remote == null ? "unknown" : remote;
    }

    /** True when the caller exceeded the per-IP credential budget. */
    private boolean blockedByIp(HttpExchange exchange) {
        return !ipAttempts.tryAcquire("ip:" + clientIp(exchange));
    }

    /** True when the caller exceeded the per-account credential budget. */
    private boolean blockedByAccount(String account) {
        return account != null && !account.isBlank()
                && !accountAttempts.tryAcquire("acct:" + account.toLowerCase(java.util.Locale.ROOT));
    }

    /** Clears an account's failure budget after a fully successful authentication. */
    private void resetAccountBudget(String account) {
        if (account != null && !account.isBlank()) {
            accountAttempts.reset("acct:" + account.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * Authentication diagnostics intentionally omit passwords, full tokens,
     * e-mail addresses and request bodies. Caddy supplies the original player
     * IP through X-Forwarded-For because this server is only reachable locally.
     */
    private void auditAuth(HttpExchange exchange, String event, String loginName, UUID uuid, String outcome) {
        String forwarded = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        String remote = forwarded == null || forwarded.isBlank()
                ? String.valueOf(exchange.getRemoteAddress())
                : forwarded.split(",", 2)[0].trim();
        String agent = exchange.getRequestHeaders().getFirst("User-Agent");
        if (agent == null || agent.isBlank()) agent = "unknown";
        if (agent.length() > 96) agent = agent.substring(0, 96);
        plugin.getLogger().info("[AUTH-AUDIT] event=" + event
                + " remote=" + remote
                + " login=" + (loginName == null || loginName.isBlank() ? "-" : loginName)
                + " uuid=" + (uuid == null ? "-" : uuid)
                + " outcome=" + outcome
                + " agent=" + agent);
    }

    private static final class ApiJsonResult {
        private final int statusCode;
        private final JsonObject json;

        private ApiJsonResult(int statusCode, JsonObject json) {
            this.statusCode = statusCode;
            this.json = json;
        }
    }

    public AuthWebServer(RPChat plugin, AuthManager authManager, StaminaManager staminaManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.staminaManager = staminaManager;
    }

    public void start(int port) {
        try {
            // Cross-origin readers must be explicitly allow-listed; the default is same-origin
            // only, which is all the CEF auth/body pages need (they call the API same-origin).
            allowedOrigins.clear();
            String configuredOrigins = plugin.getConfig().getString("web.cors-origins", "");
            if (configuredOrigins != null) {
                for (String origin : configuredOrigins.split(",")) {
                    String trimmed = origin.trim();
                    if (!trimmed.isEmpty()) {
                        allowedOrigins.add(trimmed.toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // Route web UI page
            server.createContext("/auth", new StaticFileHandler("index.html", "text/html; charset=utf-8"));
            server.createContext("/appearance", new StaticFileHandler("appearance.html", "text/html; charset=utf-8"));
            server.createContext("/", new StaticFileHandler("index.html", "text/html; charset=utf-8"));
            server.createContext("/index.html", new StaticFileHandler("index.html", "text/html; charset=utf-8"));
            server.createContext("/style.css", new StaticFileHandler("style.css", "text/css; charset=utf-8"));
            server.createContext("/app.js", new StaticFileHandler("app.js", "application/javascript; charset=utf-8"));
            server.createContext("/appearance.css", new StaticFileHandler("appearance.css", "text/css; charset=utf-8"));
            server.createContext("/appearance.js", new StaticFileHandler("appearance.js", "application/javascript; charset=utf-8"));
            server.createContext("/body", new BodyPageHandler());
            server.createContext("/body.css", new StaticFileHandler("body.css", "text/css; charset=utf-8"));
            server.createContext("/body.js", new StaticFileHandler("body.js", "application/javascript; charset=utf-8"));
            server.createContext("/assets", new WebAssetHandler());
            server.createContext("/bg_village.jpg", new StaticFileHandler("bg_village.jpg", "image/jpeg"));
            
            // Route API endpoints
            server.createContext("/api/status", new ApiStatusHandler());
            server.createContext("/api/login", new ApiLoginHandler());
            server.createContext("/api/register", new ApiRegisterHandler());
            server.createContext("/api/recovery", new ApiRecoveryHandler());
            server.createContext("/api/client-session", new ApiClientSessionHandler());
            server.createContext("/api/appearance/profile", new ApiAppearanceProfileHandler());
            server.createContext("/api/appearance/texture", new ApiAppearanceTextureHandler());
            server.createContext("/api/appearance/edit-session", new ApiAppearanceEditSessionHandler());
            server.createContext("/api/appearance/update", new ApiAppearanceUpdateHandler());
            server.createContext("/api/server-status", new ApiServerStatusHandler());
            server.createContext("/api/vitals", new ApiVitalsHandler());
            server.createContext("/api/vitals/treat", new ApiVitalsTreatHandler());
            server.createContext("/api/body-session", new ApiBodySessionHandler());
            server.createContext("/api/required-mods", new ApiRequiredModsHandler());
            server.createContext("/client", new ClientDownloadHandler());

            webExecutor = Executors.newFixedThreadPool(6, runnable -> {
                Thread thread = new Thread(runnable, "eclipse-web");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(webExecutor);
            server.start();
            plugin.getLogger().info("Auth Web Server started on port " + port);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start Auth Web Server: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            if (webExecutor != null) {
                webExecutor.shutdownNow();
                webExecutor = null;
            }
            plugin.getLogger().info("Auth Web Server stopped.");
        }
    }

    /**
     * Handler to serve static assets from the plugin data folder.
     */
    private class StaticFileHandler implements HttpHandler {
        private final String filename;
        private final String contentType;

        public StaticFileHandler(String filename, String contentType) {
            this.filename = filename;
            this.contentType = contentType;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")
                    && !exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            if (filename.endsWith(".html")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
                exchange.getResponseHeaders().set("Pragma", "no-cache");
                exchange.getResponseHeaders().set("Expires", "0");
            } else if (filename.endsWith(".jpg") || filename.endsWith(".png")) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400, immutable");
            } else {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600, must-revalidate");
            }
            
            File webDir = new File(plugin.getDataFolder(), "web");
            File file = new File(webDir, filename);

            if (!file.exists()) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());

            sendResponse(exchange, 200, contentType, bytes);
        }
    }

    /**
     * GET /body?username=...&token=... — the game client opens the vitals page with its per-login
     * token. On a valid token we grant a short-lived, HttpOnly cookie scoped to the vitals API so
     * the page's same-origin fetches are authorized without exposing the token to page scripts.
     */
    private class BodyPageHandler implements HttpHandler {
        private final StaticFileHandler delegate =
                new StaticFileHandler("body.html", "text/html; charset=utf-8");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            String username = query.get("username");
            String token = query.get("token");
            String code = query.get("code");
            ServerPlayer player = username == null ? null
                    : plugin.getServer().getPlayerList().getPlayerByName(username);

            // Preferred path: a one-time code, so the session token never appears in a URL/log.
            if (code != null && !code.isBlank()) {
                VitalsGrant grant = bodyCodes.remove(code);
                if (grant != null && grant.expiresAt() >= System.currentTimeMillis()) {
                    String id = UUID.randomUUID().toString().replace("-", "");
                    vitalsGrants.put(id, grant);
                    exchange.getResponseHeaders().add("Set-Cookie",
                            "eclipse_vitals=" + id + "; Path=/api/vitals; HttpOnly; SameSite=Strict; Max-Age=600");
                }
                String location = "/body";
                if (username != null && !username.isBlank()) {
                    location += "?username=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8.name());
                }
                exchange.getResponseHeaders().set("Location", location);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            if (player != null && authorizeApiToken(player, token)) {
                String id = UUID.randomUUID().toString().replace("-", "");
                vitalsGrants.put(id, new VitalsGrant(player.getUUID(),
                        System.currentTimeMillis() + VITALS_GRANT_TTL_MILLIS));
                exchange.getResponseHeaders().add("Set-Cookie",
                        "eclipse_vitals=" + id + "; Path=/api/vitals; HttpOnly; SameSite=Strict; Max-Age=600");
            }
            delegate.handle(exchange);
        }
    }

    private class WebAssetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")
                    && !exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600, must-revalidate");

            String path = exchange.getRequestURI().getPath();
            String relPath = path.startsWith("/assets/") ? path.substring("/assets/".length()) : "";
            if (relPath.isBlank() || relPath.contains("..") || relPath.contains("\\") || relPath.startsWith("/")) {
                sendResponse(exchange, 400, "text/plain", "Bad Request");
                return;
            }

            File webDir = new File(plugin.getDataFolder(), "web");
            File assetsDir = new File(webDir, "assets");
            File file = new File(assetsDir, relPath);
            String rootPath = assetsDir.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath) || !file.exists() || file.isDirectory()) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());

            sendResponse(exchange, 200, contentTypeFor(file.getName()), bytes);
        }
    }

    /**
     * GET /api/status?token=...
     */
    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            String token = requestToken(exchange);

            if (token == null || token.isEmpty()) {
                auditAuth(exchange, "status", null, null, "missing_token");
                sendJsonResponse(exchange, 400, createErrorJson("\u041e\u0442\u0441\u0443\u0442\u0441\u0442\u0432\u0443\u0435\u0442 \u0442\u043e\u043a\u0435\u043d \u0430\u0432\u0442\u043e\u0440\u0438\u0437\u0430\u0446\u0438\u0438."));
                return;
            }

            UUID uuid = authManager.getTokenToUuid().get(token);
            if (uuid == null) {
                auditAuth(exchange, "status", null, null, "invalid_token");
                sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u0434\u0435\u0439\u0441\u0442\u0432\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u0438\u043b\u0438 \u043f\u0440\u043e\u0441\u0440\u043e\u0447\u0435\u043d\u043d\u044b\u0439 \u0442\u043e\u043a\u0435\u043d."));
                return;
            }

            ServerPlayer sessionPlayer = plugin.getServer().getPlayerList().getPlayer(uuid);
            if (sessionPlayer == null || sessionPlayer.connection == null || !authManager.isPendingAuth(uuid)) {
                auditAuth(exchange, "status", null, uuid, "session_not_pending");
                authManager.getTokenToUuid().remove(token, uuid);
                sendJsonResponse(exchange, 410, createErrorJson("Сессия авторизации завершена или игрок вышел с сервера."));
                return;
            }

            boolean registered = authManager.getDatabase().isRegistered(uuid);
            String username = sessionPlayer.getGameProfile().name();
            auditAuth(exchange, "status", username, uuid, registered ? "registered" : "unregistered");
            String loginName = registered ? authManager.getLoginName(uuid) : null;

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("success", true);
            responseJson.addProperty("uuid", uuid.toString());
            responseJson.addProperty("username", username);
            responseJson.addProperty("registered", registered);
            AuthDatabase.AppearanceProfile appearance = authManager.getDatabase().getAppearanceProfile(uuid);
            if (appearance != null && authManager.getAppearanceManager().hasAppearance(uuid)) {
                responseJson.addProperty("appearanceModel", appearance.model());
                responseJson.addProperty("appearanceHash", appearance.hash());
                responseJson.addProperty("appearanceUrl", authManager.getAppearanceManager().textureUrl(uuid, appearance));
            }
            if (loginName != null) {
                responseJson.addProperty("loginName", loginName);
            }

            sendJsonResponse(exchange, 200, responseJson);
        }
    }

    /**
     * GET /api/client-session?username=...
     */
    private class ApiClientSessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
            String username = queryParams.get("username");

            if (username == null || username.isBlank()) {
                sendJsonResponse(exchange, 400, createErrorJson("Missing username."));
                return;
            }

            ServerPlayer player = plugin.getServer().getPlayerList().getPlayerByName(username);
            // SECURITY: never return the session URL (it embeds the bearer token) to an
            // unauthenticated caller. The token is delivered to the player's own game client over
            // the game connection; here we only report whether a login session is pending.
            if (player == null || authManager.getActiveAuthUrl(player) == null) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("success", true);
            responseJson.addProperty("pending", true);
            sendJsonResponse(exchange, 200, responseJson);
        }
    }

    /**
     * POST /api/login
     */
    private class ApiLoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            if (blockedByIp(exchange)) {
                auditAuth(exchange, "login", null, null, "rate_limited");
                sendJsonResponse(exchange, 429, createErrorJson("\u0421\u043b\u0438\u0448\u043a\u043e\u043c \u043c\u043d\u043e\u0433\u043e \u043f\u043e\u043f\u044b\u0442\u043e\u043a. \u041f\u043e\u0434\u043e\u0436\u0434\u0438\u0442\u0435 \u043d\u0435\u043c\u043d\u043e\u0433\u043e."));
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                String token = requestToken(exchange);
                if (token == null || token.isBlank()) {
                    token = json.has("token") && !json.get("token").isJsonNull()
                            ? json.get("token").getAsString() : "";
                }
                String loginName = json.get("loginName").getAsString().trim();
                String password = json.get("password").getAsString();
                boolean rememberDevice = json.has("rememberMe")
                        && !json.get("rememberMe").isJsonNull()
                        && json.get("rememberMe").getAsBoolean();

                // Bound the inputs before any PBKDF2 work: a huge password is a CPU DoS and an
                // absurd login is a log/DB abuse vector.
                if (loginName.isEmpty() || loginName.length() > 32
                        || password.isEmpty() || password.length() > 256) {
                    auditAuth(exchange, "login", loginName, null, "malformed_input");
                    sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u0432\u0435\u0440\u043d\u044b\u0435 \u0434\u0430\u043d\u043d\u044b\u0435 \u0432\u0445\u043e\u0434\u0430."));
                    return;
                }
                if (blockedByAccount(loginName)) {
                    auditAuth(exchange, "login", loginName, null, "rate_limited_account");
                    sendJsonResponse(exchange, 429, createErrorJson("\u0421\u043b\u0438\u0448\u043a\u043e\u043c \u043c\u043d\u043e\u0433\u043e \u043f\u043e\u043f\u044b\u0442\u043e\u043a \u0434\u043b\u044f \u044d\u0442\u043e\u0433\u043e \u0430\u043a\u043a\u0430\u0443\u043d\u0442\u0430. \u041f\u043e\u0434\u043e\u0436\u0434\u0438\u0442\u0435."));
                    return;
                }

                UUID uuid = authManager.getTokenToUuid().get(token);
                if (uuid == null) {
                    auditAuth(exchange, "login", loginName, null, "invalid_token");
                    sendJsonResponse(exchange, 400, createErrorJson("\u0412\u0440\u0435\u043c\u044f \u0441\u0435\u0441\u0441\u0438\u0438 \u0438\u0441\u0442\u0435\u043a\u043b\u043e. \u041f\u0435\u0440\u0435\u0437\u0430\u0439\u0434\u0438\u0442\u0435 \u0432 \u0438\u0433\u0440\u0443."));
                    return;
                }

                if (authManager.webLogin(uuid, loginName, password, rememberDevice)) {
                    resetAccountBudget(loginName);
                    auditAuth(exchange, "login", loginName, uuid, "success");
                    String rpName = authManager.getRpName(uuid);
                    JsonObject responseJson = new JsonObject();
                    responseJson.addProperty("success", true);
                    responseJson.addProperty("rpName", rpName);
                    sendJsonResponse(exchange, 200, responseJson);
                } else {
                    auditAuth(exchange, "login", loginName, uuid, "rejected");
                    sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u0432\u0435\u0440\u043d\u044b\u0439 \u043b\u043e\u0433\u0438\u043d \u0438\u043b\u0438 \u043f\u0430\u0440\u043e\u043b\u044c. \u041f\u043e\u043f\u0440\u043e\u0431\u0443\u0439\u0442\u0435 \u0435\u0449\u0435 \u0440\u0430\u0437."));
                }
            } catch (Exception e) {
                auditAuth(exchange, "login", null, null, "malformed_" + e.getClass().getSimpleName());
                sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u043a\u043e\u0440\u0440\u0435\u043a\u0442\u043d\u044b\u0439 \u0437\u0430\u043f\u0440\u043e\u0441."));
            }
        }
    }

    /**
     * POST /api/register
     */
    private class ApiRegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            if (blockedByIp(exchange)) {
                auditAuth(exchange, "register", null, null, "rate_limited");
                sendJsonResponse(exchange, 429, createErrorJson("\u0421\u043b\u0438\u0448\u043a\u043e\u043c \u043c\u043d\u043e\u0433\u043e \u043f\u043e\u043f\u044b\u0442\u043e\u043a. \u041f\u043e\u0434\u043e\u0436\u0434\u0438\u0442\u0435 \u043d\u0435\u043c\u043d\u043e\u0433\u043e."));
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                String token = requestToken(exchange);
                if (token == null || token.isBlank()) {
                    token = json.has("token") && !json.get("token").isJsonNull()
                            ? json.get("token").getAsString() : "";
                }
                String loginName = json.get("loginName").getAsString().trim();
                String rpName = json.get("rpName").getAsString().trim().replaceAll("\\s+", " ");
                String email = json.get("email").getAsString().trim();
                String password = json.get("password").getAsString();
                String appearanceData = json.has("appearanceData") && !json.get("appearanceData").isJsonNull()
                        ? json.get("appearanceData").getAsString()
                        : null;
                String appearanceModel = json.has("appearanceModel") && !json.get("appearanceModel").isJsonNull()
                        ? json.get("appearanceModel").getAsString()
                        : "classic";

                UUID uuid = authManager.getTokenToUuid().get(token);
                if (uuid == null) {
                    sendJsonResponse(exchange, 400, createErrorJson("\u0412\u0440\u0435\u043c\u044f \u0441\u0435\u0441\u0441\u0438\u0438 \u0438\u0441\u0442\u0435\u043a\u043b\u043e. \u041f\u0435\u0440\u0435\u0437\u0430\u0439\u0434\u0438\u0442\u0435 \u0432 \u0438\u0433\u0440\u0443."));
                    return;
                }

                // Check patterns on backend as well for security
                if (!loginName.matches("^[a-zA-Z0-9_]{4,16}$")) {
                    sendJsonResponse(exchange, 400, createErrorJson("\u041b\u043e\u0433\u0438\u043d \u0434\u043e\u043b\u0436\u0435\u043d \u0441\u043e\u0434\u0435\u0440\u0436\u0430\u0442\u044c 4-16 \u0441\u0438\u043c\u0432\u043e\u043b\u043e\u0432: \u043b\u0430\u0442\u0438\u043d\u0438\u0446\u0430, \u0446\u0438\u0444\u0440\u044b \u0438\u043b\u0438 \u043f\u043e\u0434\u0447\u0435\u0440\u043a\u0438\u0432\u0430\u043d\u0438\u0435."));
                    return;
                }

                // Server-side password policy: the browser rule is not a security boundary.
                if (password.length() < 8 || password.length() > 128) {
                    sendJsonResponse(exchange, 400, createErrorJson("\u041f\u0430\u0440\u043e\u043b\u044c \u0434\u043e\u043b\u0436\u0435\u043d \u0431\u044b\u0442\u044c \u043e\u0442 8 \u0434\u043e 128 \u0441\u0438\u043c\u0432\u043e\u043b\u043e\u0432."));
                    return;
                }

                if (appearanceData == null || appearanceData.isBlank()) {
                    sendJsonResponse(exchange, 400, createErrorJson("\u0417\u0430\u0433\u0440\u0443\u0437\u0438\u0442\u0435 \u043e\u0431\u043b\u0438\u043a \u043f\u0435\u0440\u0441\u043e\u043d\u0430\u0436\u0430 \u043f\u0435\u0440\u0435\u0434 \u0441\u043e\u0437\u0434\u0430\u043d\u0438\u0435\u043c \u043f\u0440\u043e\u0444\u0438\u043b\u044f."));
                    return;
                }

                // Cyrillic Firstname Lastname validation
                if (!rpName.matches("^([A-Z\\u0410-\\u042F\\u0401\\u0406\\u0404\\u0407][a-z\\u0430-\\u044F\\u0451\\u0456\\u0454\\u0457']+(-[A-Z\\u0410-\\u042F\\u0401\\u0406\\u0404\\u0407][a-z\\u0430-\\u044F\\u0451\\u0456\\u0454\\u0457']+)*)(\\s+([A-Z\\u0410-\\u042F\\u0401\\u0406\\u0404\\u0407][a-z\\u0430-\\u044F\\u0451\\u0456\\u0454\\u0457']+(-[A-Z\\u0410-\\u042F\\u0401\\u0406\\u0404\\u0407][a-z\\u0430-\\u044F\\u0451\\u0456\\u0454\\u0457']+)*))+$")) {
                    sendJsonResponse(exchange, 400, createErrorJson("\u0418\u043c\u044f \u043f\u0435\u0440\u0441\u043e\u043d\u0430\u0436\u0430 \u0434\u043e\u043b\u0436\u043d\u043e \u0431\u044b\u0442\u044c \u0432 \u0444\u043e\u0440\u043c\u0430\u0442\u0435: \u0418\u0432\u0430\u043d \u041f\u0435\u0442\u0440\u043e\u0432."));
                    return;
                }

                if (authManager.getDatabase().isLoginNameTaken(loginName)) {
                    sendJsonResponse(exchange, 400, createErrorJson("\u042d\u0442\u043e\u0442 \u043b\u043e\u0433\u0438\u043d \u0443\u0436\u0435 \u0437\u0430\u043d\u044f\u0442 \u0434\u0440\u0443\u0433\u0438\u043c \u0438\u0433\u0440\u043e\u043a\u043e\u043c."));
                    return;
                }

                if (authManager.getDatabase().isRpNameTaken(rpName)) {
                    sendJsonResponse(exchange, 400, createErrorJson("Это имя персонажа уже занято другим игроком."));
                    return;
                }

                if (authManager.webRegister(uuid, loginName, rpName, email, password, appearanceData, appearanceModel)) {
                    JsonObject responseJson = new JsonObject();
                    responseJson.addProperty("success", true);
                    sendJsonResponse(exchange, 200, responseJson);
                } else {
                    sendJsonResponse(exchange, 400, createErrorJson("\u041e\u0448\u0438\u0431\u043a\u0430 \u0440\u0435\u0433\u0438\u0441\u0442\u0440\u0430\u0446\u0438\u0438. \u041f\u0440\u043e\u0432\u0435\u0440\u044c\u0442\u0435 \u0432\u0432\u0435\u0434\u0435\u043d\u043d\u044b\u0435 \u0434\u0430\u043d\u043d\u044b\u0435."));
                }
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u043a\u043e\u0440\u0440\u0435\u043a\u0442\u043d\u044b\u0439 \u0437\u0430\u043f\u0440\u043e\u0441."));
            }
        }
    }

    /**
     * POST /api/recovery
     */
    private class ApiRecoveryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            if (blockedByIp(exchange)) {
                sendJsonResponse(exchange, 429, createErrorJson("\u0421\u043b\u0438\u0448\u043a\u043e\u043c \u043c\u043d\u043e\u0433\u043e \u043f\u043e\u043f\u044b\u0442\u043e\u043a. \u041f\u043e\u0434\u043e\u0436\u0434\u0438\u0442\u0435 \u043d\u0435\u043c\u043d\u043e\u0433\u043e."));
                return;
            }
            try {
                String body = readRequestBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                json.get("email").getAsString().trim(); // validate presence only

                // Generic response: never confirm whether an address is registered, never echo it
                // back (avoids account enumeration and reflective content).
                JsonObject responseJson = new JsonObject();
                responseJson.addProperty("success", true);
                responseJson.addProperty("message", "\u0415\u0441\u043b\u0438 \u0430\u043a\u043a\u0430\u0443\u043d\u0442 \u0441 \u0442\u0430\u043a\u043e\u0439 \u043f\u043e\u0447\u0442\u043e\u0439 \u0441\u0443\u0449\u0435\u0441\u0442\u0432\u0443\u0435\u0442, \u0438\u043d\u0441\u0442\u0440\u0443\u043a\u0446\u0438\u044f \u043e\u0442\u043f\u0440\u0430\u0432\u043b\u0435\u043d\u0430.");
                sendJsonResponse(exchange, 200, responseJson);
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u043a\u043e\u0440\u0440\u0435\u043a\u0442\u043d\u044b\u0439 \u0437\u0430\u043f\u0440\u043e\u0441."));
            }
        }
    }

    /**
     * GET /api/appearance/profile?uuid=...
     */
    private class ApiAppearanceProfileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
            String uuidRaw = queryParams.get("uuid");
            if (uuidRaw == null || uuidRaw.isBlank()) {
                sendJsonResponse(exchange, 400, createErrorJson("Missing uuid."));
                return;
            }

            try {
                UUID uuid = UUID.fromString(uuidRaw);
                AuthDatabase.AppearanceProfile appearance = authManager.getDatabase().getAppearanceProfile(uuid);
                JsonObject responseJson = new JsonObject();
                responseJson.addProperty("success", true);
                if (appearance != null && authManager.getAppearanceManager().hasAppearance(uuid)) {
                    responseJson.addProperty("hasAppearance", true);
                    responseJson.addProperty("model", appearance.model());
                    responseJson.addProperty("hash", appearance.hash());
                    responseJson.addProperty("updatedAt", appearance.updatedAt());
                    responseJson.addProperty("textureUrl", authManager.getAppearanceManager().textureUrl(uuid, appearance));
                    responseJson.addProperty("fallbackTextureUrl",
                            "/api/appearance/texture/" + uuid + ".png?v=" + appearance.hash());
                } else {
                    responseJson.addProperty("hasAppearance", false);
                }
                sendJsonResponse(exchange, 200, responseJson);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, createErrorJson("Invalid uuid."));
            }
        }
    }

    /** GET /api/appearance/edit-session?token=... */
    private class ApiAppearanceEditSessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }
            String token = requestToken(exchange);
            UUID uuid = authManager.getAppearanceEditOwner(token);
            if (uuid == null) {
                sendJsonResponse(exchange, 410, createErrorJson("Сеанс изменения внешности истёк. Выполните /skin ещё раз."));
                return;
            }
            AuthDatabase.AppearanceProfile profile = authManager.getDatabase().getAppearanceProfile(uuid);
            JsonObject json = new JsonObject();
            json.addProperty("success", true);
            json.addProperty("uuid", uuid.toString());
            json.addProperty("rpName", authManager.getRpName(uuid));
            if (profile != null && authManager.getAppearanceManager().hasAppearance(uuid)) {
                json.addProperty("model", profile.model());
                json.addProperty("textureUrl", authManager.getAppearanceManager().textureUrl(uuid, profile));
            }
            sendJsonResponse(exchange, 200, json);
        }
    }

    /** POST /api/appearance/update */
    private class ApiAppearanceUpdateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }
            try {
                JsonObject json = JsonParser.parseString(readRequestBody(exchange)).getAsJsonObject();
                String token = requestToken(exchange);
                if (token == null || token.isBlank()) {
                    token = json.has("token") && !json.get("token").isJsonNull()
                            ? json.get("token").getAsString() : "";
                }
                String model = json.has("appearanceModel") && !json.get("appearanceModel").isJsonNull()
                        ? json.get("appearanceModel").getAsString() : "classic";
                String data = json.has("appearanceData") && !json.get("appearanceData").isJsonNull()
                        ? json.get("appearanceData").getAsString() : "";
                AppearanceManager.SaveResult result = authManager.saveAppearanceEdit(token, model, data);
                if (!result.success()) {
                    sendJsonResponse(exchange, 400, createErrorJson(result.message()));
                    return;
                }
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("hash", result.hash());
                response.addProperty("model", result.model());
                sendJsonResponse(exchange, 200, response);
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("Не удалось сохранить внешний вид."));
            }
        }
    }

    /**
     * GET /api/appearance/texture/{uuid}.png
     */
    private class ApiAppearanceTextureHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String prefix = "/api/appearance/texture/";
            if (!path.startsWith(prefix) || !path.endsWith(".png")) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }

            String uuidRaw = path.substring(prefix.length(), path.length() - ".png".length());
            try {
                UUID uuid = UUID.fromString(uuidRaw);
                File file = authManager.getAppearanceManager().getAppearanceFile(uuid);
                if (!file.isFile()) {
                    sendResponse(exchange, 404, "text/plain", "404 Not Found");
                    return;
                }
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
                sendResponse(exchange, 200, "image/png", bytes);
            } catch (IllegalArgumentException e) {
                sendResponse(exchange, 400, "text/plain", "Invalid uuid");
            }
        }
    }

    // --- Utility Web Methods ---

    private boolean handleOptions(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                    "Content-Type, Authorization, X-Eclipse-Token");
            sendResponse(exchange, 204, "text/plain", "");
            return true;
        }
        return false;
    }

    /** The session token for this request: the header first, then a same-origin query fallback. */
    private String requestToken(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Eclipse-Token");
        if (header != null && !header.isBlank()) return header.trim();
        return parseQueryParams(exchange.getRequestURI().getQuery()).get("token");
    }

    private ApiJsonResult callOnMainThread(Callable<ApiJsonResult> task) throws Exception {
        if (Thread.currentThread() == plugin.getServer().getRunningThread()) {
            return task.call();
        }

        CompletableFuture<ApiJsonResult> future = new CompletableFuture<>();
        plugin.getServer().execute(() -> {
            try {
                future.complete(task.call());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future.get(3, TimeUnit.SECONDS);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        sendResponse(exchange, statusCode, contentType, bytes);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String contentType, byte[] content) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        // Same-origin pages need no CORS. Allow an explicitly allow-listed Origin, plus "null" —
        // the game client loads its CEF pages from file:// and calls the API cross-origin. Secrets
        // travel in the X-Eclipse-Token header, never the URL, so a cross-origin reader gains
        // nothing without a token.
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && (origin.equals("null")
                || allowedOrigins.contains(origin.toLowerCase(java.util.Locale.ROOT)))) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().add("Vary", "Origin");
        }
        if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(content.length));
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(statusCode, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, JsonObject json) throws IOException {
        sendResponse(exchange, statusCode, "application/json; charset=utf-8", gson.toJson(json));
    }

    private String contentTypeFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    private JsonObject createErrorJson(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("success", false);
        json.addProperty("message", message);
        return json;
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > MAX_REQUEST_BODY_BYTES) {
                    throw new IOException("Request body too large");
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the streaming cap below.
            }
        }
        try (InputStream in = exchange.getRequestBody();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > MAX_REQUEST_BODY_BYTES) {
                    throw new IOException("Request body too large");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private Map<String, String> parseQueryParams(String query) {
        Map<String, String> result = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            try {
                if (idx > 0 && pair.length() > idx + 1) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name());
                    String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name());
                    result.put(key, value);
                } else if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name());
                    result.put(key, "");
                }
            } catch (UnsupportedEncodingException ignored) {}
        }
        return result;
    }

    /**
     * GET /api/server-status
     */
    private class ApiServerStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            int onlinePlayers = plugin.getServer().getPlayerList().getPlayerCount();
            int maxPlayers = plugin.getServer().getMaxPlayers();

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("success", true);
            responseJson.addProperty("status", "online");
            responseJson.addProperty("onlinePlayers", onlinePlayers);
            responseJson.addProperty("maxPlayers", maxPlayers);

            sendJsonResponse(exchange, 200, responseJson);
        }
    }

    /** POST /api/body-session?username=... — issues a one-time body-page bootstrap code. */
    private class ApiBodySessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }
            Map<String, String> query = parseQueryParams(exchange.getRequestURI().getQuery());
            String username = query.get("username");
            String token = requestToken(exchange);
            ServerPlayer player = username == null ? null
                    : plugin.getServer().getPlayerList().getPlayerByName(username);
            if (player == null || !authorizeApiToken(player, token)) {
                sendJsonResponse(exchange, 403, createErrorJson("\u0414\u043e\u0441\u0442\u0443\u043f \u0437\u0430\u043f\u0440\u0435\u0449\u0451\u043d."));
                return;
            }
            String code = UUID.randomUUID().toString().replace("-", "");
            bodyCodes.put(code, new VitalsGrant(player.getUUID(),
                    System.currentTimeMillis() + BODY_CODE_TTL_MILLIS));
            JsonObject json = new JsonObject();
            json.addProperty("success", true);
            json.addProperty("code", code);
            sendJsonResponse(exchange, 200, json);
        }
    }

    /**
     * GET /api/vitals?username=...
     */
    private class ApiVitalsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
            String username = queryParams.get("username");
            if (username == null || username.isBlank()) {
                sendJsonResponse(exchange, 400, createErrorJson("Missing username"));
                return;
            }
            String token = requestToken(exchange);
            String cookie = readCookie(exchange, "eclipse_vitals");

            try {
                ApiJsonResult result = callOnMainThread(() -> {
                    ServerPlayer player = plugin.getServer().getPlayerList().getPlayerByName(username);
                    if (player == null) {
                        return new ApiJsonResult(404, createErrorJson("ServerPlayer is offline"));
                    }
                    if (authorizedVitalsUuid(player, token, cookie) == null) {
                        return new ApiJsonResult(403, createErrorJson("\u0414\u043e\u0441\u0442\u0443\u043f \u0437\u0430\u043f\u0440\u0435\u0449\u0451\u043d."));
                    }

                    JsonObject responseJson = staminaManager.toJson(player);
                    String rpName = authManager.getRpName(player.getUUID());
                    responseJson.addProperty("rpName", rpName != null && !rpName.isBlank() ? rpName : player.getGameProfile().name());
                    return new ApiJsonResult(200, responseJson);
                });
                sendJsonResponse(exchange, result.statusCode, result.json);
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, createErrorJson("Vitals request failed"));
            }
        }
    }

    /**
     * POST /api/vitals/treat
     */
    private class ApiVitalsTreatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleOptions(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            try {
                String body = readRequestBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String username = json.has("username") && !json.get("username").isJsonNull()
                        ? json.get("username").getAsString()
                        : "";
                String partId = json.has("partId") && !json.get("partId").isJsonNull()
                        ? json.get("partId").getAsString()
                        : "";
                String action = json.has("action") && !json.get("action").isJsonNull()
                        ? json.get("action").getAsString()
                        : "";
                String token = json.has("token") && !json.get("token").isJsonNull()
                        ? json.get("token").getAsString()
                        : null;
                String cookie = readCookie(exchange, "eclipse_vitals");

                if (username.isBlank() || partId.isBlank() || action.isBlank()) {
                    sendJsonResponse(exchange, 400, createErrorJson("Missing treatment parameters."));
                    return;
                }

                ApiJsonResult result = callOnMainThread(() -> {
                    ServerPlayer player = plugin.getServer().getPlayerList().getPlayerByName(username);
                    if (player == null) {
                        return new ApiJsonResult(404, createErrorJson("ServerPlayer is offline"));
                    }
                    if (authorizedVitalsUuid(player, token, cookie) == null) {
                        return new ApiJsonResult(403, createErrorJson("\u0414\u043e\u0441\u0442\u0443\u043f \u0437\u0430\u043f\u0440\u0435\u0449\u0451\u043d."));
                    }

                    JsonObject responseJson = staminaManager.startTreatment(player, partId, action);
                    int status = responseJson.has("success") && responseJson.get("success").getAsBoolean() ? 200 : 400;
                    return new ApiJsonResult(status, responseJson);
                });
                sendJsonResponse(exchange, result.statusCode, result.json);
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("Treatment failed"));
            }
        }
    }

    /**
     * Handler to serve client update files from the plugin client folder.
     */
    private class ClientDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }

            String path = exchange.getRequestURI().getPath(); // /client/mods/eclipseclient.jar
            // Safe substring to extract the relative path, then normalise the optional leading '/'.
            String relPath = path.startsWith("/client") ? path.substring("/client".length()) : path;
            if (relPath.startsWith("/")) relPath = relPath.substring(1);

            // Map to plugins/RPChat/client/ directory
            File clientDir = new File(plugin.getDataFolder(), "client");
            File file = new File(clientDir, relPath);

            // SECURITY: reject traversal ("../", "%2e%2e", backslashes) before touching the file
            // system, then verify the resolved path is really inside the client directory.
            if (relPath.isBlank() || relPath.contains("..") || relPath.contains("\\")) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }
            String rootPath = clientDir.getCanonicalPath() + File.separator;
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath) || !file.isFile()) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }

            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            sendResponse(exchange, 200, "application/octet-stream", bytes);
        }
    }

    /**
     * GET /api/required-mods
     */
    private class ApiRequiredModsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            File clientDir = new File(plugin.getDataFolder(), "client");
            File modsJsonFile = new File(clientDir, "mods.json");

            if (!modsJsonFile.exists()) {
                sendJsonResponse(exchange, 404, createErrorJson("Client manifest mods.json is missing."));
                return;
            }

            byte[] bytes = new byte[(int) modsJsonFile.length()];
            try (FileInputStream fis = new FileInputStream(modsJsonFile)) {
                int readBytes = fis.read(bytes);
                if (readBytes < bytes.length) {
                    plugin.getLogger().warning("Could not read entire mods.json file");
                }
            }

            sendResponse(exchange, 200, "application/json", bytes);
        }
    }

}
