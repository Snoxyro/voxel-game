package com.voxelgame.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Holds a mesh — interleaved vertex data (position + color) uploaded to the GPU
 * via a VAO and VBO.
 *
 * <p>Vertex layout: [x, y, z, r, g, b] — 6 floats, 24 bytes per vertex.
 * Attribute slot 0 = position (vec3), attribute slot 1 = color (vec3).
 */
public class Mesh {

    /** VAO id — remembers the format/layout of our vertex data on the GPU. */
    private final int vaoId;

    /** VBO id — the actual vertex data buffer on the GPU. */
    private final int vboId;

    /** How many vertices this mesh has. */
    private final int vertexCount;

    /**
     * Uploads interleaved vertex data to the GPU.
     *
     * @param vertices interleaved float array: [x, y, z, r, g, b, x, y, z, r, g, b, ...]
     */
    public Mesh(float[] vertices) {
        vertexCount = vertices.length / 6; // 6 floats per vertex (x, y, z, r, g, b)

        // Create and bind the VAO first — everything we set up next gets remembered by it
        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // Allocate a direct (off-heap) FloatBuffer — required because LWJGL passes
        // data directly to native OpenGL, which can't read regular Java heap memory
        FloatBuffer vertexBuffer = MemoryUtil.memAllocFloat(vertices.length);
        vertexBuffer.put(vertices).flip(); // flip() resets the read position to 0

        // Create the VBO, bind it, and upload our vertex data to GPU memory
        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STATIC_DRAW);

        // Free the off-heap buffer — data is now on the GPU, we don't need this copy
        MemoryUtil.memFree(vertexBuffer);

        // Stride = 24 bytes (6 floats × 4 bytes each) — the gap between the start
        // of one vertex and the start of the next
        int stride = 6 * Float.BYTES;

        // Attribute 0 — position: 3 floats, starts at byte 0
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);

        // Attribute 1 — color: 3 floats, starts at byte 12 (after the 3 position floats)
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Issues the draw call for this mesh.
     * The correct shader program must be bound before calling this.
     */
    public void render() {
        GL30.glBindVertexArray(vaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
        GL30.glBindVertexArray(0);
    }

    /**
     * Deletes the VAO and VBO from GPU memory. Call on shutdown.
     */
    public void cleanup() {
        GL15.glDeleteBuffers(vboId);
        GL30.glDeleteVertexArrays(vaoId);
    }
}