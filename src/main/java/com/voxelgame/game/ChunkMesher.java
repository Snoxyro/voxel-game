package com.voxelgame.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates an interleaved vertex array (position + color) from a Chunk
 * using greedy meshing.
 *
 * <h3>How greedy meshing works</h3>
 * For each of the six face directions, each layer along that direction's axis
 * is scanned independently. A 2D mask records which cells have a visible face
 * and what block type they belong to. The greedy merge pass then finds the
 * largest axis-aligned rectangle of identical type, emits a single quad for it,
 * marks those cells consumed, and repeats until the layer is fully processed.
 *
 * <p>On a flat 16×16 surface this collapses 256 quads into 1. On typical
 * terrain with mixed block types the reduction is significant but smaller.
 *
 * <h3>Texture note</h3>
 * Greedy meshing works cleanly with vertex colors because color is uniform
 * across a face. When textures are added later, merged quads will need tiled
 * UVs — the mesher will require a second pass at that point.
 *
 * <h3>Output format</h3>
 * Same as before: interleaved [x, y, z, r, g, b] — 6 floats, 6 vertices per
 * quad (two triangles). Mesh.java converts these to indexed geometry internally.
 */
public class ChunkMesher {

    // Directional brightness multipliers — applied to block color at mesh-build time.
    private static final float SHADE_TOP    = 1.0f;
    private static final float SHADE_SIDE   = 0.7f;
    private static final float SHADE_BOTTOM = 0.5f;

    /**
     * Generates the greedy-merged visible mesh for the given chunk.
     *
     * @param chunk the chunk to mesh
     * @param pos   the chunk's position in the world grid
     * @param world the world — queried for cross-boundary neighbor lookups
     * @return interleaved float array [x, y, z, r, g, b, ...] — 6 floats per vertex
     */
    public static float[] mesh(Chunk chunk, ChunkPos pos, World world) {
        List<Float> vertices = new ArrayList<>();

        // One mask array is allocated and reused across all six directions and
        // all layers within each direction. Each layer rebuilds it from scratch,
        // so dirty state from buildMergedQuads (which zeroes consumed cells) is
        // always overwritten before the next layer reads it.
        int[][] mask = new int[Chunk.SIZE][Chunk.SIZE];

        meshTopFaces   (chunk, pos, world, vertices, mask);
        meshBottomFaces(chunk, pos, world, vertices, mask);
        meshNorthFaces (chunk, pos, world, vertices, mask);
        meshSouthFaces (chunk, pos, world, vertices, mask);
        meshEastFaces  (chunk, pos, world, vertices, mask);
        meshWestFaces  (chunk, pos, world, vertices, mask);

        float[] result = new float[vertices.size()];
        for (int k = 0; k < vertices.size(); k++) result[k] = vertices.get(k);
        return result;
    }

    // -------------------------------------------------------------------------
    // Per-direction mesh passes
    // Each pass loops over layers along its axis, builds a 2D mask (i, j), runs
    // the greedy merge, and emits quads with the correct CCW winding for that
    // face direction. Winding orders match the original per-block quads exactly.
    // -------------------------------------------------------------------------

