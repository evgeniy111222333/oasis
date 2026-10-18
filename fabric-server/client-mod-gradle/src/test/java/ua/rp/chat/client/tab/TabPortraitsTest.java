package ua.rp.chat.client.tab;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Standalone checks for portrait refresh work and the visible-page retention budget. */
public final class TabPortraitsTest {
    public static void main(String[] args) {
        firstRefreshAndDeadline();
        changedPageRefreshesImmediately();
        mutationCannotEscapeThrottle();
        retentionIsBounded();
        resetAndInvalidationRefreshImmediately();
        System.out.println("TabPortraitsTest: 500ms refresh, page changes, cache budget and lifecycle passed");
    }

    private static void firstRefreshAndDeadline() {
        TabPortraits.RefreshWindow window = new TabPortraits.RefreshWindow();
        List<UUID> ids = List.of(new UUID(0, 1));
        require(window.begin(ids, 0), "A first visible page must resolve even at nanosecond zero");
        require(!window.begin(ids, 1), "A following frame must not resolve skins");
        require(!window.begin(ids, 499_999_999L), "Skins must stay cached until the refresh deadline");
        require(window.begin(ids, 500_000_000L), "Skins must resolve at the 500ms boundary");
        require(!window.begin(ids, 500_000_001L), "A refresh must start a new throttle interval");
    }

    private static void changedPageRefreshesImmediately() {
        TabPortraits.RefreshWindow window = new TabPortraits.RefreshWindow();
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        require(window.begin(List.of(first), 10), "First page must resolve");
        require(window.begin(List.of(second), 11), "A changed page cannot wait behind the previous page's timer");
        require(window.visible().equals(List.of(second)), "Only current-page IDs may remain retained");
        require(window.begin(List.of(), 12), "An emptied roster must discard the old page immediately");
        require(window.visible().isEmpty(), "An empty roster must retain no player IDs");
    }

    private static void mutationCannotEscapeThrottle() {
        TabPortraits.RefreshWindow window = new TabPortraits.RefreshWindow();
        ArrayList<UUID> mutable = new ArrayList<>(List.of(new UUID(0, 1)));
        window.begin(mutable, 0);
        mutable.set(0, new UUID(0, 2));
        require(window.begin(mutable, 1), "Changing a reused page list must resolve the new player immediately");
        mutable.clear();
        require(window.visible().equals(List.of(new UUID(0, 2))), "Cached IDs must not alias caller-owned mutable storage");
    }

    private static void retentionIsBounded() {
        TabPortraits.RefreshWindow window = new TabPortraits.RefreshWindow();
        ArrayList<UUID> large = new ArrayList<>();
        for (int i = 1; i <= 400; i++) large.add(new UUID(0, i));
        require(window.begin(large, 0), "Oversized input must still resolve a bounded visible page");
        require(window.visible().size() == 12, "Portrait resolution must retain at most twelve visible IDs");
        require(window.visible().getLast().equals(new UUID(0, 12)), "Off-page IDs must never enter portrait resolution");
        large.set(399, new UUID(0, 401));
        require(!window.begin(large, 1), "An off-page change must not schedule portrait resolution");
    }

    private static void resetAndInvalidationRefreshImmediately() {
        TabPortraits.RefreshWindow window = new TabPortraits.RefreshWindow();
        List<UUID> ids = List.of(new UUID(0, 1));
        window.begin(ids, -900_000_000L);
        require(!window.begin(ids, -899_999_999L), "Negative nanoTime values must still obey the throttle");
        window.invalidate();
        require(window.begin(ids, -899_999_998L), "Appearance/reload invalidation must bypass the timer");
        window.reset();
        require(window.visible().isEmpty(), "Disconnect/reset must release all retained player IDs");
        require(window.begin(ids, -899_999_997L), "A new connection must resolve its first page immediately");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
