package ua.rp.chat.microvoxel;

import java.util.Arrays;

/**
 * Per-voxel water levels for one microvoxel block position. Levels run 0 (dry) to
 * {@link #MAX_LEVEL} (brimful) per 1/16 cell; solid cells (per the sibling microvoxel volume)
 * never hold water and are simply left at 0.
 *
 * <p>Pure data: no Minecraft inside, fully unit-testable. The authoritative water budget is
 * conserved by every operation — see {@link #equalizeInto} — so simulation bugs show up as
 * failing conservation tests instead of vanishing or duplicating water.</p>
 */
public final class FluidVolume {
    /** Cells per block edge; matches {@link MicrovoxelVolume#RESOLUTION}. */
    public static final int RESOLUTION = 16;
    /** Cells per block volume. */
    public static final int CELL_COUNT = RESOLUTION * RESOLUTION * RESOLUTION;
    /** Brimful cell level. */
    public static final int MAX_LEVEL = 16;

    /** Fluid kind per volume: one volume holds exactly one fluid, like vanilla blocks. */
    public enum Kind {
        WATER,
        LAVA;

        /** Wire/storage code; unknown codes fail closed at decode time. */
        public static Kind fromCode(int code) throws IllegalArgumentException {
            if (code == 1) return LAVA;
            if (code == 0) return WATER;
            throw new IllegalArgumentException("Unknown fluid kind " + code);
        }

        public int code() {
            return this == LAVA ? 1 : 0;
        }
    }

    private int revision;
    private final byte[] levels;
    private Kind kind;

    private FluidVolume(int revision, byte[] levels, Kind kind) {
        this.revision = Math.max(1, revision);
        this.levels = levels.clone();
        this.kind = kind == null ? Kind.WATER : kind;
        validate();
    }

    public static FluidVolume empty() {
        return new FluidVolume(1, new byte[CELL_COUNT], Kind.WATER);
    }

    public static FluidVolume empty(Kind kind) {
        return new FluidVolume(1, new byte[CELL_COUNT], kind);
    }

    public static FluidVolume restore(int revision, byte[] levels) {
        return new FluidVolume(revision, levels, Kind.WATER);
    }

    public static FluidVolume restore(int revision, byte[] levels, Kind kind) {
        return new FluidVolume(revision, levels, kind);
    }

    public FluidVolume copy() {
        return new FluidVolume(revision, levels, kind);
    }

    /** Fluid kind of this volume; never null. */
    public Kind kind() {
        return kind;
    }

    /** True for lava (burns, glows, never seeps or freezes). */
    public boolean isLava() {
        return kind == Kind.LAVA;
    }

    public int revision() {
        return revision;
    }

    public void setRevision(int revision) {
        this.revision = Math.max(1, revision);
    }

    public byte[] levelsCopy() {
        return levels.clone();
    }

    public int level(int cell) {
        requireCell(cell);
        return Byte.toUnsignedInt(levels[cell]);
    }

