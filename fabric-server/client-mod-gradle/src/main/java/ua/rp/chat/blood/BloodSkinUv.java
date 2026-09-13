package ua.rp.chat.blood;

import java.util.List;

/**
 * Converts a semantic wound location to the canonical 64x64 player-skin UV.
 * Coordinates intentionally target both the body and clothing layer, so a
 * wound never disappears behind a jacket, sleeve or trouser overlay.
 */
public final class BloodSkinUv {
    public static final int FRONT = 0;
    public static final int BACK = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;

    private BloodSkinUv() {
    }

    public static List<Point> points(int zone, int face, float side, float height) {
        Cuboid base = switch (zone) {
            case 0 -> new Cuboid(0, 0, 8, 8, 8);
            case 1 -> new Cuboid(16, 16, 8, 12, 4);
            case 2 -> new Cuboid(32, 48, 4, 12, 4);
            case 3 -> new Cuboid(40, 16, 4, 12, 4);
            case 4 -> new Cuboid(16, 48, 4, 12, 4);
            case 5 -> new Cuboid(0, 16, 4, 12, 4);
            default -> null;
        };
        Cuboid overlay = switch (zone) {
            case 0 -> new Cuboid(32, 0, 8, 8, 8);
            case 1 -> new Cuboid(16, 32, 8, 12, 4);
            case 2 -> new Cuboid(48, 48, 4, 12, 4);
            case 3 -> new Cuboid(40, 32, 4, 12, 4);
            case 4 -> new Cuboid(0, 48, 4, 12, 4);
            case 5 -> new Cuboid(0, 32, 4, 12, 4);
            default -> null;
        };
        if (base == null) return List.of();
        return List.of(point(base, face, side, height), point(overlay, face, side, height));
    }

    private static Point point(Cuboid box, int face, float side, float height) {
        int safeFace = Math.max(FRONT, Math.min(RIGHT, face));
        Rect rect = switch (safeFace) {
            case BACK -> new Rect(box.u + box.depth * 2 + box.width, box.v + box.depth,
                    box.width, box.height);
            case LEFT -> new Rect(box.u + box.depth + box.width, box.v + box.depth,
                    box.depth, box.height);
            case RIGHT -> new Rect(box.u, box.v + box.depth, box.depth, box.height);
            default -> new Rect(box.u + box.depth, box.v + box.depth, box.width, box.height);
        };
        float horizontal = BloodFxRules.clamp01(side * 0.5f + 0.5f);
        float vertical = 1.0f - BloodFxRules.clamp01(height);
        int x = rect.x + Math.min(rect.width - 1, Math.round(horizontal * (rect.width - 1)));
        int y = rect.y + Math.min(rect.height - 1, Math.round(vertical * (rect.height - 1)));
        return new Point(x, y, rect);
    }

    public record Point(int x, int y, Rect face) {
    }

    public record Rect(int x, int y, int width, int height) {
    }

    private record Cuboid(int u, int v, int width, int height, int depth) {
    }
}
