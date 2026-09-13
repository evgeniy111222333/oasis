package ua.rp.chat;

import ua.rp.chat.client.carver.CarverDustCore;
import ua.rp.chat.client.carver.CarverDustScreen;

/**
 * Guards the pure shape of the screen-space dust field: the depth bands, per-mote size and
 * alpha curves and the lifetime fade must all stay inside the ranges the renderer assumes,
 * so no single mote can ever wash the frame out again.
 */
public final class CarverDustScreenTest {
    public static void main(String[] args) {
        require(CarverDustScreen.depthBand(0.0f) == 0.0f
                        && CarverDustScreen.depthBand(1.0f) == 1.0f,
                "Depth band must pin its endpoints");
        float previous = -1.0f;
        for (int step = 0; step <= 100; step++) {
            float depth = CarverDustScreen.depthBand(step / 100.0f);
            require(depth >= previous && depth >= 0.0f && depth <= 1.0f,
                    "Depth band must be monotonic inside 0..1, got " + depth + " at " + step);
            previous = depth;
        }
        require(CarverDustScreen.depthBand(-5.0f) == 0.0f
                        && CarverDustScreen.depthBand(5.0f) == 1.0f,
                "Depth band must clamp out-of-range rolls");

        previous = -1.0f;
        for (int step = 0; step <= 100; step++) {
            float depth = step / 100.0f;
            float size = CarverDustScreen.sizeForDepth(depth);
            float alpha = CarverDustScreen.alphaForDepth(depth);
            require(size >= 0.006f && size <= 0.072f,
                    "Mote size must stay small at depth " + depth + ", got " + size);
            require(alpha >= 0.10f && alpha <= 0.48f,
                    "Mote alpha must stay see-through at depth " + depth + ", got " + alpha);
            require(size >= previous - 1.0e-6f,
                    "Mote size must grow with depth");
            previous = size;
        }

        require(Math.abs(CarverDustScreen.lifeFade(0.0f)) < 1.0e-6f
                        && Math.abs(CarverDustScreen.lifeFade(1.0f)) < 1.0e-6f
                        && Math.abs(CarverDustScreen.lifeFade(0.5f) - 1.0f) < 1.0e-6f,
                "Lifetime fade must rise to one and fall back to zero");
        require(Math.abs(CarverDustScreen.lifeFade(-1.0f)) < 1.0e-6f
                        && Math.abs(CarverDustScreen.lifeFade(2.0f)) < 1.0e-6f,
                "Lifetime fade must clamp outside its domain");

        require(CarverDustScreen.lighten(0x00FFF0, 40) == 0x28FFFF
                        && CarverDustScreen.lighten(0xFFFFFF, 40) == 0xFFFFFF,
                "Lighten must raise channels and clamp at white");
        require(CarverDustScreen.darken(0xFFFFFF, 40) == 0xD7D7D7
                        && CarverDustScreen.darken(0x000000, 40) == 0x000000,
                "Darken must drop channels and clamp at black");

        System.out.println("CarverDustScreenTest: parallax bands, size, alpha, fade passed");

        require(CarverDustCore.puffCount() >= 10, "The cloud needs enough puffs to read as volume");
        for (int index = 0; index < CarverDustCore.puffCount(); index++) {
            double half = CarverDustCore.puffHalf(index);
            require(half > 0.3 && half < 1.2,
                    "Puff half-size must stay local, got " + half + " at " + index);
            require(CarverDustCore.puffAlpha(index, 0.0f, 0.0f) == 0.0f,
                    "A cold cloud must be fully transparent");
            require(CarverDustCore.puffAlpha(index, 1.0f, 0.0f) <= 0.62f,
                    "A puff must never wash the frame out");
        }
        float previousAlpha = -1.0f;
        for (int step = 0; step <= 20; step++) {
            float alpha = CarverDustCore.puffAlpha(0, step / 20.0f, 0.0f);
            require(alpha >= previousAlpha, "Puff alpha must grow with the work ramp");
            previousAlpha = alpha;
        }
        require(CarverDustCore.puffAlpha(0, 1.0f, 1.0f)
                        > CarverDustCore.puffAlpha(0, 1.0f, 0.0f),
                "A strike must kick the cloud brighter");
        require(CarverDustCore.puffAlpha(0, 1.0f, 1.0f) <= 0.62f,
                "Even a strike must respect the alpha ceiling");
        System.out.println("CarverDustCoreTest: cloud volume, ramp and strike kick passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
