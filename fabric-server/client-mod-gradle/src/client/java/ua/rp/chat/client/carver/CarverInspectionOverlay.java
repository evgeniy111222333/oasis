package ua.rp.chat.client.carver;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ua.rp.chat.carver.CarverChalkQuads;
import ua.rp.chat.carver.CarverFaceSlicer;
import ua.rp.chat.carver.CarverInclusionField;
import ua.rp.chat.carver.DraftMask;

/**
 * Phase 0 world overlay for the meta-information that is not part of the material: the linked
 * inclusion hints, the entry scan pulse and the sonar reveal. The grain itself is baked into the
 * hologram mesh (see {@code CarverHologramRenderer} / {@code CarverGrainTint}).
 *
 * <p>Inclusion markers respect the live occupancy (volume minus draft): a hint is drawn on the
 * first still-solid cell walking in from the shell, so a carved-away voxel never leaves a marker
 * floating over the hole. Every mark is {@code setAlwaysOnTop()} (the copy is opaque and drawn
 * in the same pass) and back-facing footprints are culled.</p>
 */
public final class CarverInspectionOverlay {
    /** Line thickness, in blocks. */
    private static final double THICK = 0.0042;
    /** Surface nudge so the marks sit just proud of the copy, in blocks. */
    private static final double PAD = 0.006;
    /** Cell centre offset from the cell min corner, in blocks. */
    private static final double HALF = 0.5 / 16.0;
    /** Stroke width of the analysis marks, in pixels. */
    private static final float STROKE = 2.0f;
    /** Half-length of a grain direction arrow, in blocks. */
    private static final double ARROW = 0.34 / 16.0;
    /** Half-thickness of a grain arrow shaft, in blocks. */
    private static final double ARROW_THICK = 0.010;
    /** Grid stride of the grain arrows across the working face; larger = sparser. */
    private static final int ARROW_STRIDE = 3;
    /** Grain guide colour, alpha baked in. */
    private static final int GRAIN_ARROW_RGB = 0x9FC3C4;

    private static final GizmoStyle SCAN = GizmoStyle.stroke(0x66BFE3FF, STROKE);
    private static final int SONAR_RGB = 0xC6A8FF;
    private static final int COMMON_RGB = 0x6FA8DC;
    private static final int UNCOMMON_RGB = 0xFFC24A;
    private static final int RARE_RGB = 0xE070FF;

    private static BlockPos occFocus;
    private static int occRevision = Integer.MIN_VALUE;
    private static long occDraftFp = Long.MIN_VALUE;
    private static boolean[] occCache;

    private CarverInspectionOverlay() {
    }

    /** World-space END_MAIN hook, mirroring the chalk overlay's contract. */
    public static void render() {
        if (!CarverClientState.designing()) return;
        BlockPos focus = CarverInspection.focus();
        if (focus == null) return;
        double lift = CarverHologram.visualLift();
        double offX = CarverHologram.offsetX();
        double offZ = CarverHologram.offsetZ();
        if (CarverInspection.enabled()) {
            boolean[] occupied = occupancy(focus);
            if (occupied != null) {
                drawStructures(focus, lift, offX, offZ, cameraPos(), occupied);
                if (CarverInspection.grainArrows()) {
                    drawGrainArrows(focus, lift, offX, offZ, occupied);
                }
            }
        }
        drawScan(focus, lift, offX, offZ);
        if (CarverInspection.sonarActive()) {
            drawSonarRipple(focus, lift, offX, offZ);
        }
    }

