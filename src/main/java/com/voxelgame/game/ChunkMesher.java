package com.voxelgame.game;

import java.util.Arrays;
import java.util.Map;

/**
 * Generates an interleaved vertex array (position + color) from a Chunk
 * using greedy meshing.
 *
 * <h3>Threading</h3>
 * This class is stateless and thread-safe. It receives a pre-captured snapshot
 * of the six face-adjacent neighbor chunks instead of a World reference,
 * allowing meshing to run on background threads with no shared-state access.
 *
 * <h3>How greedy meshing works</h3>
 * For each of the six face directions, each layer along that direction's axis
 * is scanned independently. A 2D mask records which cells have a visible face
 * and what block type they belong to. The greedy merge pass finds the largest
 * axis-aligned rectangle of identical type, emits one quad for it, marks those
 * cells consumed, and repeats until the layer is fully processed.
 *
 * <p>On a flat 16×16 surface this collapses 256 quads into 1. On typical
 * terrain with mixed block types the reduction is significant but smaller.
 *
 * <h3>Output format</h3>
 * Interleaved [x, y, z, r, g, b] — 6 floats, 6 vertices per quad (two
 * triangles). {@link com.voxelgame.engine.Mesh} converts these to indexed
 * geometry internally.
 *
 * <h3>Allocation</h3>
 * Uses a manually grown {@code float[]} instead of {@code List<Float>} to
 * avoid per-element boxing. The buffer starts at a capacity sized for a typical
 * chunk and doubles when full — same strategy as ArrayList but without the
 * Float object overhead.
 */
public class ChunkMesher {

    // Directional brightness multipliers — applied to block color at mesh-build time.
    private static final float SHADE_TOP    = 1.0f;
    private static final float SHADE_SIDE   = 0.7f;
    private static final float SHADE_BOTTOM = 0.5f;

    /**
     * Floats per quad (6 vertices × 6 floats). Used for initial buffer sizing.
     * A typical surface chunk has ~256 quads before greedy merging, far fewer after.
     * Starting at 1024 quads × 36 floats = 36864 floats covers most cases without
     * excessive initial allocation.
     */
    private static final int INITIAL_CAPACITY = 36 * 1024;

