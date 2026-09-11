package ua.rp.chat.carver;

/**
 * Seamless, deterministic value noise for the carving geology. Continuous across cell
 * boundaries (trilinear value noise with smoothstep interpolation), seeded per workpiece, so
 * bedding planes, growth rings and crystal domains flow as continuous structure instead of the
 * per-cell static a plain hash produces. Pure and dependency-free.
 *
 * <p>Mirror contract: client-only helper, no server copy exists by design.</p>
 */
public final class CarverNoise {
    private CarverNoise() {
    }

    /** Trilinear value noise in {@code [0, 1)}. Continuous in x, y and z. Pure. */
    public static double value3(long seed, double x, double y, double z) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        int z0 = fastFloor(z);
        double fx = x - x0;
        double fy = y - y0;
        double fz = z - z0;
        double ux = fade(fx);
        double uy = fade(fy);
        double uz = fade(fz);
        double c000 = hash(seed, x0, y0, z0);
        double c100 = hash(seed, x0 + 1, y0, z0);
        double c010 = hash(seed, x0, y0 + 1, z0);
        double c110 = hash(seed, x0 + 1, y0 + 1, z0);
        double c001 = hash(seed, x0, y0, z0 + 1);
        double c101 = hash(seed, x0 + 1, y0, z0 + 1);
        double c011 = hash(seed, x0, y0 + 1, z0 + 1);
        double c111 = hash(seed, x0 + 1, y0 + 1, z0 + 1);
        double x00 = lerp(c000, c100, ux);
        double x10 = lerp(c010, c110, ux);
        double x01 = lerp(c001, c101, ux);
        double x11 = lerp(c011, c111, ux);
        return lerp(lerp(x00, x10, uy), lerp(x01, x11, uy), uz);
    }

    /** Fractal sum of {@code value3} over {@code octaves}. Pure. */
    public static double fbm3(long seed, double x, double y, double z, int octaves) {
        double sum = 0.0;
        double amplitude = 0.5;
        double normalizer = 0.0;
        double frequency = 1.0;
        int octave = Math.max(1, Math.min(6, octaves));
        for (int i = 0; i < octave; i++) {
            sum += amplitude * value3(seed + i * 0x9E3779B97F4A7C15L,
                    x * frequency, y * frequency, z * frequency);
            normalizer += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return normalizer <= 0.0 ? 0.0 : sum / normalizer;
    }

    /** Stable hash of one integer lattice corner into {@code [0, 1)}. Pure. */
    private static double hash(long seed, int x, int y, int z) {
        long h = seed;
        h = h * 0x9E3779B97F4A7C15L + x * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 30;
        h = h * 0x94D049BB133111EBL + y * 0xD6E8FEB86659FD93L;
        h ^= h >>> 27;
        h = h * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        return (h >>> 11) / (double) (1L << 53);
    }

    private static double fade(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static int fastFloor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
