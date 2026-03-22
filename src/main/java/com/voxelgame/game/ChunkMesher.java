package com.voxelgame.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates an interleaved vertex array (position + color) from a Chunk.
 *
 * <p>Only visible faces are emitted. A face is skipped if the neighbor in that
 * direction is a solid block. Neighbor checks that cross a chunk boundary are
 * resolved via the {@link World} — this eliminates the seam faces that were
 * previously generated between adjacent loaded chunks.
 *
 * <p>Each face is shaded by a directional brightness multiplier to give cheap
 * depth cues without a lighting system: top = full, sides = dimmed, bottom = darkest.
 *
 * <p>Output format per vertex: [x, y, z, r, g, b] — 6 floats, 6 vertices per face.
 */
public class ChunkMesher {

    // Directional brightness multipliers — applied to block color at mesh-build time.
    // Gives the illusion of a sun overhead without any runtime lighting.
    private static final float SHADE_TOP    = 1.0f;
    private static final float SHADE_SIDE   = 0.7f;
    private static final float SHADE_BOTTOM = 0.5f;

    /**
     * Generates the visible mesh for the given chunk.
     *
     * @param chunk the chunk to mesh
     * @param pos   the chunk's position in the world grid — used to resolve
     *              cross-boundary neighbor lookups into world-space coordinates
     * @param world the world — queried when a neighbor check crosses a chunk boundary
     * @return interleaved float array [x, y, z, r, g, b, ...] — 6 floats per vertex
     */
    public static float[] mesh(Chunk chunk, ChunkPos pos, World world) {
        List<Float> vertices = new ArrayList<>();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    if (chunk.isAir(x, y, z)) continue;

                    float[] color = chunk.getBlock(x, y, z).color();

                    // For each of the six faces, check whether the neighbor in
                    // that direction is air. isAirAt() handles the cross-boundary
                    // case transparently — callers here don't need to know.

                    // TOP face (+Y) — visible if block above is air
                    if (isAirAt(chunk, pos, world, x, y + 1, z)) {
                        emitQuad(vertices, color, SHADE_TOP,
                            x,     y + 1, z,
                            x,     y + 1, z + 1,
                            x + 1, y + 1, z + 1,
                            x + 1, y + 1, z
                        );
                    }
                    // BOTTOM face (-Y)
                    if (isAirAt(chunk, pos, world, x, y - 1, z)) {
                        emitQuad(vertices, color, SHADE_BOTTOM,
                            x,     y, z + 1,
                            x,     y, z,
                            x + 1, y, z,
                            x + 1, y, z + 1
                        );
                    }
                    // NORTH face (-Z)
                    if (isAirAt(chunk, pos, world, x, y, z - 1)) {
                        emitQuad(vertices, color, SHADE_SIDE,
                            x,     y,     z,
                            x,     y + 1, z,
                            x + 1, y + 1, z,
                            x + 1, y,     z
                        );
                    }
                    // SOUTH face (+Z)
                    if (isAirAt(chunk, pos, world, x, y, z + 1)) {
                        emitQuad(vertices, color, SHADE_SIDE,
                            x + 1, y,     z + 1,
                            x + 1, y + 1, z + 1,
                            x,     y + 1, z + 1,
                            x,     y,     z + 1
                        );
                    }
                    // EAST face (+X)
                    if (isAirAt(chunk, pos, world, x + 1, y, z)) {
                        emitQuad(vertices, color, SHADE_SIDE,
                            x + 1, y,     z,
                            x + 1, y + 1, z,
                            x + 1, y + 1, z + 1,
                            x + 1, y,     z + 1
                        );
                    }
                    // WEST face (-X)
                    if (isAirAt(chunk, pos, world, x - 1, y, z)) {
                        emitQuad(vertices, color, SHADE_SIDE,
                            x, y,     z + 1,
                            x, y + 1, z + 1,
                            x, y + 1, z,
                            x, y,     z
                        );
                    }
                }
            }
        }

        // Convert List<Float> to float[]
        float[] result = new float[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) {
            result[i] = vertices.get(i);
        }
        return result;
    }

    /**
     * Checks whether the block at the given local chunk coordinates is air,
     * crossing into the World if the coordinates fall outside this chunk's bounds.
     *
     * <p>When the neighbor is within the chunk, this is a direct array lookup —
     * fast, no HashMap overhead. When the neighbor is in an adjacent chunk, the
     * world is queried. {@link World#getBlock} returns {@link Block#AIR} for
     * unloaded chunks, so faces at the edge of the loaded world are always emitted
     * (the world boundary is treated as open air, which is the correct visual result).
     *
     * @param chunk  the chunk being meshed
     * @param pos    the chunk's world grid position
     * @param world  the world, for cross-boundary lookups
     * @param lx     local X coordinate (may be -1 or Chunk.SIZE to cross a boundary)
     * @param ly     local Y coordinate
     * @param lz     local Z coordinate
     * @return true if the neighbor position is air or unloaded
     */
    private static boolean isAirAt(Chunk chunk, ChunkPos pos, World world,
                                    int lx, int ly, int lz) {
        if (lx >= 0 && lx < Chunk.SIZE
         && ly >= 0 && ly < Chunk.SIZE
         && lz >= 0 && lz < Chunk.SIZE) {
            // Neighbor is inside this chunk — fast path, no world lookup needed
            return chunk.isAir(lx, ly, lz);
        }
        // Neighbor is in an adjacent chunk — convert to world space and query
        return world.getBlock(
            pos.worldX() + lx,
            pos.worldY() + ly,
            pos.worldZ() + lz
        ) == Block.AIR;
    }

    /**
     * Emits a quad as two triangles with a shaded color into the vertex list.
     * Vertices must be in CCW order when viewed from outside.
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