package com.voxelgame.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Holds a mesh — interleaved vertex data uploaded to the GPU via a VAO, VBO, and EBO.
 *
 * <h3>Vertex layout</h3>
 * <pre>
 *   [x, y, z, r, g, b, u, v, layer] — 9 floats, 36 bytes per vertex.
 *
 *   Attribute slot 0 — position  (vec3,  offset  0)
 *   Attribute slot 1 — color     (vec3,  offset 12)   AO+directional shade, greyscale
 *   Attribute slot 2 — texCoord  (vec2,  offset 24)   UV in tile units (0→w, 0→h)
 *   Attribute slot 3 — texLayer  (float, offset 32)   texture array layer index
 * </pre>
 *
 * <h3>UV tiling</h3>
 * UV coordinates are in tile units rather than 0–1. A greedy-merged quad spanning
 * w×h blocks has UVs 0→w (U) and 0→h (V). The fragment shader applies {@code fract()}
 * before sampling, so the tile repeats seamlessly across the full quad width.
 *
 * <h3>Indexed rendering</h3>
 * Input is the same interleaved float array that {@link com.voxelgame.game.ChunkMesher}
 * produces — 6 vertices per quad (two triangles with duplicated corners). This class
 * converts internally to 4 unique vertices + an index list, reducing vertex data by ~33%.
 */
public class Mesh {

    /** Number of floats per vertex: position(3) + color(3) + uv(2) + layer(1). */
    private static final int FLOATS_PER_VERTEX = 9;

    /** Vertices emitted per quad by ChunkMesher (two triangles, corners duplicated). */
    private static final int VERTS_PER_QUAD_IN  = 6;

    /** Unique vertices per quad after deduplication. */
    private static final int VERTS_PER_QUAD_OUT = 4;

    /** Indices per quad — two triangles × 3 indices = 6. */
    private static final int INDICES_PER_QUAD   = 6;

    /** Byte stride between the start of consecutive vertices: 9 floats × 4 bytes. */
    private static final int STRIDE = FLOATS_PER_VERTEX * Float.BYTES;

    private final int vaoId;
    private final int vboId;
    private final int eboId;
    private final int indexCount;

    /**
     * Converts ChunkMesher's interleaved vertex array into deduplicated vertex +
     * index data and uploads both to the GPU.
     *
     * <p>Input format: groups of 6 vertices per quad, where each group encodes
     * two triangles. The normal split is [v0, v1, v2, v0, v2, v3]; the diagonal-flipped
     * split is [v1, v2, v3, v1, v3, v0]. In both cases unique corners are at
     * positions 0, 1, 2, 5 within the group.
     *
     * @param vertices interleaved float array from ChunkMesher —
     *                 [x,y,z,r,g,b,u,v,layer, x,y,z,r,g,b,u,v,layer, ...]
     *                 length must be a multiple of (VERTS_PER_QUAD_IN * FLOATS_PER_VERTEX)
     */
    public Mesh(float[] vertices) {
        int quadCount = vertices.length / (VERTS_PER_QUAD_IN * FLOATS_PER_VERTEX);
        indexCount    = quadCount * INDICES_PER_QUAD;

        float[] uniqueVerts = new float[quadCount * VERTS_PER_QUAD_OUT * FLOATS_PER_VERTEX];
        int[]   indices     = new int[indexCount];

        int vOut = 0;
        int iOut = 0;

        for (int q = 0; q < quadCount; q++) {
            int inBase  = q * VERTS_PER_QUAD_IN  * FLOATS_PER_VERTEX;
            int outBase = q * VERTS_PER_QUAD_OUT;

            // Extract the 4 unique corners from the 6-vertex input.
            // Unique corners are always at positions 0, 1, 2, 5 regardless of
            // whether the normal or flipped diagonal split was used.
            System.arraycopy(vertices, inBase + 0 * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX;
            System.arraycopy(vertices, inBase + 1 * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX;
            System.arraycopy(vertices, inBase + 2 * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX;
            System.arraycopy(vertices, inBase + 5 * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX;

            // Two triangles referencing the 4 unique corners
            indices[iOut++] = outBase;
            indices[iOut++] = outBase + 1;
            indices[iOut++] = outBase + 2;
            indices[iOut++] = outBase;
            indices[iOut++] = outBase + 2;
            indices[iOut++] = outBase + 3;
        }

        // --- GPU upload ---
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(uniqueVerts.length);
        vertexBuffer.put(uniqueVerts).flip();
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(vertexBuffer);

        IntBuffer indexBuffer = MemoryUtil.memAllocInt(indices.length);
        indexBuffer.put(indices).flip();
        eboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(indexBuffer);

        // Attribute 0 — position: 3 floats at byte offset 0
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0);
        GL20.glEnableVertexAttribArray(0);

        // Attribute 1 — color: 3 floats at byte offset 12
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, STRIDE, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        // Attribute 2 — texCoord: 2 floats at byte offset 24
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 6 * Float.BYTES);
        GL20.glEnableVertexAttribArray(2);

        // Attribute 3 — texLayer: 1 float at byte offset 32
        // Passed as float and used as vec3.z in texture() — OpenGL rounds it to the
        // nearest integer layer index when sampling the array.
        GL20.glVertexAttribPointer(3, 1, GL11.GL_FLOAT, false, STRIDE, 8 * Float.BYTES);
        GL20.glEnableVertexAttribArray(3);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        // IMPORTANT: do NOT unbind GL_ELEMENT_ARRAY_BUFFER while the VAO is active —
        // that removes the EBO association from the VAO silently.
    }

    /**
     * Issues the indexed draw call for this mesh.
     * The correct shader program must be bound and the texture array must be
     * bound to texture unit 0 before calling this.
     */
    public void render() {
        GL30.glBindVertexArray(vaoId);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Deletes the VAO, VBO, and EBO from GPU memory. Call on shutdown.
     */
    public void cleanup() {
        GL15.glDeleteBuffers(eboId);
        GL15.glDeleteBuffers(vboId);
        GL30.glDeleteVertexArrays(vaoId);
    }
}