    /**
     * Live occupancy of the socket (volume minus draft), cached per (focus, revision, draft).
     * Null when the volume is unreadable, in which case inclusion marks are skipped entirely.
     */
    private static boolean[] occupancy(BlockPos focus) {
        DraftMask draft = CarverClientState.draft();
        long fingerprint = draft.isEmpty() ? 0L : CarverChalkQuads.draftFingerprint(draft);
        int revision = -1;
        try {
            ua.rp.chat.client.microvoxel.MicrovoxelClientState.CachedVolume cached =
                    ua.rp.chat.client.microvoxel.MicrovoxelClientState.get(focus);
            if (cached != null && cached.volume != null) {
                revision = cached.volume.revision();
            }
        } catch (RuntimeException ignored) {
            revision = -1;
        }
        if (focus.equals(occFocus) && revision == occRevision && fingerprint == occDraftFp
                && occCache != null) {
            return occCache;
        }
        ua.rp.chat.microvoxel.MicrovoxelVolume volume;
        try {
            volume = CarverHologramRenderer.sourceVolume(focus);
        } catch (RuntimeException unreadable) {
            return null;
        }
        if (volume == null) return null;
        boolean[] occupied = new boolean[DraftMask.CELL_COUNT];
        for (int cell = 0; cell < DraftMask.CELL_COUNT; cell++) {
            occupied[cell] = volume.occupied(cell) && !draft.get(cell);
        }
        occFocus = focus.immutable();
        occRevision = revision;
        occDraftFp = fingerprint;
        occCache = occupied;
        return occupied;
    }

    /**
     * Inclusion rendering. Passively each structure shows one hint on its real remaining surface,
     * faded by how deep it hides. The sonar reveal paints the still-solid voxels and their
     * surface footprints in the tier colour, so a scan reads as a real sonogram.
     */
    private static void drawStructures(BlockPos focus, double lift, double offX, double offZ,
                                       Vec3 camera, boolean[] occupied) {
        boolean sonar = CarverInspection.sonarActive();
        double fade = sonar ? CarverInspection.sonarProgress() : 0.0;
        for (CarverInclusionField.Structure structure : CarverInspection.structures()) {
            int rgb = tierColor(structure.tier());
            double half = kindHalf(structure.kind());
            if (sonar) {
                double alpha = 0.35 + 0.55 * (1.0 - fade);
                for (int cell : structure.cells()) {
                    if (!occupied[cell]) continue;
                    pointCell(focus, lift, offX, offZ, cell, half, rgb, alpha);
                    footprintCell(focus, lift, offX, offZ, camera, cell, half, rgb, alpha * 0.5,
                            occupied);
                }
            } else {
                int cell = surfaceFacingCell(structure, occupied);
                if (cell < 0) continue;
                int depth = boundaryDepth(cell);
                double alpha = (0.20 + (structure.tier() == CarverInclusionField.Tier.RARE ? 0.16 : 0.0))
                        * (1.0 - Math.min(1.0, depth / 15.0)) + 0.05;
                footprintCell(focus, lift, offX, offZ, camera, cell, half * 1.1, rgb, alpha, occupied);
                if (structure.tier() == CarverInclusionField.Tier.RARE) {
                    footprintCell(focus, lift, offX, offZ, camera, cell, half * 1.9, rgb, alpha * 0.5,
                            occupied);
                }
            }
        }
    }

    private static void pointCell(BlockPos focus, double lift, double offX, double offZ,
                                  int cell, double half, int rgb, double alpha) {
        double px = focus.getX() + offX + (DraftMask.x(cell) + 0.5) / 16.0;
        double py = focus.getY() + lift + (DraftMask.y(cell) + 0.5) / 16.0;
        double pz = focus.getZ() + offZ + (DraftMask.z(cell) + 0.5) / 16.0;
        draw(new AABB(px - half, py - half, pz - half, px + half, py + half, pz + half),
                GizmoStyle.stroke(alphaColor(rgb, alpha), STROKE));
    }

