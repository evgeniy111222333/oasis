package ua.rp.chat.auth;

import java.awt.image.BufferedImage;

/**
 * Guards the legacy skin conversion and its one-time migration: a 64x32 skin and a 64x64 file
 * the old converter left with blank left limbs must both be repaired by mirroring the right
 * limbs, while a genuine modern skin must pass through untouched.
 */
public final class AppearanceLegacySkinTest {
    public static void main(String[] args) {
        // --- A raw 64x32 legacy skin expands and mirrors the left limbs. ---
        BufferedImage legacy = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        fill(legacy, 40, 16, 16, 16, 0xFF112233); // right arm base
        fill(legacy, 0, 16, 16, 16, 0xFF445566);  // right leg base
        fill(legacy, 16, 16, 24, 16, 0xFF778899); // body

        BufferedImage modern = AppearanceManager.toModernSkin(legacy);
        require(modern.getWidth() == 64 && modern.getHeight() == 64,
                "A legacy skin must be expanded to 64x64");
        require(modern.getRGB(32, 48) == 0xFF112233,
                "The left arm must mirror the right arm, not stay blank");
        require(modern.getRGB(16, 48) == 0xFF445566,
                "The left leg must mirror the right leg, not stay blank");
        require(modern.getRGB(16, 16) == 0xFF778899,
                "The legacy body pixels must be preserved");

        // --- A 64x64 file the old converter left with a blank bottom half is detected+repaired. ---
        BufferedImage broken = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(broken, 40, 16, 16, 16, 0xFFAA0101);
        fill(broken, 0, 16, 16, 16, 0xFFAA0202);
        require(AppearanceManager.isBrokenLegacySkin(broken),
                "A blank-bottom legacy file must be detected as broken");
        BufferedImage repaired = AppearanceManager.toModernSkin(broken);
        require(repaired.getRGB(32, 48) == 0xFFAA0101,
                "The migration must restore the left arm");
        require(repaired.getRGB(16, 48) == 0xFFAA0202,
                "The migration must restore the left leg");

        // --- A genuine modern skin (left limbs opaque) is never touched. ---
        BufferedImage valid = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        fill(valid, 0, 16, 16, 16, 0xFF010203);   // right leg
        fill(valid, 16, 48, 16, 16, 0xFF040506);  // left leg present
        fill(valid, 32, 48, 16, 16, 0xFF070809);  // left arm present
        require(!AppearanceManager.isBrokenLegacySkin(valid),
                "A valid modern skin must not be flagged for migration");
        require(AppearanceManager.toModernSkin(valid) == valid,
                "A valid modern skin must be returned untouched");

        System.out.println("AppearanceLegacySkinTest: legacy mirror conversion passed");
    }

    private static void fill(BufferedImage image, int x, int y, int width, int height, int argb) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                image.setRGB(x + column, y + row, argb);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("AppearanceLegacySkinTest: " + message);
        }
    }

    private AppearanceLegacySkinTest() {
    }
}