    /**
     * Generates the greedy-merged visible mesh for the given chunk.
     *
     * <p>Fast-path: all-air chunks return an empty array immediately with no
     * iteration. For occupied chunks, the Y occupancy range from the chunk is
     * passed to each face pass so layer loops are clamped to the occupied band.
     *
     * @param chunk     the chunk to mesh
     * @param pos       the chunk's position in the world grid
     * @param neighbors snapshot of the six face-adjacent neighbor chunks
     * @return interleaved float array [x, y, z, r, g, b, ...], trimmed to exact size
     */
    public static float[] mesh(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors) {
        // All-air chunks produce no geometry — skip all six face passes entirely
        if (chunk.isAllAir()) return new float[0];

        int minY = chunk.getMinOccupiedY();
        int maxY = chunk.getMaxOccupiedY();

        float[] buf  = new float[INITIAL_CAPACITY];
        int     size = 0;
        int[][] mask = new int[Chunk.SIZE][Chunk.SIZE];

        size = meshTopFaces   (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshBottomFaces(chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshNorthFaces (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshSouthFaces (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshEastFaces  (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshWestFaces  (chunk, pos, neighbors, buf, size, mask, minY, maxY);

        return Arrays.copyOf(buf, size);
    }

    // -------------------------------------------------------------------------
    // Per-direction mesh passes
    // -------------------------------------------------------------------------

    /**
     * TOP faces (+Y): outer loop clamped to [minY, maxY] — layers above and below
     * the occupied band are guaranteed empty and skipped entirely.
     */
    private static int meshTopFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                     float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < Chunk.SIZE; z++)
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[z][x] = (b != Block.AIR && isAirAt(chunk, pos, neighbors, x, y + 1, z))
                            ? b.ordinal() + 1 : 0;
                }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 5) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                float[] color = Block.values()[quads[qi+4] - 1].color();
                buf = ensureCapacity(buf, size, 36);
                size = emitQuad(buf, size, color, SHADE_TOP,
                        i,     y + 1, j,
                        i,     y + 1, j + h,
                        i + w, y + 1, j + h,
                        i + w, y + 1, j);
            }
        }
        return size;
    }

    /**
     * BOTTOM faces (-Y): outer loop clamped to [minY, maxY].
     */
    private static int meshBottomFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                        float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < Chunk.SIZE; z++)
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[z][x] = (b != Block.AIR && isAirAt(chunk, pos, neighbors, x, y - 1, z))
                            ? b.ordinal() + 1 : 0;
                }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 5) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                float[] color = Block.values()[quads[qi+4] - 1].color();
                buf = ensureCapacity(buf, size, 36);
                size = emitQuad(buf, size, color, SHADE_BOTTOM,
                        i,     y, j + h,
                        i,     y, j,
                        i + w, y, j,
                        i + w, y, j + h);
            }
        }
        return size;
    }

    /** NORTH faces (-Z): stale mask rows outside [minY, maxY] are explicitly zeroed. */
    private static int meshNorthFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                       float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) {
                    Arrays.fill(mask[y], 0);
                    continue;
                }
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][x] = (b != Block.AIR && isAirAt(chunk, pos, neighbors, x, y, z - 1))
                            ? b.ordinal() + 1 : 0;
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 5) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                float[] color = Block.values()[quads[qi+4] - 1].color();
                buf = ensureCapacity(buf, size, 36);
                size = emitQuad(buf, size, color, SHADE_SIDE,
                        i,     j,     z,
                        i,     j + h, z,
                        i + w, j + h, z,
                        i + w, j,     z);
            }
        }
        return size;
    }

    /** SOUTH faces (+Z): stale mask rows outside [minY, maxY] are explicitly zeroed. */
    private static int meshSouthFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                       float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) {
                    Arrays.fill(mask[y], 0);
                    continue;
                }
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][x] = (b != Block.AIR && isAirAt(chunk, pos, neighbors, x, y, z + 1))
                            ? b.ordinal() + 1 : 0;
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 5) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                float[] color = Block.values()[quads[qi+4] - 1].color();
                buf = ensureCapacity(buf, size, 36);
                size = emitQuad(buf, size, color, SHADE_SIDE,
                        i + w, j,     z + 1,
                        i + w, j + h, z + 1,
                        i,     j + h, z + 1,
                        i,     j,     z + 1);
            }
        }
        return size;
    }

    /** EAST faces (+X): stale mask rows outside [minY, maxY] are explicitly zeroed. */
    private static int meshEastFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                      float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) {
                    Arrays.fill(mask[y], 0);
                    continue;
                }
                for (int z = 0; z < Chunk.SIZE; z++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][z] = (b != Block.AIR && isAirAt(chunk, pos, neighbors, x + 1, y, z))
                            ? b.ordinal() + 1 : 0;
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 5) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                float[] color = Block.values()[quads[qi+4] - 1].color();
                buf = ensureCapacity(buf, size, 36);
                size = emitQuad(buf, size, color, SHADE_SIDE,
                        x + 1, j,     i,
                        x + 1, j + h, i,
                        x + 1, j + h, i + w,
                        x + 1, j,     i + w);
            }
        }
        return size;
    }

    /** WEST faces (-X): stale mask rows outside [minY, maxY] are explicitly zeroed. */
    private static int meshWestFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                      float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) {
                    Arrays.fill(mask[y], 0);
                    continue;
                }
                for (int z = 0; z < Chunk.SIZE; z++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][z] = (b != Block.AIR && isAirAt(chunk, pos, neighbors, x - 1, y, z))
                            ? b.ordinal() + 1 : 0;
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 5) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                float[] color = Block.values()[quads[qi+4] - 1].color();
                buf = ensureCapacity(buf, size, 36);
                size = emitQuad(buf, size, color, SHADE_SIDE,
                        x, j,     i + w,
                        x, j + h, i + w,
                        x, j + h, i,
                        x, j,     i);
            }
        }
        return size;
    }

    // -------------------------------------------------------------------------
    // Greedy merge
    // -------------------------------------------------------------------------

    /**
     * Runs the greedy merge algorithm on a SIZE×SIZE mask.
     * Returns a flat int array of [i, j, w, h, type, i, j, w, h, type, ...]
     * — 5 ints per quad. Using a flat int[] avoids int[] object allocation
     * per quad compared to returning a List of int arrays.
     *
     * <p>Modifies the mask in place. Callers must rebuild before the next layer.
     *
     * @param mask SIZE×SIZE mask — 0 = no face, positive = block ordinal + 1
     * @return flat int array of quads, length = quadCount × 5
     */
    private static int[] buildMergedQuads(int[][] mask) {
        // Pre-allocate generously — SIZE*SIZE worst case quads, trimmed at end
        int[] result = new int[Chunk.SIZE * Chunk.SIZE * 5];
        int count = 0;

        for (int j = 0; j < Chunk.SIZE; j++) {
            for (int i = 0; i < Chunk.SIZE; i++) {
                int type = mask[j][i];
                if (type == 0) continue;

                int w = 1;
                while (i + w < Chunk.SIZE && mask[j][i + w] == type) w++;

                int h = 1;
                outer:
                while (j + h < Chunk.SIZE) {
                    for (int k = 0; k < w; k++)
                        if (mask[j + h][i + k] != type) break outer;
                    h++;
                }

                result[count++] = i;
                result[count++] = j;
                result[count++] = w;
                result[count++] = h;
                result[count++] = type;

                for (int dj = 0; dj < h; dj++)
                    for (int di = 0; di < w; di++)
                        mask[j + dj][i + di] = 0;
            }
        }

        return Arrays.copyOf(result, count);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the buffer has at least {@code needed} free slots starting at
     * {@code size}. Doubles capacity if not. Returns the (possibly new) buffer.
     */
    private static float[] ensureCapacity(float[] buf, int size, int needed) {
        if (size + needed <= buf.length) return buf;
        return Arrays.copyOf(buf, Math.max(buf.length * 2, size + needed));
    }

    /**
     * Checks whether the block at the given local chunk coordinates is air.
     * Fast path for in-bounds coordinates; neighbor snapshot for cross-boundary.
     * Thread-safe — reads only from chunk and the immutable neighbors snapshot.
     *
     * @param chunk     the chunk being meshed
     * @param pos       the chunk's world grid position
     * @param neighbors snapshot of face-adjacent neighbor chunks
     * @param lx        local X (may be -1 or Chunk.SIZE to cross a boundary)
     * @param ly        local Y
     * @param lz        local Z
     * @return true if the position is air or in an unloaded/missing neighbor
     */
    private static boolean isAirAt(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                    int lx, int ly, int lz) {
        if (lx >= 0 && lx < Chunk.SIZE
         && ly >= 0 && ly < Chunk.SIZE
         && lz >= 0 && lz < Chunk.SIZE) {
            return chunk.isAir(lx, ly, lz);
        }

        int worldX = pos.worldX() + lx;
        int worldY = pos.worldY() + ly;
        int worldZ = pos.worldZ() + lz;

        Chunk neighbor = neighbors.get(new ChunkPos(
            Math.floorDiv(worldX, Chunk.SIZE),
            Math.floorDiv(worldY, Chunk.SIZE),
            Math.floorDiv(worldZ, Chunk.SIZE)
        ));
        if (neighbor == null) return true;

        return neighbor.isAir(
            Math.floorMod(worldX, Chunk.SIZE),
            Math.floorMod(worldY, Chunk.SIZE),
            Math.floorMod(worldZ, Chunk.SIZE)
        );
    }

    /**
     * Writes a quad as two triangles into buf at position size.
     * Returns the new size after writing 36 floats (6 vertices × 6 floats).
     * Vertices must be in CCW order when viewed from outside the face.
     *
     * @param buf   the output buffer — must have at least 36 free slots at size
     * @param size  current write position
     * @param color base block color [r, g, b]
     * @param shade brightness multiplier for this face direction
     * @return new size after writing
     */
    private static int emitQuad(float[] buf, int size,
                                 float[] color, float shade,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3) {
        float r = color[0] * shade;
        float g = color[1] * shade;
        float b = color[2] * shade;

        // Triangle 1: v0, v1, v2
        buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r; buf[size++]=g; buf[size++]=b;
        buf[size++]=x1; buf[size++]=y1; buf[size++]=z1; buf[size++]=r; buf[size++]=g; buf[size++]=b;
        buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r; buf[size++]=g; buf[size++]=b;
        // Triangle 2: v0, v2, v3
        buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r; buf[size++]=g; buf[size++]=b;
        buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r; buf[size++]=g; buf[size++]=b;
        buf[size++]=x3; buf[size++]=y3; buf[size++]=z3; buf[size++]=r; buf[size++]=g; buf[size++]=b;

        return size;
    }
}