    /**
     * Footprint of one inclusion voxel on the real surface: walks in from the shell along the
     * nearest face until the first still-solid cell and marks that exposed plane. A fully carved
     * column draws nothing, so a hint can never float over a hole.
     */
    private static void footprintCell(BlockPos focus, double lift, double offX, double offZ,
                                      Vec3 camera, int cell, double half, int rgb, double alpha,
                                      boolean[] occupied) {
        CarverFaceSlicer.Face face = nearestFace(cell);
        int[] n = normal(face);
        int[] grid = CarverFaceSlicer.inverse(face, cell);
        int surface = -1;
        for (int layer = 0; layer < 16; layer++) {
            int candidate = CarverFaceSlicer.cellFor(face, grid[0], grid[1], layer);
            if (occupied[candidate]) {
                surface = candidate;
                break;
            }
        }
        if (surface < 0) return;
        double px = focus.getX() + offX + (DraftMask.x(surface) + 0.5) / 16.0 + n[0] * (HALF + PAD);
        double py = focus.getY() + lift + (DraftMask.y(surface) + 0.5) / 16.0 + n[1] * (HALF + PAD);
        double pz = focus.getZ() + offZ + (DraftMask.z(surface) + 0.5) / 16.0 + n[2] * (HALF + PAD);
        if (n[0] * (px - camera.x) + n[1] * (py - camera.y) + n[2] * (pz - camera.z) >= 0.0) {
            return; // back-facing footprint, hidden by the copy
        }
        double tx = n[0] != 0 ? THICK : half;
        double ty = n[1] != 0 ? THICK : half;
        double tz = n[2] != 0 ? THICK : half;
        draw(new AABB(px - tx, py - ty, pz - tz, px + tx, py + ty, pz + tz),
                GizmoStyle.stroke(alphaColor(rgb, alpha), STROKE));
    }

    /**
     * Directional grain guides on the working face: a sparse comb of short arrows along the
     * projected grain travel, coloured by the domain, plus a cap at the positive tip. A glance
     * tells the artisan which way the material wants to be cut; a carved hole never shows a
     * floating arrow because only still-solid cells are drawn.
     */
    private static void drawGrainArrows(BlockPos focus, double lift, double offX, double offZ,
                                        boolean[] occupied) {
        ua.rp.chat.carver.CarverGrainField.Field grain = CarverInspection.field();
        if (grain == null || !grain.hasGrain()) return;
        CarverFaceSlicer.Face face = CarverClientState.viewFace();
        for (int row = 0; row < 16; row += ARROW_STRIDE) {
            for (int col = 0; col < 16; col += ARROW_STRIDE) {
                int surface = -1;
                for (int layer = 0; layer < 16; layer++) {
                    int candidate = CarverFaceSlicer.cellFor(face, col, row, layer);
                    if (occupied[candidate]) {
                        surface = candidate;
                        break;
                    }
                }
                if (surface < 0) continue;
                ua.rp.chat.carver.CarverGrainField.Direction dir =
                        ua.rp.chat.carver.CarverGrainField.projected(grain.direction(surface), face);
                double cx = focus.getX() + offX + (DraftMask.x(surface) + 0.5) / 16.0;
                double cy = focus.getY() + lift + (DraftMask.y(surface) + 0.5) / 16.0;
                double cz = focus.getZ() + offZ + (DraftMask.z(surface) + 0.5) / 16.0;
                int rgb = ua.rp.chat.carver.CarverGrainPalette.lensColor(
                        grain.type(), grain.domain(surface), grain.strength(surface));
                double tx = dir.x() != 0 ? ARROW : ARROW_THICK;
                double ty = dir.y() != 0 ? ARROW : ARROW_THICK;
                double tz = dir.z() != 0 ? ARROW : ARROW_THICK;
                draw(new AABB(cx - tx, cy - ty, cz - tz, cx + tx, cy + ty, cz + tz),
                        GizmoStyle.stroke(alphaColor(rgb, 0.85), STROKE));
                double hx = dir.x() * ARROW;
                double hy = dir.y() * ARROW;
                double hz = dir.z() * ARROW;
                double cap = ARROW * 0.35;
                draw(new AABB(cx + hx - cap, cy + hy - cap, cz + hz - cap,
                                cx + hx + cap, cy + hy + cap, cz + hz + cap),
                        GizmoStyle.stroke(alphaColor(rgb, 1.0), STROKE));
            }
        }
    }

