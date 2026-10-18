package ua.rp.chat;

import ua.rp.chat.client.tab.TabAnimation;
import ua.rp.chat.client.tab.TabLayout;
import ua.rp.chat.client.tab.TabRoster;
import ua.rp.chat.client.tab.TabText;
import ua.rp.chat.client.tab.TabClock;
import ua.rp.chat.client.tab.TabPortraitsTest;
import ua.rp.chat.client.tab.TabTextRasterTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Exercises viewport coverage, live roster churn, Unicode labels and real-time transitions. */
public final class CompactTabTest {
    public static void main(String[] args) {
        layoutCoverage();
        rosterUpdates();
        unicode();
        animation();
        clock();
        TabPortraitsTest.main(args);
        TabTextRasterTest.main(args);
        System.out.println("CompactTabTest: viewport/pagination, stable caching, Unicode, FPS-independent fade passed");
    }

    private static void layoutCoverage() {
        int[] widths = {160, 240, 320, 426, 640, 854, 1280, 1920, 3440};
        int[] heights = {120, 180, 240, 360, 480, 720, 1080, 1440};
        int[] counts = {0, 1, 2, 3, 4, 11, 12, 13, 18, 36, 64, 100, 257, 1000};
        for (int width : widths) for (int height : heights) for (int count : counts) {
            TabLayout first = TabLayout.calculate(width, height, count, 0);
            Set<Integer> seen = new HashSet<>();
            for (int offset = 0; offset < Math.max(1, count); offset += first.capacity()) {
                TabLayout l = TabLayout.calculate(width, height, count, offset);
                require(l.x() >= 0 && l.x() + l.width() <= width, "Horizontal screen overflow");
                require(l.y() >= 0 && l.y() + l.height() <= height, "Vertical screen overflow");
                require(l.capacity() == first.capacity() && l.pages() == first.pages(), "Page capacity must not shrink on last page");
                for (int i = 0; i < l.visible(); i++) {
                    seen.add(l.index(i));
                    for (int j = 0; j < i; j++) require(l.index(i) != l.index(j), "Character duplicated on the same orbit");
                    require(l.column(i) < l.columns(), "Player outside columns");
                    require(l.textX(i) >= l.x() && l.textX(i) + l.nameCapacity() <= l.x() + l.width(), "Name overflow");
                    require(l.textY(i) + 18 * l.nameScale() <= l.y() + l.height(), "Name outside vertical viewport");
                    float fixed = l.spokeLength() + 2 * l.nodeGap() + l.headSize() + l.nameCapacity();
                    double angle = Math.toRadians(l.tilt(i));
                    float reach = (float)(Math.abs(Math.cos(angle)) * fixed + Math.abs(Math.sin(angle)) * l.headSize());
                    require(l.anchorX(i) - reach >= 0 && l.anchorX(i) + reach <= width,
                            "A tilted character strip must stay inside the viewport");
                    if (i > 0 && l.left(i) == l.left(i-1)) {
                        require(Math.abs(l.anchorY(i) - l.anchorY(i-1)) >= l.headSize() + 2,
                                "Neighboring portraits must not overlap on a small screen");
                    }
                }
            }
            require(seen.size() == count, "Pagination loses players");
            require(TabLayout.calculate(width, height, count, -1).first() == (count > first.capacity() ? count-1 : 0),
                    "Orbit scrolling must wrap and small rosters must stay on their only page");
        }
        TabLayout accepted = TabLayout.calculate(854, 480, 12, 0);
        require(accepted.columns() == 2 && accepted.rows() == 5 && accepted.pages() == 2,
                "Astrolabe must place five characters on each of two arcs, then show the remaining roster by scrolling");
        require(TabLayout.calculate(160, 120, 12, 0).capacity() < 10, "Small screens must reduce visible arc slots");
        require(TabLayout.calculate(1920, 1080, 12, 0).radius() == 265,
                "Full-HD must preserve the original HTML astrolabe dimensions");
        require(TabLayout.calculate(1280, 720, 12, 0).radius() >= 250,
                "Ordinary desktop windows must not unnecessarily shrink the reference assembly");
    }

