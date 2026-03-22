package com.voxelgame.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Holds a mesh — interleaved vertex data (position + color) uploaded to the GPU
 * via a VAO, VBO, and EBO (Element Buffer Object).
 *
 * <p>Vertex layout: [x, y, z, r, g, b] — 6 floats, 24 bytes per vertex.
 * Attribute slot 0 = position (vec3), attribute slot 1 = color (vec3).
 *
 * <p>Input is the same interleaved float array that ChunkMesher produces —
 * 6 vertices per quad (two triangles with duplicated corners). Mesh converts
 * this internally to 4 unique vertices per quad plus an index list, reducing
 * vertex data by ~33% before upload.
 *
 * <p>Index type is GL_UNSIGNED_INT, supporting up to ~4 billion unique vertices
 * per mesh — well beyond any practical chunk size.
 */
public class Mesh {

    private static final int FLOATS_PER_VERTEX = 6;

    /**
     * Vertices emitted per quad by ChunkMesher (two triangles, corners duplicated).
     * Layout: v0 v1 v2 v0 v2 v3 — corners 0 and 2 appear twice each.
     */
    private static final int VERTS_PER_QUAD_IN  = 6;

    /** Unique vertices per quad after deduplication. */
    private static final int VERTS_PER_QUAD_OUT = 4;

    /** Indices per quad — two triangles, each referencing 3 of the 4 unique corners. */
    private static final int INDICES_PER_QUAD   = 6;

    /** VAO id — records the vertex format so we don't re-specify it on every draw. */
    private final int vaoId;

    /** VBO id — the unique vertex data on the GPU. */
    private final int vboId;

    /** EBO id — the index list telling the GPU which vertices form each triangle. */
    private final int eboId;

    /** Number of indices to pass to glDrawElements. */
    private final int indexCount;

    /**
     * Converts ChunkMesher's interleaved vertex array into deduplicated vertex +
     * index data and uploads both to the GPU.
     *
     * <p>Input format: groups of 6 vertices per quad, where each group encodes
     * two triangles as [v0, v1, v2, v0, v2, v3]. Positions 0, 3 are identical
     * (corner 0) and positions 2, 4 are identical (corner 2).
     *
     * @param vertices interleaved float array from ChunkMesher —
     *                 [x, y, z, r, g, b, x, y, z, r, g, b, ...]
     *                 length must be a multiple of (VERTS_PER_QUAD_IN * FLOATS_PER_VERTEX)
     */
    public Mesh(float[] vertices) {
        int quadCount  = vertices.length / (VERTS_PER_QUAD_IN * FLOATS_PER_VERTEX);
        indexCount     = quadCount * INDICES_PER_QUAD;

        // --- Build deduplicated vertex array and index array ---
        // For each quad we extract 4 unique corner vertices and emit 6 indices.
        float[] uniqueVerts = new float[quadCount * VERTS_PER_QUAD_OUT * FLOATS_PER_VERTEX];
        int[]   indices     = new int[indexCount];

        int vOut = 0; // write cursor into uniqueVerts
        int iOut = 0; // write cursor into indices

        for (int q = 0; q < quadCount; q++) {
            int inBase  = q * VERTS_PER_QUAD_IN  * FLOATS_PER_VERTEX;
            int outBase = q * VERTS_PER_QUAD_OUT; // base index into the unique vertex table

            // Extract the 4 unique corners from the 6-vertex input.
            // Input order: v0 v1 v2 v0 v2 v3 — corners at positions 0,1,2,5.
            // Corner 0 = input vertex 0, corner 1 = input vertex 1,
            // corner 2 = input vertex 2, corner 3 = input vertex 5.
            System.arraycopy(vertices, inBase + 0  * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX; // corner 0
            System.arraycopy(vertices, inBase + 1  * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX; // corner 1
            System.arraycopy(vertices, inBase + 2  * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX; // corner 2
            System.arraycopy(vertices, inBase + 5  * FLOATS_PER_VERTEX, uniqueVerts, vOut, FLOATS_PER_VERTEX); vOut += FLOATS_PER_VERTEX; // corner 3

            // Two triangles referencing the 4 unique corners.
            // Triangle 1: corners 0,1,2 — Triangle 2: corners 0,2,3
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

        // Upload unique vertex data to VBO
        FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(uniqueVerts.length);
        vertexBuffer.put(uniqueVerts).flip();
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(vertexBuffer);

        // Upload index list to EBO.
        // Binding GL_ELEMENT_ARRAY_BUFFER while a VAO is active stores the EBO
        // association in the VAO — no need to rebind the EBO at draw time.
        IntBuffer indexBuffer = MemoryUtil.memAllocInt(indices.length);
        indexBuffer.put(indices).flip();
        eboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, eboId);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffer, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(indexBuffer);

        // Stride = 24 bytes (6 floats × 4 bytes) — gap between vertex starts
        int stride = FLOATS_PER_VERTEX * Float.BYTES;

        // Attribute 0 — position: 3 floats starting at byte 0
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);

        // Attribute 1 — color: 3 floats starting at byte 12 (after position)
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        // Note: do NOT unbind GL_ELEMENT_ARRAY_BUFFER while the VAO is bound —
        // that would remove the EBO association from the VAO.
    }

    /**
     * Issues the indexed draw call for this mesh.
     * The correct shader program must be bound before calling this.
     */
    public void render() {
        GL30.glBindVertexArray(vaoId);
        // GL_UNSIGNED_INT matches the int[] index array uploaded in the constructor.
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