package ua.rp.chat.microvoxel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class MicrovoxelVolume {
    public static final int RESOLUTION = 16;
    public static final int CELL_COUNT = RESOLUTION * RESOLUTION * RESOLUTION;
    public static final int MAX_PALETTE = 32;

    private int revision;
    private final List<String> palette;
    private final byte[] cells;
    private transient List<Cuboid> collisionCuboids;

    private MicrovoxelVolume(int revision, List<String> palette, byte[] cells) {
        this.revision = Math.max(1, revision);
        this.palette = new ArrayList<>(palette);
        this.cells = cells.clone();
        validate();
    }

    public static MicrovoxelVolume full(String blockData) {
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("Block data cannot be empty");
        }
        byte[] cells = new byte[CELL_COUNT];
        Arrays.fill(cells, (byte) 1);
        return new MicrovoxelVolume(1, List.of("", blockData), cells);
    }

    public static MicrovoxelVolume restore(int revision, List<String> palette, byte[] cells) {
        return new MicrovoxelVolume(revision, palette, cells);
    }

    public MicrovoxelVolume copy() {
        return new MicrovoxelVolume(revision, palette, cells);
    }

    public int revision() {
        return revision;
    }

    public List<String> palette() {
        return Collections.unmodifiableList(palette);
    }

    public byte[] cellsCopy() {
        return cells.clone();
    }

    public int materialIndex(int cell) {
        requireCell(cell);
        return Byte.toUnsignedInt(cells[cell]);
    }

    public String material(int cell) {
        return palette.get(materialIndex(cell));
    }

    public boolean occupied(int cell) {
        return materialIndex(cell) != 0;
    }

    public boolean occupied(int x, int y, int z) {
        return inside(x, y, z) && cells[index(x, y, z)] != 0;
    }

    public boolean remove(int cell) {
        requireCell(cell);
        if (cells[cell] == 0) {
            return false;
        }
        cells[cell] = 0;
        changed();
        return true;
    }

    public boolean put(int cell, String blockData) {
        requireCell(cell);
        if (blockData == null || blockData.isBlank()) {
            throw new IllegalArgumentException("Block data cannot be empty");
        }
        if (cells[cell] != 0) {
            return false;
        }
        int paletteIndex = palette.indexOf(blockData);
        if (paletteIndex < 0) {
            if (palette.size() >= MAX_PALETTE) {
                throw new IllegalStateException("Microvoxel palette limit reached");
            }
            palette.add(blockData);
            paletteIndex = palette.size() - 1;
        }
        cells[cell] = (byte) paletteIndex;
        changed();
        return true;
    }

    public int occupiedCount() {
        int count = 0;
        for (byte cell : cells) {
            if (cell != 0) count++;
        }
        return count;
    }

    public boolean hasOccupiedNeighbour(int cell) {
        int x = x(cell);
        int y = y(cell);
        int z = z(cell);
        return occupied(x - 1, y, z) || occupied(x + 1, y, z)
                || occupied(x, y - 1, z) || occupied(x, y + 1, z)
                || occupied(x, y, z - 1) || occupied(x, y, z + 1);
    }

    public boolean isUniformFull() {
        int material = Byte.toUnsignedInt(cells[0]);
        if (material == 0) return false;
        for (byte cell : cells) {
            if (Byte.toUnsignedInt(cell) != material) return false;
        }
        return true;
    }

    public String uniformMaterial() {
        return isUniformFull() ? palette.get(Byte.toUnsignedInt(cells[0])) : null;
    }

    public List<Cuboid> collisionCuboids() {
        if (collisionCuboids != null) return collisionCuboids;
        boolean[] used = new boolean[CELL_COUNT];
        List<Cuboid> result = new ArrayList<>();
        for (int y = 0; y < RESOLUTION; y++) {
            for (int z = 0; z < RESOLUTION; z++) {
                for (int x = 0; x < RESOLUTION; x++) {
                    int start = index(x, y, z);
                    if (cells[start] == 0 || used[start]) continue;
                    int maxX = x + 1;
                    while (maxX < RESOLUTION && canExtendX(used, maxX, y, z)) maxX++;
                    int maxZ = z + 1;
                    while (maxZ < RESOLUTION && canExtendZ(used, x, maxX, y, maxZ)) maxZ++;
                    int maxY = y + 1;
                    while (maxY < RESOLUTION && canExtendY(used, x, maxX, z, maxZ, maxY)) maxY++;
                    for (int cy = y; cy < maxY; cy++) {
                        for (int cz = z; cz < maxZ; cz++) {
                            for (int cx = x; cx < maxX; cx++) used[index(cx, cy, cz)] = true;
                        }
                    }
                    result.add(new Cuboid(x, y, z, maxX, maxY, maxZ));
                }
            }
        }
        collisionCuboids = List.copyOf(result);
        return collisionCuboids;
    }

    private boolean canExtendX(boolean[] used, int x, int y, int z) {
        int cell = index(x, y, z);
        return cells[cell] != 0 && !used[cell];
    }

    private boolean canExtendZ(boolean[] used, int minX, int maxX, int y, int z) {
        for (int x = minX; x < maxX; x++) {
            int cell = index(x, y, z);
            if (cells[cell] == 0 || used[cell]) return false;
        }
        return true;
    }

    private boolean canExtendY(boolean[] used, int minX, int maxX, int minZ, int maxZ, int y) {
        for (int z = minZ; z < maxZ; z++) {
            for (int x = minX; x < maxX; x++) {
                int cell = index(x, y, z);
                if (cells[cell] == 0 || used[cell]) return false;
            }
        }
        return true;
    }

    private void changed() {
        revision = revision == Integer.MAX_VALUE ? 1 : revision + 1;
        collisionCuboids = null;
    }

    private void validate() {
        if (cells.length != CELL_COUNT || palette.isEmpty() || !palette.get(0).isEmpty()
                || palette.size() > MAX_PALETTE) {
            throw new IllegalArgumentException("Invalid microvoxel volume");
        }
        HashSet<String> unique = new HashSet<>();
        for (int index = 1; index < palette.size(); index++) {
            String material = palette.get(index);
            if (material == null || material.isBlank() || !unique.add(material)) {
                throw new IllegalArgumentException("Invalid or duplicate palette material");
            }
        }
        for (byte cell : cells) {
            if (Byte.toUnsignedInt(cell) >= palette.size()) {
                throw new IllegalArgumentException("Cell references missing palette entry");
            }
        }
    }

    public static int index(int x, int y, int z) {
        if (!inside(x, y, z)) throw new IndexOutOfBoundsException("Microvoxel coordinate outside 16x16x16 volume");
        return x | (z << 4) | (y << 8);
    }

    public static int x(int cell) {
        requireCell(cell);
        return cell & 15;
    }

    public static int z(int cell) {
        requireCell(cell);
        return (cell >>> 4) & 15;
    }

    public static int y(int cell) {
        requireCell(cell);
        return (cell >>> 8) & 15;
    }

    public static boolean inside(int x, int y, int z) {
        return (x | y | z) >= 0 && x < RESOLUTION && y < RESOLUTION && z < RESOLUTION;
    }

    private static void requireCell(int cell) {
        if (cell < 0 || cell >= CELL_COUNT) throw new IndexOutOfBoundsException("Invalid microvoxel cell " + cell);
    }

    public record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }
}
