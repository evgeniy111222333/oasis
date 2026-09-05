package ua.rp.chat.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

final class McefDiagnostics {
    private static final Logger LOGGER = LoggerFactory.getLogger("McefDiagnostics");
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

    static void registerHandlers(MCEFBrowser browser, String traceId) {
        if (browser == null) return;
        try {
            CefClient client = browser.getClient();
            if (client == null) {
                LOGGER.warn("[AUTH-DIAG][{}] CefClient is null, cannot register handlers", traceId);
                return;
            }

            // Register Load Handler to log page load success, errors, etc.
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
                    LOGGER.info("[AUTH-DIAG][{}] Load state: isLoading={}, canGoBack={}, canGoForward={}, url={}", 
                            traceId, isLoading, canGoBack, canGoForward, safeUrl(browser.getURL()));
                }

                @Override
                public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
                    LOGGER.error("[AUTH-DIAG][{}] Load error: code={}, text={}, failedUrl={}", 
                            traceId, errorCode, errorText, safeUrl(failedUrl));
                }
            });

            // Register Display Handler to log address changes and console messages
            client.addDisplayHandler(new CefDisplayHandlerAdapter() {
                @Override
                public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
                    LOGGER.info("[AUTH-DIAG][{}] URL redirected to: {}", traceId, safeUrl(url));
                }

                @Override
                public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
                    LOGGER.info("[AUTH-DIAG-JS][{}] {}:{} - [{}] {}", traceId, source, line, level, message);
                    return false; // Let default logging (if any) handle it too
                }
            });

            // Register Request Handler to log before navigation
            client.addRequestHandler(new CefRequestHandlerAdapter() {
                @Override
                public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean user_gesture, boolean is_redirect) {
                    LOGGER.info("[AUTH-DIAG][{}] Navigation request: url={}, userGesture={}, isRedirect={}", 
                            traceId, safeUrl(request.getURL()), user_gesture, is_redirect);
                    return false; // Do not cancel navigation
                }
            });

            // Register Life Span Handler to capture blocked popups (important!)
            client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
                @Override
                public boolean onBeforePopup(CefBrowser browser, CefFrame frame, String target_url, String target_frame_name) {
                    LOGGER.warn("[AUTH-DIAG][{}] BLOCKED POPUP BLOCKED BY CEF! target_url={}, frame_name={}", 
                            traceId, safeUrl(target_url), target_frame_name);
                    return false; // Continue with default popup blocking
                }
            });

            LOGGER.info("[AUTH-DIAG][{}] Registered display, load, request, and lifespan handlers successfully.", traceId);
        } catch (Throwable t) {
            LOGGER.error("[AUTH-DIAG][{}] Failed to register diagnostics handlers", traceId, t);
        }
    }
}