    // --- pulses ---
    private static void drawScan(BlockPos focus, double lift, double offX, double offZ) {
        double progress = CarverInspection.scanProgress();
        if (progress >= 1.0) return;
        double half = 0.05 + 0.55 * progress;
        double cx = focus.getX() + 0.5 + offX;
        double cy = focus.getY() + 0.5 + lift;
        double cz = focus.getZ() + 0.5 + offZ;
        draw(new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half), SCAN);
    }

    private static void drawSonarRipple(BlockPos focus, double lift, double offX, double offZ) {
        double progress = CarverInspection.sonarProgress();
        double half = 0.1 + 0.7 * progress;
        double alpha = 0.5 * (1.0 - progress);
        double cx = focus.getX() + 0.5 + offX;
        double cy = focus.getY() + 0.5 + lift;
        double cz = focus.getZ() + 0.5 + offZ;
        draw(new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half),
                GizmoStyle.stroke(alphaColor(SONAR_RGB, alpha), STROKE));
    }

    // --- helpers ---

    /** Analysis marks always render on top so the opaque copy cannot hide them. */
    private static void draw(AABB box, GizmoStyle style) {
        Gizmos.cuboid(box, style).setAlwaysOnTop();
    }

    private static Vec3 cameraPos() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.gameRenderer != null
                    && minecraft.gameRenderer.getMainCamera() != null) {
                return minecraft.gameRenderer.getMainCamera().position();
            }
        } catch (RuntimeException ignored) {
        }
        return Vec3.ZERO;
    }

    private static int tierColor(CarverInclusionField.Tier tier) {
        return switch (tier) {
            case COMMON -> COMMON_RGB;
            case UNCOMMON -> UNCOMMON_RGB;
            case RARE -> RARE_RGB;
        };
    }

    private static double kindHalf(CarverInclusionField.Kind kind) {
        return switch (kind) {
            case CRACK -> 0.32 / 16.0;
            case VEIN -> 0.5 / 16.0;
            case CAVITY -> 0.72 / 16.0;
        };
    }

    /**
     * The still-solid structure voxel nearest the surface, so the passive hint lands close to the
     * viewer. Returns -1 when the inclusion no longer has any solid voxel.
     */
    private static int surfaceFacingCell(CarverInclusionField.Structure structure, boolean[] occupied) {
        int best = -1;
        int bestDepth = Integer.MAX_VALUE;
        for (int cell : structure.cells()) {
            if (!occupied[cell]) continue;
            int depth = boundaryDepth(cell);
            if (depth < bestDepth) {
                bestDepth = depth;
                best = cell;
            }
        }
        return best;
    }

    private static int boundaryDepth(int cell) {
        int x = DraftMask.x(cell);
        int y = DraftMask.y(cell);
        int z = DraftMask.z(cell);
        return Math.min(Math.min(x, 15 - x), Math.min(Math.min(y, 15 - y), Math.min(z, 15 - z)));
    }

    private static int alphaColor(int rgb, double alpha) {
        int a = (int) Math.round(Math.max(0.0, Math.min(1.0, alpha)) * 255.0);
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static int[] normal(CarverFaceSlicer.Face face) {
        return switch (face) {
            case UP -> new int[]{0, 1, 0};
            case DOWN -> new int[]{0, -1, 0};
            case NORTH -> new int[]{0, 0, -1};
            case SOUTH -> new int[]{0, 0, 1};
            case WEST -> new int[]{-1, 0, 0};
            case EAST -> new int[]{1, 0, 0};
        };
    }

    private static CarverFaceSlicer.Face nearestFace(int cell) {
        int x = DraftMask.x(cell);
        int y = DraftMask.y(cell);
        int z = DraftMask.z(cell);
        int best = 15 - x;
        CarverFaceSlicer.Face face = CarverFaceSlicer.Face.EAST;
        if (x < best) { best = x; face = CarverFaceSlicer.Face.WEST; }
        if (15 - y < best) { best = 15 - y; face = CarverFaceSlicer.Face.UP; }
        if (y < best) { best = y; face = CarverFaceSlicer.Face.DOWN; }
        if (15 - z < best) { best = 15 - z; face = CarverFaceSlicer.Face.SOUTH; }
        if (z < best) { face = CarverFaceSlicer.Face.NORTH; }
        return face;
    }
}