    private static void rosterUpdates() {
        TabRoster roster = new TabRoster();
        UUID self = new UUID(0, 9);
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        List<TabRoster.Entry> input = new ArrayList<>(List.of(
                new TabRoster.Entry(b, "Незнайомець", false), new TabRoster.Entry(self, "Остап", true),
                new TabRoster.Entry(a, "Незнайомець", false)));
        require(roster.replace(input, true), "First packet must update roster");
        require(roster.entries().getFirst().id().equals(self), "Self must stay first");
        require(roster.entries().get(1).id().equals(a), "Identical labels need deterministic UUID tie-break");
        List<TabRoster.Entry> cached = roster.entries();
        long revision = roster.revision();
        Collections.reverse(input);
        for (int i = 0; i < 1000; i++) require(!roster.replace(input, true), "Unchanged packets must not invalidate");
        require(roster.entries() == cached && roster.revision() == revision, "Unchanged packets must preserve cached snapshot");
        input.clear();
        require(roster.entries().size() == 3, "Caller must not mutate cached roster");
        roster.replace(List.of(new TabRoster.Entry(a, "Человек в закрытом шлеме", false)), true);
        require(roster.entries().getFirst().label().equals("Человек в закрытом шлеме"), "Masked server label must remain intact");
        require(roster.entries().size() == 1, "Disconnected players must disappear");
        roster.replace(List.of(), true);
        require(roster.authoritative() && roster.entries().isEmpty(), "Empty authoritative roster must not expose fallback identities");
        roster.clear();
        require(!roster.authoritative() && roster.entries().isEmpty(), "Disconnect must clear authority and names");
    }

    private static void unicode() {
        require(TabText.clean("§cОстап\nРовена\u202E").equals("Остап Ровена"), "Control codes must not inject new rows or bidi overrides");
        require(TabText.clean("a".repeat(1000)).length() == 128, "Labels need bounded CPU/memory cost");
        require(new TabRoster.Entry(new UUID(0, 0), "", false).label().equals("Незнакомец"), "Empty label fallback");
        String name = "Остап😀Ровена";
        for (int width = 0; width < 30; width++) {
            String cut = TabText.ellipsize(name, width, s -> s.codePointCount(0, s.length()));
            require(cut.codePointCount(0, cut.length()) <= width, "Truncation exceeds available width");
            for (int i = 0; i < cut.length(); i++) {
                char c = cut.charAt(i);
                if (Character.isHighSurrogate(c)) require(++i < cut.length() && Character.isLowSurrogate(cut.charAt(i)), "Broken Unicode pair");
                else require(!Character.isLowSurrogate(c), "Orphan low surrogate");
            }
        }
        int[] calls = {0};
        TabText.ellipsize("Мандрівник".repeat(12), 40, s -> { calls[0]++; return s.length(); });
        require(calls[0] <= 10, "Truncation should use logarithmic, not per-character measurement");
    }

    private static void animation() {
        for (int fps : new int[]{15, 30, 60, 144, 240}) {
            TabAnimation animation = new TabAnimation();
            long start = 1_000_000_000L;
            float previous = 0;
            for (int frame = 0; frame <= Math.ceil(fps * 0.90); frame++) {
                float opacity = animation.advance(true, start + Math.round(frame * 1_000_000_000.0 / fps));
                require(opacity >= previous && opacity <= 1, "Opening must be monotonic at " + fps + " FPS");
                previous = opacity;
            }
            require(previous == 1, "Astrolabe must finish its 850ms unfold at " + fps + " FPS");
            long close = start + 950_000_000L;
            for (int frame = 0; frame <= Math.ceil(fps * 0.70); frame++) {
                float opacity = animation.advance(false, close + Math.round(frame * 1_000_000_000.0 / fps));
                require(opacity <= previous && opacity >= 0, "Closing must be monotonic");
                previous = opacity;
            }
            require(!animation.visible(), "Closing must clear visibility");
        }
        TabAnimation reversed = new TabAnimation();
        reversed.advance(true, 1_000_000_000L);
        float opened = reversed.advance(true, 1_050_000_000L);
        float closed = reversed.advance(false, 1_060_000_000L);
        require(closed < opened, "Fast release must reverse animation immediately");
        require(reversed.advance(true, 1_070_000_000L) > closed, "Fast re-press must reverse smoothly");
        reversed.reset();
        require(!reversed.visible(), "Screen/focus changes must clear animation immediately");
        reversed.advance(true, 0);
        float unfolding = reversed.advance(true, 200_000_000L);
        require(unfolding > 0 && unfolding < 1, "Concept rings must still be unfolding after 200ms");
    }

    private static void clock() {
        require(TabClock.of(0).label().equals("Day 1 - Dawn"), "Dawn must use the actual game day");
        require(TabClock.of(2999).phase().equals("Dawn") && TabClock.of(3000).phase().equals("High Noon"), "Noon boundary");
        require(TabClock.of(10999).phase().equals("High Noon") && TabClock.of(11000).phase().equals("Eventide"), "Eventide boundary");
        require(TabClock.of(13999).phase().equals("Eventide") && TabClock.of(14000).phase().equals("Midnight"), "Midnight boundary");
        require(TabClock.of(23000).phase().equals("Dawn") && TabClock.of(24000).day() == 2, "Day rollover");
        require(TabClock.of(-1).day() == 1 && TabClock.of(-1).phase().equals("Dawn"), "Negative clock robustness");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
