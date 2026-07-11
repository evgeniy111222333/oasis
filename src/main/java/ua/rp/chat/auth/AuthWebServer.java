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
                sendJsonResponse(exchange, 400, createErrorJson("\u041e\u0442\u0441\u0443\u0442\u0441\u0442\u0432\u0443\u0435\u0442 \u0442\u043e\u043a\u0435\u043d \u0430\u0432\u0442\u043e\u0440\u0438\u0437\u0430\u0446\u0438\u0438."));
                return;
            }

            UUID uuid = authManager.getTokenToUuid().get(token);
            if (uuid == null) {
                sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u0434\u0435\u0439\u0441\u0442\u0432\u0438\u0442\u0435\u043b\u044c\u043d\u044b\u0439 \u0438\u043b\u0438 \u043f\u0440\u043e\u0441\u0440\u043e\u0447\u0435\u043d\u043d\u044b\u0439 \u0442\u043e\u043a\u0435\u043d."));
                return;
            }

            Player sessionPlayer = plugin.getServer().getPlayer(uuid);
            if (sessionPlayer == null || !sessionPlayer.isOnline() || !authManager.isPendingAuth(uuid)) {
                authManager.getTokenToUuid().remove(token, uuid);
                sendJsonResponse(exchange, 410, createErrorJson("Сессия авторизации завершена или игрок вышел с сервера."));
                return;
            }

            boolean registered = authManager.getDatabase().isRegistered(uuid);
            String username = sessionPlayer.getName();
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
                    sendJsonResponse(exchange, 400, createErrorJson("\u0412\u0440\u0435\u043c\u044f \u0441\u0435\u0441\u0441\u0438\u0438 \u0438\u0441\u0442\u0435\u043a\u043b\u043e. \u041f\u0435\u0440\u0435\u0437\u0430\u0439\u0434\u0438\u0442\u0435 \u0432 \u0438\u0433\u0440\u0443."));
                    return;
                }

                if (authManager.webLogin(uuid, loginName, password, rememberDevice)) {
                    String rpName = authManager.getRpName(uuid);
                    JsonObject responseJson = new JsonObject();
                    responseJson.addProperty("success", true);
                    responseJson.addProperty("rpName", rpName);
                    sendJsonResponse(exchange, 200, responseJson);
                } else {
                    sendJsonResponse(exchange, 400, createErrorJson("\u041d\u0435\u0432\u0435\u0440\u043d\u044b\u0439 \u043b\u043e\u0433\u0438\u043d \u0438\u043b\u0438 \u043f\u0430\u0440\u043e\u043b\u044c. \u041f\u043e\u043f\u0440\u043e\u0431\u0443\u0439\u0442\u0435 \u0435\u0449\u0435 \u0440\u0430\u0437."));
                }
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("\u041e\u0448\u0438\u0431\u043a\u0430 \u043e\u0431\u0440\u0430\u0431\u043e\u0442\u043a\u0438 \u0437\u0430\u043f\u0440\u043e\u0441\u0430: " + e.getMessage()));
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
                sendJsonResponse(exchange, 400, createErrorJson("\u041e\u0448\u0438\u0431\u043a\u0430 \u043e\u0431\u0440\u0430\u0431\u043e\u0442\u043a\u0438: " + e.getMessage()));
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
                responseJson.addProperty("message", "\u041a\u043e\u0434 \u0432\u043e\u0441\u0441\u0442\u0430\u043d\u043e\u0432\u043b\u0435\u043d\u0438\u044f \u043e\u0442\u043f\u0440\u0430\u0432\u043b\u0435\u043d \u043d\u0430 \u043f\u043e\u0447\u0442\u0443 " + email + "!");
                sendJsonResponse(exchange, 200, responseJson);
            } catch (Exception e) {
                sendJsonResponse(exchange, 400, createErrorJson("\u041e\u0448\u0438\u0431\u043a\u0430: " + e.getMessage()));
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

            String path = exchange.getRequestURI().getPath(); // /client/mods/eclipseclient.jar
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
