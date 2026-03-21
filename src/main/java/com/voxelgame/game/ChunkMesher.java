package com.voxelgame.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates an interleaved vertex array (position + color) from a Chunk.
 *
 * <p>Only visible faces are emitted — a face is skipped if the neighbor in that
 * direction is a solid block. Each face is shaded by a directional brightness
 * multiplier to give cheap depth cues without a lighting system:
 * top = full, sides = dimmed, bottom = darkest.
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
     * @return interleaved float array [x, y, z, r, g, b, ...] — 6 floats per vertex
     */
    public static float[] mesh(Chunk chunk) {
        List<Float> vertices = new ArrayList<>();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    if (chunk.isAir(x, y, z)) continue;

                    float[] color = chunk.getBlock(x, y, z).color();

                    // TOP face (+Y) — visible if block above is air
                    if (chunk.isAir(x, y + 1, z)) {
                        emitQuad(vertices, color, SHADE_TOP,
                            x,     y + 1, z,
                            x,     y + 1, z + 1,
                            x + 1, y + 1, z + 1,
                            x + 1, y + 1, z
                        );
                    }
                    // BOTTOM face (-Y)
                    if (chunk.isAir(x, y - 1, z)) {
                        emitQuad(vertices, color, SHADE_BOTTOM,
                            x,     y, z + 1,
                            x,     y, z,
                            x + 1, y, z,
                            x + 1, y, z + 1
                        );
                    }
                    // NORTH face (-Z)
                    if (chunk.isAir(x, y, z - 1)) {
                        emitQuad(vertices, color, SHADE_SIDE,
                            x,     y,     z,
                            x,     y + 1, z,
                            x + 1, y + 1, z,
                            x + 1, y,     z
                        );
                    }
                    // SOUTH face (+Z)
                    if (chunk.isAir(x, y, z + 1)) {
                        emitQuad(vertices, color, SHADE_SIDE,
                            x + 1, y,     z + 1,
                            x + 1, y + 1, z + 1,
                            x,     y + 1, z + 1,
                            x,     y,     z + 1
                        );
                    }
                    // EAST face (+X)
                    if (chunk.isAir(x + 1, y, z)) {
                        emitQuad(vertices, color, SHADE_SIDE,
                            x + 1, y,     z,
                            x + 1, y + 1, z,
                            x + 1, y + 1, z + 1,
                            x + 1, y,     z + 1
                        );
                    }
                    // WEST face (-X)
                    if (chunk.isAir(x - 1, y, z)) {
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