    /**
     * TOP faces (+Y): scan x/z plane per y layer. Face sits at y+1.
     * mask axes: i = x, j = z.
     */
    private static void meshTopFaces(Chunk chunk, ChunkPos pos, World world,
                                      List<Float> vertices, int[][] mask) {
        for (int y = 0; y < Chunk.SIZE; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[z][x] = (b != Block.AIR && isAirAt(chunk, pos, world, x, y + 1, z))
                            ? b.ordinal() + 1 : 0;
                }
            }
            for (int[] q : buildMergedQuads(mask)) {
                int i = q[0], j = q[1], w = q[2], h = q[3];
                float[] color = Block.values()[q[4] - 1].color();
                emitQuad(vertices, color, SHADE_TOP,
                        i,     y + 1, j,
                        i,     y + 1, j + h,
                        i + w, y + 1, j + h,
                        i + w, y + 1, j);
            }
        }
    }

    /**
     * BOTTOM faces (-Y): scan x/z plane per y layer. Face sits at y.
     * mask axes: i = x, j = z.
     */
    private static void meshBottomFaces(Chunk chunk, ChunkPos pos, World world,
                                         List<Float> vertices, int[][] mask) {
        for (int y = 0; y < Chunk.SIZE; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[z][x] = (b != Block.AIR && isAirAt(chunk, pos, world, x, y - 1, z))
                            ? b.ordinal() + 1 : 0;
                }
            }
            for (int[] q : buildMergedQuads(mask)) {
                int i = q[0], j = q[1], w = q[2], h = q[3];
                float[] color = Block.values()[q[4] - 1].color();
                // Bottom winding is the reverse of top (CCW from below)
                emitQuad(vertices, color, SHADE_BOTTOM,
                        i,     y, j + h,
                        i,     y, j,
                        i + w, y, j,
                        i + w, y, j + h);
            }
        }
    }

    /**
     * NORTH faces (-Z): scan x/y plane per z layer. Face sits at z.
     * mask axes: i = x, j = y.
     */
    private static void meshNorthFaces(Chunk chunk, ChunkPos pos, World world,
                                        List<Float> vertices, int[][] mask) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][x] = (b != Block.AIR && isAirAt(chunk, pos, world, x, y, z - 1))
                            ? b.ordinal() + 1 : 0;
                }
            }
            for (int[] q : buildMergedQuads(mask)) {
                int i = q[0], j = q[1], w = q[2], h = q[3];
                float[] color = Block.values()[q[4] - 1].color();
                emitQuad(vertices, color, SHADE_SIDE,
                        i,     j,     z,
                        i,     j + h, z,
                        i + w, j + h, z,
                        i + w, j,     z);
            }
        }
    }

    /**
     * SOUTH faces (+Z): scan x/y plane per z layer. Face sits at z+1.
     * mask axes: i = x, j = y.
     */
    private static void meshSouthFaces(Chunk chunk, ChunkPos pos, World world,
                                        List<Float> vertices, int[][] mask) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][x] = (b != Block.AIR && isAirAt(chunk, pos, world, x, y, z + 1))
                            ? b.ordinal() + 1 : 0;
                }
            }
            for (int[] q : buildMergedQuads(mask)) {
                int i = q[0], j = q[1], w = q[2], h = q[3];
                float[] color = Block.values()[q[4] - 1].color();
                // South winding reverses x vs north (CCW from +Z side)
                emitQuad(vertices, color, SHADE_SIDE,
                        i + w, j,     z + 1,
                        i + w, j + h, z + 1,
                        i,     j + h, z + 1,
                        i,     j,     z + 1);
            }
        }
    }

    /**
     * EAST faces (+X): scan z/y plane per x layer. Face sits at x+1.
     * mask axes: i = z, j = y.
     */
    private static void meshEastFaces(Chunk chunk, ChunkPos pos, World world,
                                       List<Float> vertices, int[][] mask) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][z] = (b != Block.AIR && isAirAt(chunk, pos, world, x + 1, y, z))
                            ? b.ordinal() + 1 : 0;
                }
            }
            for (int[] q : buildMergedQuads(mask)) {
                int i = q[0], j = q[1], w = q[2], h = q[3]; // i = z, j = y
                float[] color = Block.values()[q[4] - 1].color();
                emitQuad(vertices, color, SHADE_SIDE,
                        x + 1, j,     i,
                        x + 1, j + h, i,
                        x + 1, j + h, i + w,
                        x + 1, j,     i + w);
            }
        }
    }

    /**
     * WEST faces (-X): scan z/y plane per x layer. Face sits at x.
     * mask axes: i = z, j = y.
     */
    private static void meshWestFaces(Chunk chunk, ChunkPos pos, World world,
                                       List<Float> vertices, int[][] mask) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    Block b = chunk.getBlock(x, y, z);
                    mask[y][z] = (b != Block.AIR && isAirAt(chunk, pos, world, x - 1, y, z))
                            ? b.ordinal() + 1 : 0;
                }
            }
            for (int[] q : buildMergedQuads(mask)) {
                int i = q[0], j = q[1], w = q[2], h = q[3]; // i = z, j = y
                float[] color = Block.values()[q[4] - 1].color();
                // West winding reverses z vs east (CCW from -X side)
                emitQuad(vertices, color, SHADE_SIDE,
                        x, j,     i + w,
                        x, j + h, i + w,
                        x, j + h, i,
                        x, j,     i);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Greedy merge
    // -------------------------------------------------------------------------

    /**
     * Runs the greedy merge algorithm on a SIZE×SIZE mask.
     *
     * <p>Scans left-to-right, top-to-bottom. When a non-zero cell is found,
     * extends right while the type matches, then extends down while the entire
     * row width still matches. Emits the rectangle and zeroes the consumed cells.
     *
     * <p>This method modifies the mask in place. Callers must rebuild the mask
     * before the next layer — which the face methods already do.
     *
     * @param mask SIZE×SIZE mask — 0 = no face, positive = block ordinal + 1
     * @return list of [i, j, w, h, type] — one entry per merged quad
     */
    private static List<int[]> buildMergedQuads(int[][] mask) {
        List<int[]> quads = new ArrayList<>();

        for (int j = 0; j < Chunk.SIZE; j++) {
            for (int i = 0; i < Chunk.SIZE; i++) {
                int type = mask[j][i];
                if (type == 0) continue;

                // Extend right while the same type continues
                int w = 1;
                while (i + w < Chunk.SIZE && mask[j][i + w] == type) w++;

                // Extend down while the full width row still matches
                int h = 1;
                outer:
                while (j + h < Chunk.SIZE) {
                    for (int k = 0; k < w; k++) {
                        if (mask[j + h][i + k] != type) break outer;
                    }
                    h++;
                }

                quads.add(new int[]{ i, j, w, h, type });

                // Zero consumed cells — they won't be revisited because the
                // scan pointer has already passed i, and rows below j+h have
                // not been reached yet
                for (int dj = 0; dj < h; dj++)
                    for (int di = 0; di < w; di++)
                        mask[j + dj][i + di] = 0;
            }
        }

        return quads;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Checks whether the block at the given local chunk coordinates is air,
     * crossing into the World if the coordinates fall outside this chunk's bounds.
     *
     * @param chunk the chunk being meshed
     * @param pos   the chunk's world grid position
     * @param world the world, for cross-boundary lookups
     * @param lx    local X (may be -1 or Chunk.SIZE to cross a boundary)
     * @param ly    local Y
     * @param lz    local Z
     * @return true if the neighbor position is air or unloaded
     */
    private static boolean isAirAt(Chunk chunk, ChunkPos pos, World world,
                                    int lx, int ly, int lz) {
        if (lx >= 0 && lx < Chunk.SIZE
         && ly >= 0 && ly < Chunk.SIZE
         && lz >= 0 && lz < Chunk.SIZE) {
            return chunk.isAir(lx, ly, lz);
        }
        return world.getBlock(
                pos.worldX() + lx,
                pos.worldY() + ly,
                pos.worldZ() + lz
        ) == Block.AIR;
    }

    /**
     * Emits a quad as two triangles into the vertex list.
     * Vertices must be in CCW order when viewed from outside the face.
     *
     * @param vertices the list to append into
     * @param color    the base block color [r, g, b]
     * @param shade    brightness multiplier for this face direction
     */
    private static void emitQuad(List<Float> vertices,
                                  float[] color, float shade,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3) {
        float r = color[0] * shade;
        float g = color[1] * shade;
        float b = color[2] * shade;

        // Triangle 1: v0, v1, v2
        vertices.add(x0); vertices.add(y0); vertices.add(z0); vertices.add(r); vertices.add(g); vertices.add(b);
        vertices.add(x1); vertices.add(y1); vertices.add(z1); vertices.add(r); vertices.add(g); vertices.add(b);
        vertices.add(x2); vertices.add(y2); vertices.add(z2); vertices.add(r); vertices.add(g); vertices.add(b);
        // Triangle 2: v0, v2, v3
        vertices.add(x0); vertices.add(y0); vertices.add(z0); vertices.add(r); vertices.add(g); vertices.add(b);
        vertices.add(x2); vertices.add(y2); vertices.add(z2); vertices.add(r); vertices.add(g); vertices.add(b);
        vertices.add(x3); vertices.add(y3); vertices.add(z3); vertices.add(r); vertices.add(g); vertices.add(b);
    }
}