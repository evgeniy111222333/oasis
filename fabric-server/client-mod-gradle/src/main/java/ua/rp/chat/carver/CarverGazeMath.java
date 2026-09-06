package ua.rp.chat.carver;

import java.util.List;

public final class CarverGazeMath {
    public static final double TAU_YAW = 0.12;
    public static final double TAU_PITCH = 0.15;
    public static final double PEEK_IN = 0.15;
    public static final double PEEK_HOLD = 0.45;
    public static final double PEEK_OUT = 0.60;
    public static final double ENTRY_TICKS = 28.0;

    private CarverGazeMath() {
    }

    public static double smoothstep(double u) {
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1.0;
        return u * u * (3.0 - 2.0 * u);
    }

    public static double tickAlpha(double dTicks, double tau) {
        if (!(dTicks > 0.0) || !(tau > 0.0)) return 0.0;
        double ticks = Math.max(0.0, Math.min(3.0, dTicks));
        if (ticks <= 0.0) return 0.0;
        return 1.0 - Math.exp(-ticks * 0.05 / tau);
    }

    public static double entryBlend(double smoothTicks) {
        if (smoothTicks <= 0.0) return 0.0;
        if (smoothTicks >= ENTRY_TICKS) return 1.0;
        return smoothstep(smoothTicks / ENTRY_TICKS);
    }

    public static boolean isPeekStrike(int strikeIndex, long uuidLow) {
        if (strikeIndex < 0) return false;
        int slot = (int) ((strikeIndex + (uuidLow & 3L)) & 3L);
        return slot == 2;
    }

    public static double peekWeight(double cycleT) {
        double t = cycleT - Math.floor(cycleT);
        if (t < PEEK_IN || t >= PEEK_OUT) return 0.0;
        if (t < 0.25) return smoothstep((t - PEEK_IN) / 0.10);
        if (t < PEEK_HOLD) return 1.0;
        return 1.0 - smoothstep((t - PEEK_HOLD) / (PEEK_OUT - PEEK_HOLD));
    }

    public static double nodRadians(double cycleT) {
        double t = cycleT - Math.floor(cycleT);
        if (t < 0.90) return 0.0;
        return Math.sin((t - 0.90) / 0.10 * Math.PI) * Math.toRadians(1.25);
    }

    public static double swayYawRadians(double breathPhase) {
        double p = breathPhase - Math.floor(breathPhase);
        return Math.toRadians(0.4) * Math.sin(p * 2.0 * Math.PI);
    }

    public static double swayPitchRadians(double breathPhase) {
        double p = breathPhase - Math.floor(breathPhase);
        return Math.toRadians(0.3) * Math.sin(p * 2.0 * Math.PI + 1.3);
    }

    public static double wrapDelta(double delta) {
        while (delta > Math.PI) delta -= Math.PI * 2.0;
        while (delta < -Math.PI) delta += Math.PI * 2.0;
        return delta;
    }

    public static double[] peekWorld(int focusX, int focusY, int focusZ,
                                     double[] contactCells, DraftMask mask,
                                     int axis, int variant) {
        double[] fallback = fallbackPeek(focusX, focusY, focusZ, contactCells, axis, variant);
        if (mask == null || mask.isEmpty() || contactCells == null) return fallback;
        try {
            int[] dir = peekDir(axis, variant);
            List<Integer> cells = mask.cells();
            double bestScore = Double.NEGATIVE_INFINITY;
            int best = -1;
            for (int cell : cells) {
                double dx = DraftMask.x(cell) + 0.5 - contactCells[0];
                double dy = DraftMask.y(cell) + 0.5 - contactCells[1];
                double dz = DraftMask.z(cell) + 0.5 - contactCells[2];
                double along = dx * dir[0] + dy * dir[1] + dz * dir[2];
                if (along < 1.5 || along > 5.0) continue;
                double lateral = Math.sqrt(Math.max(0.0,
                        dx * dx + dy * dy + dz * dz - along * along));
                double score = along - lateral * 1.5;
                if (score > bestScore) {
                    bestScore = score;
                    best = cell;
                }
            }
            if (best < 0) {
                for (int cell : cells) {
                    double dx = DraftMask.x(cell) + 0.5 - contactCells[0];
                    double dy = DraftMask.y(cell) + 0.5 - contactCells[1];
                    double dz = DraftMask.z(cell) + 0.5 - contactCells[2];
                    double along = dx * dir[0] + dy * dir[1] + dz * dir[2];
                    if (along <= 0.0) continue;
                    double lateral = Math.sqrt(Math.max(0.0,
                            dx * dx + dy * dy + dz * dz - along * along));
                    double score = along - lateral * 2.0;
                    if (score > bestScore) {
                        bestScore = score;
                        best = cell;
                    }
                }
            }
            if (best < 0) return fallback;
            return new double[]{
                    focusX + (DraftMask.x(best) + 0.5) / 16.0,
                    focusY + (DraftMask.y(best) + 0.5) / 16.0,
                    focusZ + (DraftMask.z(best) + 0.5) / 16.0};
        } catch (RuntimeException unreadable) {
            return fallback;
        }
    }

    private static double[] fallbackPeek(int focusX, int focusY, int focusZ,
                                         double[] contactCells, int axis, int variant) {
        int[] dir = peekDir(axis, variant);
        double baseX = contactCells == null ? 8.0 : contactCells[0];
        double baseY = contactCells == null ? 8.0 : contactCells[1];
        double baseZ = contactCells == null ? 8.0 : contactCells[2];
        return new double[]{
                focusX + (baseX + dir[0] * 4.8) / 16.0,
                focusY + (baseY + dir[1] * 4.8) / 16.0,
                focusZ + (baseZ + dir[2] * 4.8) / 16.0};
    }

    private static int[] peekDir(int axis, int variant) {
        int v = Math.floorMod(variant, 4);
        if (axis == 0) {
            return switch (v) {
                case 0 -> new int[]{0, 0, 1};
                case 1 -> new int[]{0, 0, -1};
                case 2 -> new int[]{0, 1, 0};
                default -> new int[]{0, 1, 1};
            };
        }
        if (axis == 2) {
            return switch (v) {
                case 0 -> new int[]{1, 0, 0};
                case 1 -> new int[]{1, 1, 0};
                case 2 -> new int[]{-1, 0, 0};
                default -> new int[]{0, 1, 0};
            };
        }
        return switch (v) {
            case 0 -> new int[]{1, 0, 0};
            case 1 -> new int[]{0, 0, 1};
            case 2 -> new int[]{-1, 0, 0};
            default -> new int[]{0, 0, -1};
        };
    }
}
