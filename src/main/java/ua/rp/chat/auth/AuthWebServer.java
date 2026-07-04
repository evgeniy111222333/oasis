package ua.rp.chat.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ua.rp.chat.vitals.StaminaManager;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Embedded HTTP server for serving the auth UI and processing REST API endpoints.
 */
public class AuthWebServer {

    private final JavaPlugin plugin;
    private final AuthManager authManager;
    private final StaminaManager staminaManager;
    private HttpServer server;
    private final Gson gson = new Gson();

    private static final class ApiJsonResult {
        private final int statusCode;
        private final JsonObject json;

        private ApiJsonResult(int statusCode, JsonObject json) {
            this.statusCode = statusCode;
            this.json = json;
        }
    }

    public AuthWebServer(JavaPlugin plugin, AuthManager authManager, StaminaManager staminaManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.staminaManager = staminaManager;
    }

    public void start(int port) {
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // Route web UI page
            server.createContext("/auth", new StaticFileHandler("index.html", "text/html; charset=utf-8"));
            server.createContext("/", new StaticFileHandler("index.html", "text/html; charset=utf-8"));
            server.createContext("/index.html", new StaticFileHandler("index.html", "text/html; charset=utf-8"));
            server.createContext("/style.css", new StaticFileHandler("style.css", "text/css; charset=utf-8"));
            server.createContext("/app.js", new StaticFileHandler("app.js", "application/javascript; charset=utf-8"));
            server.createContext("/body", new StaticFileHandler("body.html", "text/html; charset=utf-8"));
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
            server.createContext("/api/server-status", new ApiServerStatusHandler());
            server.createContext("/api/vitals", new ApiVitalsHandler());
            server.createContext("/api/vitals/treat", new ApiVitalsTreatHandler());
            server.createContext("/api/required-mods", new ApiRequiredModsHandler());
            server.createContext("/client", new ClientDownloadHandler());

            server.setExecutor(null); // default executor
            server.start();
            plugin.getLogger().info("Auth Web Server started on port " + port);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to start Auth Web Server: " + e.getMessage(), e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
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
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");
            
            File webDir = new File(plugin.getDataFolder(), "web");
            File file = new File(webDir, filename);

            if (!file.exists()) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }

            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(bytes);
            }

            sendResponse(exchange, 200, contentType, bytes);
        }
    }

    private class WebAssetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.getResponseHeaders().set("Pragma", "no-cache");
            exchange.getResponseHeaders().set("Expires", "0");

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

            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int readBytes = fis.read(bytes);
                if (readBytes < bytes.length) {
                    plugin.getLogger().warning("Could not read entire web asset: " + file.getName());
                }
            }

            sendResponse(exchange, 200, contentTypeFor(file.getName()), bytes);
        }
    }

    /**
     * GET /api/status?token=...
     */
    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                sendJsonResponse(exchange, 405, createErrorJson("Method Not Allowed"));
                return;
            }

            Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getQuery());
            String token = queryParams.get("token");

            if (token == null || token.isEmpty()) {
                sendJsonResponse(exchange, 400, createErrorJson("РћС‚СЃСѓС‚СЃС‚РІСѓРµС‚ С‚РѕРєРµРЅ Р°РІС‚РѕСЂРёР·Р°С†РёРё."));
                return;
            }

            UUID uuid = authManager.getTokenToUuid().get(token);
            if (uuid == null) {
                sendJsonResponse(exchange, 400, createErrorJson("РќРµРґРµР№СЃС‚РІРёС‚РµР»СЊРЅС‹Р№ РёР»Рё РїСЂРѕСЃСЂРѕС‡РµРЅРЅС‹Р№ С‚РѕРєРµРЅ."));
                return;
            }

            boolean registered = authManager.getDatabase().isRegistered(uuid);
            String username = plugin.getServer().getPlayer(uuid).getName();
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

            Player player = plugin.getServer().getPlayerExact(username);
            String authUrl = authManager.getActiveAuthUrl(player);
            if (authUrl == null) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("success", true);
            responseJson.addProperty("authUrl", authUrl);
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

            try {
                String body = readRequestBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                String token = json.get("token").getAsString();
                String loginName = json.get("loginName").getAsString().trim();
                String password = json.get("password").getAsString();
                boolean rememberDevice = json.has("rememberMe")
                        && !json.get("rememberMe").isJsonNull()
                        && json.get("rememberMe").getAsBoolean();

                UUID uuid = authManager.getTokenToUuid().get(token);
                if (uuid == null) {
                    sendJsonResponse(exchange, 400, createErrorJson("Р’СЂРµРјСЏ СЃРµСЃСЃРёРё РёСЃС‚РµРєР»Рѕ. РџРµСЂРµР·Р°Р№РґРёС‚Рµ РІ РёРіСЂСѓ."));
                    return;
                }

                if (authManager.webLogin(uuid, loginName, password, rememberDevice)) {
                    String rpName = authManager.getRpName(uuid);
                    JsonObject responseJson = new JsonObject();
                    responseJson.addProperty("success", true);
                    responseJson.addProperty("rpName", rpName);
                    sendJsonResponse(exchange, 200, responseJson);
                } else {
                    sendJsonResponse(exchange, 400, createErrorJson("РќРµРІРµСЂРЅС‹Р№ Р»РѕРіРёРЅ РёР»Рё РїР°СЂРѕР»СЊ. РџРѕРїСЂРѕР±СѓР№С‚Рµ РµС‰Рµ СЂР°Р·."));
                }
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("РћС€РёР±РєР° РѕР±СЂР°Р±РѕС‚РєРё Р·Р°РїСЂРѕСЃР°: " + e.getMessage()));
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

            try {
                String body = readRequestBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();

                String token = json.get("token").getAsString();
                String loginName = json.get("loginName").getAsString().trim();
                String rpName = json.get("rpName").getAsString().trim();
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
                    sendJsonResponse(exchange, 400, createErrorJson("Р’СЂРµРјСЏ СЃРµСЃСЃРёРё РёСЃС‚РµРєР»Рѕ. РџРµСЂРµР·Р°Р№РґРёС‚Рµ РІ РёРіСЂСѓ."));
                    return;
                }

                // Check patterns on backend as well for security
                if (!loginName.matches("^[a-zA-Z0-9_]{4,16}$")) {
                    sendJsonResponse(exchange, 400, createErrorJson("Р›РѕРіРёРЅ РґРѕР»Р¶РµРЅ СЃРѕРґРµСЂР¶Р°С‚СЊ 4-16 СЃРёРјРІРѕР»РѕРІ: Р»Р°С‚РёРЅРёС†Р°, С†РёС„СЂС‹ РёР»Рё РїРѕРґС‡РµСЂРєРёРІР°РЅРёРµ."));
                    return;
                }

                // Cyrillic Firstname Lastname validation
                if (!rpName.matches("^[A-ZРђ-РЇРЃ][a-zР°-СЏС‘']+\\s+[A-ZРђ-РЇРЃ][a-zР°-СЏС‘']+$")) {
                    sendJsonResponse(exchange, 400, createErrorJson("РРјСЏ РїРµСЂСЃРѕРЅР°Р¶Р° РґРѕР»Р¶РЅРѕ Р±С‹С‚СЊ РІ С„РѕСЂРјР°С‚Рµ: РРІР°РЅ РџРµС‚СЂРѕРІ."));
                    return;
                }

                if (authManager.getDatabase().isLoginNameTaken(loginName)) {
                    sendJsonResponse(exchange, 400, createErrorJson("Р­С‚РѕС‚ Р»РѕРіРёРЅ СѓР¶Рµ Р·Р°РЅСЏС‚ РґСЂСѓРіРёРј РёРіСЂРѕРєРѕРј."));
                    return;
                }

                if (authManager.webRegister(uuid, loginName, rpName, email, password, appearanceData, appearanceModel)) {
                    JsonObject responseJson = new JsonObject();
                    responseJson.addProperty("success", true);
                    sendJsonResponse(exchange, 200, responseJson);
                } else {
                    sendJsonResponse(exchange, 400, createErrorJson("РћС€РёР±РєР° СЂРµРіРёСЃС‚СЂР°С†РёРё. РџСЂРѕРІРµСЂСЊС‚Рµ РІРІРµРґРµРЅРЅС‹Рµ РґР°РЅРЅС‹Рµ."));
                }
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("РћС€РёР±РєР° РѕР±СЂР°Р±РѕС‚РєРё: " + e.getMessage()));
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

            try {
                String body = readRequestBody(exchange);
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                String email = json.get("email").getAsString().trim();

                // Mock recovery logic
                JsonObject responseJson = new JsonObject();
                responseJson.addProperty("success", true);
                responseJson.addProperty("message", "РљРѕРґ РІРѕСЃСЃС‚Р°РЅРѕРІР»РµРЅРёСЏ РѕС‚РїСЂР°РІР»РµРЅ РЅР° РїРѕС‡С‚Сѓ " + email + "!");
                sendJsonResponse(exchange, 200, responseJson);
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("РћС€РёР±РєР°: " + e.getMessage()));
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
                } else {
                    responseJson.addProperty("hasAppearance", false);
                }
                sendJsonResponse(exchange, 200, responseJson);
            } catch (IllegalArgumentException e) {
                sendJsonResponse(exchange, 400, createErrorJson("Invalid uuid."));
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
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
            sendResponse(exchange, 204, "text/plain", "");
            return true;
        }
        return false;
    }

    private ApiJsonResult callOnMainThread(Callable<ApiJsonResult> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }

        CompletableFuture<ApiJsonResult> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
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
        // Enable CORS in development if needed
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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
        try (BufferedReader br = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
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

            int onlinePlayers = plugin.getServer().getOnlinePlayers().size();
            int maxPlayers = plugin.getServer().getMaxPlayers();

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("success", true);
            responseJson.addProperty("status", "online");
            responseJson.addProperty("onlinePlayers", onlinePlayers);
            responseJson.addProperty("maxPlayers", maxPlayers);

            sendJsonResponse(exchange, 200, responseJson);
        }
    }

    /**
     * GET /api/vitals?username=...
     */
    private class ApiVitalsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
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

            try {
                ApiJsonResult result = callOnMainThread(() -> {
                    Player player = plugin.getServer().getPlayerExact(username);
                    if (player == null) {
                        return new ApiJsonResult(404, createErrorJson("Player is offline"));
                    }

                    JsonObject responseJson = staminaManager.toJson(player);
                    String rpName = authManager.getRpName(player.getUniqueId());
                    responseJson.addProperty("rpName", rpName != null && !rpName.isBlank() ? rpName : player.getName());
                    return new ApiJsonResult(200, responseJson);
                });
                sendJsonResponse(exchange, result.statusCode, result.json);
            } catch (Exception e) {
                sendJsonResponse(exchange, 500, createErrorJson("Vitals request failed: " + e.getMessage()));
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

                if (username.isBlank() || partId.isBlank() || action.isBlank()) {
                    sendJsonResponse(exchange, 400, createErrorJson("Missing treatment parameters."));
                    return;
                }

                ApiJsonResult result = callOnMainThread(() -> {
                    Player player = plugin.getServer().getPlayerExact(username);
                    if (player == null) {
                        return new ApiJsonResult(404, createErrorJson("Player is offline"));
                    }

                    JsonObject responseJson = staminaManager.startTreatment(player, partId, action);
                    int status = responseJson.has("success") && responseJson.get("success").getAsBoolean() ? 200 : 400;
                    return new ApiJsonResult(status, responseJson);
                });
                sendJsonResponse(exchange, result.statusCode, result.json);
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("Treatment failed: " + e.getMessage()));
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

            String path = exchange.getRequestURI().getPath(); // /client/mods/oasisauth.jar
            // Safe substring to extract the relative path
            String relPath = path.startsWith("/client") ? path.substring("/client".length()) : path;
            
            // Map to plugins/RPChat/client/ directory
            File clientDir = new File(plugin.getDataFolder(), "client");
            File file = new File(clientDir, relPath);

            if (!file.exists() || file.isDirectory()) {
                sendResponse(exchange, 404, "text/plain", "404 Not Found");
                return;
            }

            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                int readBytes = fis.read(bytes);
                if (readBytes < bytes.length) {
                    plugin.getLogger().warning("Could not read entire file: " + file.getName());
                }
            }

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
                writeDefaultModsJson(modsJsonFile);
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

    private void writeDefaultModsJson(File file) {
        try {
            file.getParentFile().mkdirs();
            String defaultContent = "[\n" +
                    "  { \"name\": \"oasisauth-1.0.0.jar\", \"path\": \"mods/oasisauth-1.0.0.jar\", \"sha1\": \"c3d307fb95478ff0393e9abdf70269832248e035\", \"size\": 540772 },\n" +
                    "  { \"name\": \"mcef_fabric_2.2.0_MC_26.1.1.jar\", \"path\": \"mods/mcef_fabric_2.2.0_MC_26.1.1.jar\", \"sha1\": \"3168366b5cfce5302a53635674dcee443bb7eeca\", \"size\": 453664 },\n" +
                    "  { \"name\": \"fabric-api-0.153.0+26.1.2.jar\", \"path\": \"mods/fabric-api-0.153.0+26.1.2.jar\", \"sha1\": \"5d984764e54f1f1db397d3f76429a0f15e591845\", \"size\": 2504357 },\n" +
                    "  { \"name\": \"fabric-language-kotlin-1.13.12+kotlin.2.4.0.jar\", \"path\": \"mods/fabric-language-kotlin-1.13.12+kotlin.2.4.0.jar\", \"sha1\": \"2bc17bb4275cc70a12e4ac35d139a71a30845720\", \"size\": 8076848 },\n" +
                    "  { \"name\": \"yet_another_config_lib_v3-3.9.5+26.1-fabric.jar\", \"path\": \"mods/yet_another_config_lib_v3-3.9.5+26.1-fabric.jar\", \"sha1\": \"dd0b7f266eced755bb48d5213df309f07d71de5b\", \"size\": 1121083 },\n" +
                    "  { \"name\": \"sodium-fabric-0.8.12+mc26.1.2.jar\", \"path\": \"mods/sodium-fabric-0.8.12+mc26.1.2.jar\", \"sha1\": \"cd6c6236f0dcff03c7148414db220de32c934b5a\", \"size\": 1844226 },\n" +
                    "  { \"name\": \"iris-fabric-1.10.9+mc26.1.1.jar\", \"path\": \"mods/iris-fabric-1.10.9+mc26.1.1.jar\", \"sha1\": \"c30e04509a1b284372cb9037b07714d4223ae91a\", \"size\": 2803860 },\n" +
                    "  { \"name\": \"zoomify-2.16.1+26.1.jar\", \"path\": \"mods/zoomify-2.16.1+26.1.jar\", \"sha1\": \"c180ae8cf90da1abd67c26b5c5e7bf5d795c3b1d\", \"size\": 561967 },\n" +
                    "  { \"name\": \"entity_texture_features_26.1-fabric-7.1.jar\", \"path\": \"mods/entity_texture_features_26.1-fabric-7.1.jar\", \"sha1\": \"ff6284b53ad23e06bc082d1e05e8828e47455126\", \"size\": 740706 },\n" +
                    "  { \"name\": \"entity_model_features-3.2.4-26.1-fabric.jar\", \"path\": \"mods/entity_model_features-3.2.4-26.1-fabric.jar\", \"sha1\": \"7a43e5c92b87e360bfa0156870f2097549e3732d\", \"size\": 577617 }\n" +
                    "]";
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(defaultContent);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to write default mods.json: " + e.getMessage());
        }
    }
}
