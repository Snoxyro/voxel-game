package com.voxelgame.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Holds a mesh — vertex data uploaded to the GPU via a VAO and VBO.
 * For now, supports simple float vertex arrays (positions only).
 */
public class Mesh {

    /** VAO id — remembers the format/layout of our vertex data on the GPU. */
    private final int vaoId;

    /** VBO id — the actual vertex data buffer on the GPU. */
    private final int vboId;

    /** How many vertices this mesh has. */
    private final int vertexCount;

    /**
     * Uploads the given vertex positions to the GPU.
     *
     * @param vertices flat float array of vertex positions: [x0,y0,z0, x1,y1,z1, ...]
     */
    public Mesh(float[] vertices) {
        vertexCount = vertices.length / 3; // 3 floats per vertex (x, y, z)

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

        // Tell the VAO how to interpret the VBO data:
        // - Attribute slot 0 (matches 'layout location = 0' in the vertex shader)
        // - 3 floats per vertex
        // - Not normalized
        // - Stride 0 (floats are tightly packed)
        // - Offset 0 (start from the beginning)
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 0, 0);
        GL20.glEnableVertexAttribArray(0);

        // Unbind — good practice to avoid accidentally modifying state later
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