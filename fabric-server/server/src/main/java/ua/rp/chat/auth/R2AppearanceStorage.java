package ua.rp.chat.auth;

import ua.rp.chat.SimpleConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

public final class R2AppearanceStorage {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withLocale(Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final Logger logger;
    private final boolean enabled;
    private final boolean required;
    private final String bucket;
    private final String endpoint;
    private final String publicBaseUrl;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;

    private R2AppearanceStorage(
            Logger logger,
            boolean enabled,
            boolean required,
            String bucket,
            String endpoint,
            String publicBaseUrl,
            String accessKeyId,
            String secretAccessKey,
            String region
    ) {
        this.httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        this.logger = logger;
        this.enabled = enabled;
        this.required = required;
        this.bucket = trimSlashes(bucket);
        this.endpoint = trimTrailingSlash(endpoint);
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl);
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.region = region == null || region.isBlank() ? "auto" : region.trim();
    }

    public static R2AppearanceStorage fromConfig(SimpleConfig config, Logger logger) {
        String provider = config.getString("appearance.storage.provider", "local");
        boolean enabled = "r2".equalsIgnoreCase(provider);
        if (!enabled) {
            return disabled(logger);
        }

        String bucket = resolveEnvironmentValue(config.getString("appearance.storage.r2.bucket", ""));
        String endpoint = resolveEnvironmentValue(config.getString("appearance.storage.r2.endpoint", ""));
        String publicBaseUrl = resolveEnvironmentValue(config.getString("appearance.storage.r2.public-url", ""));
        String accessKeyId = resolveEnvironmentValue(config.getString("appearance.storage.r2.access-key-id", ""));
        String secretAccessKey = resolveEnvironmentValue(config.getString("appearance.storage.r2.secret-access-key", ""));
        boolean required = config.getBoolean("appearance.storage.r2.required", false);
        String region = config.getString("appearance.storage.r2.region", "auto");

        if (bucket.isBlank() || endpoint.isBlank() || publicBaseUrl.isBlank()
                || accessKeyId.isBlank() || secretAccessKey.isBlank()) {
            logger.warning("R2 appearance storage is selected but not fully configured. Falling back to local storage.");
            return disabled(logger);
        }

        logger.info("Appearance storage: Cloudflare R2 bucket " + bucket + " via " + publicBaseUrl);
        return new R2AppearanceStorage(logger, true, required, bucket, endpoint, publicBaseUrl,
                accessKeyId, secretAccessKey, region);
    }

    private static R2AppearanceStorage disabled(Logger logger) {
        return new R2AppearanceStorage(logger, false, false, "", "", "", "", "", "auto");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRequired() {
        return required;
    }

    public String bucket() {
        return bucket;
    }

    public String endpoint() {
        return endpoint;
    }

    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    public String accessKeyId() {
        return accessKeyId;
    }

    public String secretAccessKey() {
        return secretAccessKey;
    }

    public String region() {
        return region;
    }

    public static record UploadResult(String key, String publicUrl) {}

    public UploadResult uploadSkin(UUID uuid, String hash, byte[] pngBytes) throws IOException, InterruptedException {
        return uploadWithKey("skins/" + uuid + "/" + hash + ".png", pngBytes);
    }

    public UploadResult uploadCharacterSkin(String characterKey, String hash, byte[] pngBytes) throws IOException, InterruptedException {
        if (!enabled) throw new IllegalStateException("R2 storage is disabled.");
        String safeCharacterKey = characterKey == null || characterKey.isBlank() ? "unknown" : characterKey.trim();
        return uploadWithKey("skins/characters/" + safeCharacterKey + "/" + hash + ".png", pngBytes);
    }

    private UploadResult uploadWithKey(String key, byte[] pngBytes) throws IOException, InterruptedException {
        if (!enabled) throw new IllegalStateException("R2 storage is disabled.");
        URI uri = URI.create(endpoint + "/" + urlEncodePath(bucket) + "/" + urlEncodePath(key));
        String host = uri.getHost();
        String canonicalUri = "/" + urlEncodePath(bucket) + "/" + urlEncodePath(key);
        String payloadHash = sha256Hex(pngBytes);
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = DATE_STAMP.format(now);
        String credentialScope = dateStamp + "/" + region + "/s3/aws4_request";
        String signedHeaders = "cache-control;content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalHeaders = "cache-control:public, max-age=31536000, immutable\n"
                + "content-type:image/png\n"
                + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + amzDate + "\n";
        String canonicalRequest = "PUT\n" + canonicalUri + "\n\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = signingKey(secretAccessKey, dateStamp, region, "s3");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        HttpRequest request = HttpRequest.newBuilder(uri)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(pngBytes))
                .header("Authorization", authorization)
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .header("Content-Type", "image/png")
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .timeout(java.time.Duration.ofSeconds(8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            logger.warning("R2 upload failed for " + key + ": HTTP " + code + " "
                    + (response.body() == null ? "" : response.body()));
            throw new IOException("R2 upload failed with HTTP " + code);
        }
        return new UploadResult(key, publicUrl(key));
    }

    public String publicUrl(String key) {
        return publicBaseUrl + "/" + urlEncodePath(key);
    }

    private static String sha256Hex(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not found", e);
        }
    }

    private static String resolveEnvironmentValue(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String varName = trimmed.substring(2, trimmed.length() - 1);
            String val = System.getenv(varName);
            return val != null ? val : "";
        }
        return trimmed;
    }

    private static String trimSlashes(String s) {
        if (s == null) return "";
        return s.replaceAll("^/+", "").replaceAll("/+$", "").trim();
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.replaceAll("/+$", "").trim();
    }

    private static String urlEncodePath(String path) {
        String[] parts = path.split("/");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result.append('/');
            result.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return result.toString();
    }

    private static byte[] signingKey(String secret, String dateStamp, String region, String service) {
        byte[] dateKey = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] dateRegionKey = hmac(dateKey, region);
        byte[] dateRegionServiceKey = hmac(dateRegionKey, service);
        return hmac(dateRegionServiceKey, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
