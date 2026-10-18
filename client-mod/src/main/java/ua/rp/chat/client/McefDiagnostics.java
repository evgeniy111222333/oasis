package ua.rp.chat.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

final class McefDiagnostics {
    private static final AtomicInteger NEXT_TRACE_ID = new AtomicInteger();

    private McefDiagnostics() {
    }

    static String nextTraceId(String kind) {
        return kind + "-" + NEXT_TRACE_ID.incrementAndGet();
    }

    static String safeUrl(String value) {
        if (value == null || value.isBlank()) {
            return "<empty>";
        }
        try {
            URI uri = URI.create(value);
            String query = uri.getRawQuery();
            if (query == null || query.isBlank()) {
                return value;
            }
            StringBuilder safeQuery = new StringBuilder();
            for (String part : query.split("&")) {
                if (!safeQuery.isEmpty()) {
                    safeQuery.append('&');
                }
                int equals = part.indexOf('=');
                String name = equals < 0 ? part : part.substring(0, equals);
                safeQuery.append(name);
                if (equals >= 0) {
                    safeQuery.append('=').append("token".equalsIgnoreCase(name) ? "<redacted>" : part.substring(equals + 1));
                }
            }
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), safeQuery.toString(), uri.getFragment()).toString();
        } catch (IllegalArgumentException | URISyntaxException ignored) {
            return "<invalid-url>";
        }
    }

    static String browserState(MCEFBrowser browser) {
        if (browser == null) {
            return "mcefInitialized=" + MCEF.isInitialized() + ", browser=null";
        }
        try {
            boolean ready = browser.isTextureReady();
            String texture = String.valueOf(browser.getTextureIdentifier());
            int glId = browser.getRenderer().getTextureID();
            int textureWidth = browser.getRenderer().getTextureWidth();
            int textureHeight = browser.getRenderer().getTextureHeight();
            return "mcefInitialized=" + MCEF.isInitialized()
                    + ", browser=" + Integer.toHexString(System.identityHashCode(browser))
                    + ", textureReady=" + ready
                    + ", texture=" + texture
                    + ", glId=" + glId
                    + ", textureSize=" + textureWidth + "x" + textureHeight
                    + ", transparent=" + browser.getRenderer().isTransparent();
        } catch (Throwable t) {
            return "mcefInitialized=" + MCEF.isInitialized()
                    + ", browser=" + Integer.toHexString(System.identityHashCode(browser))
                    + ", stateError=" + t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }
}
