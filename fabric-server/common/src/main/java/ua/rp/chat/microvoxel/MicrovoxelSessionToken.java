package ua.rp.chat.microvoxel;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-login anti-replay token for the microvoxel action channel.
 *
 * <p>The server issues a fresh random token on every {@code HELLO} and delivers it in
 * {@code HELLO_ACK}. Every replay-sensitive action (a mutation, or undo/redo) must present the
 * current token, so a packet captured in an earlier session — or before a server restart — is
 * rejected even though its monotonic transaction id would otherwise look fresh. The monotonic id
 * still guards ordering and duplicates <em>within</em> a session; the token guards the session
 * boundary.</p>
 *
 * <p>The previous token stays valid so a client that re-handshakes mid-session (a full resync)
 * does not have its in-flight actions rejected in the window before the new {@code HELLO_ACK}
 * arrives. Pure and dependency-free, so the accept rule is unit-tested without a server.</p>
 */
public final class MicrovoxelSessionToken {
    /** "No token": an unauthenticated or pre-handshake action always presents this and is rejected. */
    public static final long NONE = 0L;

    private MicrovoxelSessionToken() {
    }

    /** A fresh non-zero token. Zero is reserved for {@link #NONE}. */
    public static long fresh() {
        long value;
        do {
            value = ThreadLocalRandom.current().nextLong();
        } while (value == NONE);
        return value;
    }

    /**
     * True when {@code presented} is the live token or the immediately previous one. {@link #NONE}
     * is never accepted, so an old client that cannot present a token is rejected rather than
     * silently trusted.
     */
    public static boolean matches(long current, long previous, long presented) {
        if (presented == NONE) return false;
        return presented == current || (previous != NONE && presented == previous);
    }
}
