package ua.rp.chat.stonemason;

/**
 * Built-in carving patterns for the stonemason drafting table.
 *
 * <p>Every template is a deterministic removal mask over the 16x16x16 block volume:
 * y=0 is the bottom of the block, y=15 its top face. All math is integer and
 * order-independent, so server and client always compute the identical mask.</p>
 *
 * <p>Pure and dependency-free: safe to unit-test.</p>
 *
 * <p>Mirror contract: duplicated verbatim in the paired module. Keep both copies
 * byte-identical; {@code verifyStonemasonParity} fails the build on divergence.</p>
 */
public final class DraftTemplates {
    public static final int BATH = 1;
    public static final int BASIN = 2;
    public static final int COLUMN = 3;

    private DraftTemplates() {
    }

    public static boolean isTemplate(int templateId) {
        return templateId == BATH || templateId == BASIN || templateId == COLUMN;
    }

    /**
     * Unions the template mask into {@code mask}; returns newly added cells.
     * Unknown ids add nothing.
     */
    public static int apply(DraftMask mask, int templateId) {
        return switch (templateId) {
            case BATH -> applyBath(mask);
            case BASIN -> applyBasin(mask);
            case COLUMN -> applyColumn(mask);
            default -> 0;
        };
    }

    /**
     * Bathtub: a 10x10 cavity, 6 cells deep from the top face, plus a one-cell
     * bevelled rim. 600 + 44 = 644 cells.
     */
    static int applyBath(DraftMask mask) {
        DraftMask bath = new DraftMask();
        for (int y = 10; y <= 15; y++) {
            for (int x = 3; x <= 12; x++) {
                for (int z = 3; z <= 12; z++) {
                    bath.set(DraftMask.index(x, y, z));
                }
            }
        }
        for (int x = 2; x <= 13; x++) {
            for (int z = 2; z <= 13; z++) {
                if (x >= 3 && x <= 12 && z >= 3 && z <= 12) continue;
                bath.set(DraftMask.index(x, 15, z));
            }
        }
        return mask.orIn(bath);
    }

    /**
     * Water bowl: a paraboloid hollow centred on the block, 7 cells deep in the
     * middle and feathering out to the 12x12 top area.
     */
    static int applyBasin(DraftMask mask) {
        DraftMask basin = new DraftMask();
        for (int x = 2; x <= 13; x++) {
            for (int z = 2; z <= 13; z++) {
                double dx = x - 7.5;
                double dz = z - 7.5;
                int depth = 7 - (int) Math.floor((dx * dx + dz * dz) / 6.0);
                if (depth <= 0) continue;
                if (depth > 7) depth = 7;
                for (int step = 0; step < depth; step++) {
                    basin.set(DraftMask.index(x, 15 - step, z));
                }
            }
        }
        return mask.orIn(basin);
    }

    /**
     * Carved column: an 8x8 core kept full-height, four 4-wide vertical flutes cut
     * into its faces, plus a two-cell base ring and a two-cell capital ring.
     */
    static int applyColumn(DraftMask mask) {
        DraftMask column = new DraftMask();
        for (int y = 0; y <= 15; y++) {
            for (int groove = 6; groove <= 9; groove++) {
                column.set(DraftMask.index(4, y, groove));
                column.set(DraftMask.index(11, y, groove));
                column.set(DraftMask.index(groove, y, 4));
                column.set(DraftMask.index(groove, y, 11));
            }
        }
        for (int ring = 0; ring <= 1; ring++) {
            for (int x = 3; x <= 12; x++) {
                for (int z = 3; z <= 12; z++) {
                    if (x >= 4 && x <= 11 && z >= 4 && z <= 11) continue;
                    column.set(DraftMask.index(x, ring, z));
                    column.set(DraftMask.index(x, 15 - ring, z));
                }
            }
        }
        return mask.orIn(column);
    }
}
