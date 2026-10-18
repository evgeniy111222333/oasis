package ua.rp.chat.microvoxel;

/**
 * Positive revision arithmetic with an explicit Integer.MAX_VALUE -&gt; 1 wrap.
 *
 * <p>Mirror contract: duplicated verbatim in the client module. Serial comparison treats
 * {@code MAX_VALUE -&gt; 1} as the only forward wrap; the reverse ({@code 1 -&gt; MAX_VALUE})
 * is stale, never newer, so replayed pre-wrap packets cannot resurrect old state.</p>
 */
public final class MicrovoxelRevision {
    private MicrovoxelRevision() {
    }

    public static int next(int revision) {
        return revision >= Integer.MAX_VALUE ? 1 : Math.max(1, revision + 1);
    }

    public static boolean isImmediateNext(int candidate, int current) {
        return candidate == next(current);
    }

    public static boolean isNewer(int candidate, int current) {
        if (candidate == current) return false;
        if (current == Integer.MAX_VALUE) return candidate == 1;
        if (candidate == Integer.MAX_VALUE && current == 1) return false;
        return candidate > current;
    }
}
