package ua.rp.chat.projectile;

/**
 * Calibration for the direct Blockbench Block/Item export.
 *
 * <p>The authored arrow points from the tip towards the fletching along local {@code +Z}.
 * Coordinates stay exactly as exported; renderers position either the geometric centre (flight)
 * or the physical tip (an embedded projectile) without rewriting cubes or UVs.</p>
 */
public final class DirectArrowGeometry {
    public static final float SOURCE_CENTER_X = 9.5f / 16.0f;
    public static final float SOURCE_CENTER_Y = 11.5f / 16.0f;
    public static final float SOURCE_TIP_Z = -11.1f / 16.0f;
    public static final float SOURCE_TAIL_Z = 23.3f / 16.0f;
    public static final float SOURCE_CENTER_Z = (SOURCE_TIP_Z + SOURCE_TAIL_Z) * 0.5f;
    public static final float TARGET_LENGTH_BLOCKS = 0.90f;
    public static final float MODEL_SCALE =
            TARGET_LENGTH_BLOCKS / (SOURCE_TAIL_Z - SOURCE_TIP_Z);

    private DirectArrowGeometry() {
    }
}
