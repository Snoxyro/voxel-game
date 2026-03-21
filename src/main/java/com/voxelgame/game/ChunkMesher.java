package com.voxelgame.game;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates a vertex array from a Chunk by emitting geometry only for visible faces.
 *
 * <p>For each solid block, each of its 6 faces is checked against its neighbor.
 * A face is only emitted if the neighbor in that direction is air. This avoids
 * generating hidden interior geometry.
 *
 * <p>Output is a flat float array: [x, y, z, x, y, z, ...] suitable for uploading
 * directly to a Mesh.
 */
public class ChunkMesher {

    /**
     * Generates the visible mesh for the given chunk.
     *
     * @param chunk the chunk to mesh
     * @return flat float array of vertex positions — 3 floats per vertex, 6 vertices per face
     */
    public static float[] mesh(Chunk chunk) {
        List<Float> vertices = new ArrayList<>();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    if (chunk.isAir(x, y, z)) continue;

                    // TOP face (+Y) — visible if block above is air
                    if (chunk.isAir(x, y + 1, z)) {
                        emitQuad(vertices,
                            x,     y + 1, z,
                            x,     y + 1, z + 1,
                            x + 1, y + 1, z + 1,
                            x + 1, y + 1, z
                        );
                    }
                    // BOTTOM face (-Y)
                    if (chunk.isAir(x, y - 1, z)) {
                        emitQuad(vertices,
                            x,     y, z + 1,
                            x,     y, z,
                            x + 1, y, z,
                            x + 1, y, z + 1
                        );
                    }
                    // NORTH face (-Z)
                    if (chunk.isAir(x, y, z - 1)) {
                        emitQuad(vertices,
                            x,     y,     z,
                            x,     y + 1, z,
                            x + 1, y + 1, z,
                            x + 1, y,     z
                        );
                    }
                    // SOUTH face (+Z)
                    if (chunk.isAir(x, y, z + 1)) {
                        emitQuad(vertices,
                            x + 1, y,     z + 1,
                            x + 1, y + 1, z + 1,
                            x,     y + 1, z + 1,
                            x,     y,     z + 1
                        );
                    }
                    // EAST face (+X)
                    if (chunk.isAir(x + 1, y, z)) {
                        emitQuad(vertices,
                            x + 1, y,     z,
                            x + 1, y + 1, z,
                            x + 1, y + 1, z + 1,
                            x + 1, y,     z + 1
                        );
                    }
                    // WEST face (-X)
                    if (chunk.isAir(x - 1, y, z)) {
                        emitQuad(vertices,
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
     * Emits a quad as two triangles into the vertex list.
     * Vertices must be provided in CCW order when viewed from outside.
     * Triangle 1: v0, v1, v2 — Triangle 2: v0, v2, v3
     */
    private static void emitQuad(List<Float> vertices,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float x2, float y2, float z2,
                                  float x3, float y3, float z3) {
        // Triangle 1
        vertices.add(x0); vertices.add(y0); vertices.add(z0);
        vertices.add(x1); vertices.add(y1); vertices.add(z1);
        vertices.add(x2); vertices.add(y2); vertices.add(z2);
        // Triangle 2
        vertices.add(x0); vertices.add(y0); vertices.add(z0);
        vertices.add(x2); vertices.add(y2); vertices.add(z2);
        vertices.add(x3); vertices.add(y3); vertices.add(z3);
    }
}