    public void setLevel(int cell, int level) {
        requireCell(cell);
        levels[cell] = (byte) Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /** Total water units in this volume; the conservation invariant sums these. */
    public long totalUnits() {
        long total = 0;
        for (byte level : levels) total += Byte.toUnsignedInt(level);
        return total;
    }

    /** True when no cell holds water. */
    public boolean isDry() {
        for (byte level : levels) {
            if (level != 0) return false;
        }
        return true;
    }

    /**
     * Fills every cell whose mask entry is true to brimful. The mask is normally "air cells
     * of the sibling microvoxel volume". Returns how many cells changed.
     */
    public int fillMasked(boolean[] airCells) {
        int changed = 0;
        for (int cell = 0; cell < CELL_COUNT; cell++) {
            if (airCells[cell] && levels[cell] != (byte) MAX_LEVEL) {
                levels[cell] = (byte) MAX_LEVEL;
                changed++;
            }
        }
        if (changed > 0) bumpRevision();
        return changed;
    }

    /** Empties the whole volume. Returns the water units removed (for metrics). */
    public long drainAll() {
        long removed = totalUnits();
        if (removed > 0) {
            Arrays.fill(levels, (byte) 0);
            bumpRevision();
        }
        return removed;
    }

    /**
     * Pressure equalization between two adjacent volumes along their shared face. Water moves
     * from higher to lower cells in matched pairs until the face is level or the transfer
     * budget runs out; returns units moved. The levels arrays are mutated in place and the
     * total across both arrays is exactly preserved.
     *
     * @param from        source levels (mutated)
     * @param to          sink levels (mutated)
     * @param pairs       matched cell index pairs across the shared face
     * @param maxTransfer per-call budget in units
     */
    public static long equalizeInto(byte[] from, byte[] to, int[] pairs, long maxTransfer) {
        long moved = 0;
        for (int index = 0; index + 1 < pairs.length && moved < maxTransfer; index += 2) {
            int source = pairs[index];
            int sink = pairs[index + 1];
            int sourceLevel = Byte.toUnsignedInt(from[source]);
            int sinkLevel = Byte.toUnsignedInt(to[sink]);
            int delta = (sourceLevel - sinkLevel) / 2;
            if (delta <= 0) continue;
            long allowed = Math.min(delta, maxTransfer - moved);
            from[source] = (byte) (sourceLevel - allowed);
            to[sink] = (byte) (sinkLevel + allowed);
            moved += allowed;
        }
        return moved;
    }

    /**
     * Settles one column stack against the sibling microvoxel geometry: purges levels out of
     * freshly solid cells (displaced upward into the topmost air segment, overflow deleted
     * like vanilla), then compacts every air segment bottom-up to brimful cells. Returns how
     * many cells changed; zero means already settled (callers skip revision bumps and dirty
     * flags on zero). Operates on raw arrays with scalar accumulators: no allocation, one
     * pass per column, L1-friendly.
     *
     * @param occupied per-cell solidity of the sibling microvoxel volume (true = solid)
     * @return changed cell count, plus purged-overflow units packed into {@code deletedOut}
     *         when non-null (single-element array used to avoid allocation)
     */
    public int settleWith(boolean[] occupied, long[] deletedOut) {
        int changed = 0;
        long deleted = 0;
        // Segment starts per column; alternating solid/air caps segments at 8, array of 16
        // is deliberately oversized to avoid any length checks in the hot loop.
        int[] segments = new int[16];
        for (int x = 0; x < RESOLUTION; x++) {
            for (int z = 0; z < RESOLUTION; z++) {
                // Purge pass: solid cells surrender their levels into the displacement pool.
                long pool = 0;
                for (int y = RESOLUTION - 1; y >= 0; y--) {
                    int cell = index(x, y, z);
                    if (occupied[cell] && levels[cell] != 0) {
                        pool += Byte.toUnsignedInt(levels[cell]);
                        levels[cell] = 0;
                        changed++;
                    }
                }
                // Gather air segments bottom-up (at most 8, array holds 16).
                int segmentCount = 0;
                int y = 0;
                while (y < RESOLUTION) {
                    if (occupied[index(x, y, z)]) {
                        y++;
                        continue;
                    }
                    segments[segmentCount++] = y;
                    do {
                        y++;
                    } while (y < RESOLUTION && !occupied[index(x, y, z)]);
                }
                // Fill every segment from its own water; the pool pours into the topmost one
                // (displaced upward, like vanilla) and only its overflow is deleted.
                for (int segment = 0; segment < segmentCount; segment++) {
                    int start = segments[segment];
                    int end = start + 1;
                    while (end < RESOLUTION && !occupied[index(x, end, z)]) end++;
                    boolean topmost = segment == segmentCount - 1;
                    long units = topmost ? pool : 0;
                    for (int fill = start; fill < end; fill++) {
                        units += Byte.toUnsignedInt(levels[index(x, fill, z)]);
                    }
                    for (int fill = start; fill < end; fill++) {
                        int target = (int) Math.min(MAX_LEVEL, units);
                        units -= target;
                        int cell = index(x, fill, z);
                        if (levels[cell] != (byte) target) {
                            levels[cell] = (byte) target;
                            changed++;
                        }
                    }
                    if (topmost) {
                        deleted += units;
                        pool = 0;
                    }
                }
                // No air segments at all (column freshly bricked solid): the whole pool
                // overflows and is deleted, exactly like vanilla.
                deleted += pool;
            }
        }
        if (changed > 0) bumpRevision();
        if (deletedOut != null && deletedOut.length > 0) deletedOut[0] += deleted;
        return changed;
    }

    /**
     * Tops boundary air cells toward full from a vanilla neighbor. Source water fills to the
     * brim; lesser flows only to half (no infinite full basins off a trickle). Stops after
     * {@code maxCells} topped cells so one face cannot eat the whole tick budget. Returns
     * topped cell count. Pure array math, no world access.
     */
    public static int inflowTopUp(byte[] levels, boolean[] air, int[] boundaryCells,
                                  boolean source, int maxCells) {
        int cap = source ? MAX_LEVEL : MAX_LEVEL / 2;
        int topped = 0;
        for (int cell : boundaryCells) {
            if (topped >= maxCells) break;
            if (!air[cell]) continue;
            int current = Byte.toUnsignedInt(levels[cell]);
            if (current >= cap) continue;
            levels[cell] = (byte) cap;
            topped++;
        }
        return topped;
    }

    /**
     * Rain catch: the topmost air cell of every column gains {@code amount}, capped at brim.
     * Rain lands on surfaces, never inside rock. Returns topped cell count. Pure array math.
     */
    public static int rainTopUp(byte[] levels, boolean[] solid, int amount) {
        int topped = 0;
        for (int x = 0; x < RESOLUTION; x++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int y = RESOLUTION - 1; y >= 0; y--) {
                    int cell = index(x, y, z);
                    if (solid[cell]) break;
                    int current = Byte.toUnsignedInt(levels[cell]);
                    if (current >= MAX_LEVEL) break;
                    levels[cell] = (byte) Math.min(MAX_LEVEL, current + amount);
                    topped++;
                    break;
                }
            }
        }
        return topped;
    }

    /**
     * Freezes the topmost wet cell of every column into ice (returned cells), zeroing their
     * levels. Solid cells are skipped through rather than stopping the column, so lakes
     * frost top-down layer by layer as each crust turns solid. The caller writes ice voxels
     * into the sibling microvoxel volume for exactly these cells. Pure array math; thaw is
     * deliberately one-way v1.
     */
    public static java.util.List<Integer> freezeTopCells(byte[] levels, boolean[] solid) {
        java.util.List<Integer> frozen = new java.util.ArrayList<>();
        for (int x = 0; x < RESOLUTION; x++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int y = RESOLUTION - 1; y >= 0; y--) {
                    int cell = index(x, y, z);
                    if (solid[cell]) continue;
                    if (Byte.toUnsignedInt(levels[cell]) > 0) {
                        levels[cell] = 0;
                        frozen.add(cell);
                        break;
                    }
                }
            }
        }
        return frozen;
    }

    /**
     * Drains one bottom layer above open space for outflow placement. Returns drained units;
     * the caller places vanilla water below and accounts the rest. Pure array math.
     */
    public static long drainBottomLayer(byte[] levels, boolean[] solid, int perCell) {
        long drained = 0;
        for (int x = 0; x < RESOLUTION; x++) {
            for (int z = 0; z < RESOLUTION; z++) {
                int cell = index(x, 0, z);
                if (solid[cell]) continue;
                int current = Byte.toUnsignedInt(levels[cell]);
                if (current <= 0) continue;
                int take = Math.min(current, perCell);
                levels[cell] = (byte) (current - take);
                drained += take;
            }
        }
        return drained;
    }

    /**
     * Raw levels for tick kernels that already hold the store lock; mutate sparingly.
     * Public because the sim lives in a neighboring component package by design.
     */
    public byte[] levelsDirect() {
        return levels;
    }

    /**
     * Horizontal pressure relaxation inside one volume: bottom-up per layer, pairwise
     * equalization across the 4-neighbourhood, half the gap on differences of 2+, all moves
     * capped by a shared budget. The sweep direction alternates with {@code flip} (callers
     * pass the tick parity), so continuous flow never drifts sideways over time. Solid cells
     * neither give nor receive. Returns moved units; exactly conserving, bump on change.
     */
    public long lateralFlow(boolean[] solid, long maxMove, boolean flip) {
        long moved = 0;
        for (int y = 0; y < RESOLUTION && moved < maxMove; y++) {
            for (int z = 0; z < RESOLUTION && moved < maxMove; z++) {
                for (int i = 0; i < RESOLUTION && moved < maxMove; i++) {
                    int x = flip ? RESOLUTION - 1 - i : i;
                    int cell = index(x, y, z);
                    if (solid[cell]) continue;
                    int here = Byte.toUnsignedInt(levels[cell]);
                    int nx = flip ? x - 1 : x + 1;
                    if (nx >= 0 && nx < RESOLUTION) {
                        int neighbour = index(nx, y, z);
                        if (!solid[neighbour]) {
                            int there = Byte.toUnsignedInt(levels[neighbour]);
                            long allowed = Math.min((here - there) / 2, maxMove - moved);
                            if (allowed > 0) {
                                levels[cell] = (byte) (here - allowed);
                                levels[neighbour] = (byte) (there + allowed);
                                moved += allowed;
                                here -= (int) allowed;
                            }
                        }
                    }
                    int nz = flip ? z - 1 : z + 1;
                    if (nz >= 0 && nz < RESOLUTION) {
                        int neighbour = index(x, y, nz);
                        if (!solid[neighbour]) {
                            int there = Byte.toUnsignedInt(levels[neighbour]);
                            long allowed = Math.min((here - there) / 2, maxMove - moved);
                            if (allowed > 0) {
                                levels[cell] = (byte) (here - allowed);
                                levels[neighbour] = (byte) (there + allowed);
                                moved += allowed;
                            }
                        }
                    }
                }
            }
        }
        if (moved > 0) bumpRevision();
        return moved;
    }

    /**
     * Two-way pressure transfer with a neighboring volume across matched face pairs. Moves
     * water both directions toward equilibrium within the budget; the combined total is
     * exactly preserved. Both volumes bump revision when anything moved.
     */
    public long equalizeWith(FluidVolume other, int[] pairs, long maxTransfer) {
        long moved = equalizeInto(this.levels, other.levels, pairs, maxTransfer);
        moved += equalizeInto(other.levels, this.levels, pairs, maxTransfer - moved);
        if (moved > 0) {
            bumpRevision();
            other.bumpRevision();
        }
        return moved;
    }

    /**
     * Matched cell pairs for two volumes sharing a face. {@code axis} is 0/1/2 for a face
     * normal along X/Y/Z; {@code positive} selects which side of {@code from} touches
     * {@code to}. Precomputed once per combination (6 tables, 3KB total): the sim resolves
     * them 144 times per tick, so per-call allocation would be pure GC churn. Treat the
     * returned arrays as immutable.
     */
    private static final int[][] FACE_PAIR_TABLES = buildFacePairTables();

    public static int[] facePairs(int axis, boolean positive) {
        return FACE_PAIR_TABLES[axis * 2 + (positive ? 0 : 1)];
    }

    private static int[][] buildFacePairTables() {
        int[][] tables = new int[6][];
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = 0; sign < 2; sign++) {
                boolean positive = sign == 0;
                int[] pairs = new int[16 * 16 * 2];
                int cursor = 0;
                for (int a = 0; a < 16; a++) {
                    for (int b = 0; b < 16; b++) {
                        int from;
                        int to;
                        if (axis == 0) {
                            from = index(positive ? 15 : 0, a, b);
                            to = index(positive ? 0 : 15, a, b);
                        } else if (axis == 1) {
                            from = index(a, positive ? 15 : 0, b);
                            to = index(a, positive ? 0 : 15, b);
                        } else {
                            from = index(a, b, positive ? 15 : 0);
                            to = index(a, b, positive ? 0 : 15);
                        }
                        pairs[cursor++] = from;
                        pairs[cursor++] = to;
                    }
                }
                tables[axis * 2 + sign] = pairs;
            }
        }
        return tables;
    }

    public static int index(int x, int y, int z) {
        return x | (z << 4) | (y << 8);
    }

    private void bumpRevision() {
        revision = revision >= Integer.MAX_VALUE ? 1 : revision + 1;
    }

    private void validate() {
        if (levels.length != CELL_COUNT) {
            throw new IllegalArgumentException("Fluid levels must hold 4096 cells");
        }
        for (byte level : levels) {
            if (Byte.toUnsignedInt(level) > MAX_LEVEL) {
                throw new IllegalArgumentException("Fluid level out of range");
            }
        }
    }

    private static void requireCell(int cell) {
        if (cell < 0 || cell >= CELL_COUNT) throw new IndexOutOfBoundsException("Invalid fluid cell " + cell);
    }
}
