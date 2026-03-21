package com.voxelgame.engine;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Renders a black wireframe outline around the block the player is targeting.
 *
 * <p>The outline is a unit cube slightly expanded beyond the block's boundaries
 * ({@link #EXPAND}) to prevent z-fighting with the block's own faces. It reuses
 * the main 3D shader — the shader must already be bound before calling
 * {@link #render}.
 *
 * <p>Uses {@code GL_LINES}: each pair of vertices is drawn as a line segment.
 * Face culling does not apply to lines, so no GL_CULL_FACE interaction.
 */
public class BlockHighlightRenderer {

    /**
     * How far the wireframe cube extends beyond the (0,0,0)→(1,1,1) unit block.
     * Small enough to stay tight to the block, large enough to avoid z-fighting.
     */
    private static final float EXPAND = 0.005f;

    /** 12 edges × 2 vertices per edge = 24 line segment endpoints. */
    private static final int VERTEX_COUNT = 24;

    private final int vaoId;
    private final int vboId;

    /**
     * Builds and uploads the static wireframe cube geometry to the GPU.
     */
    public BlockHighlightRenderer() {
        float[] vertices = buildWireCube(EXPAND);

        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        FloatBuffer buf = MemoryUtil.memAllocFloat(vertices.length);
        buf.put(vertices).flip();

        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(buf);

        // Matches the main shader's interleaved layout: [x, y, z, r, g, b]
        int stride = 6 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Renders the wireframe outline at the given world-space block coordinate.
     * The main 3D shader must already be bound. This method sets the model matrix
     * uniform to translate the unit cube to the block's world position.
     *
     * @param shader the currently bound shader program
     * @param blockX world-space X of the target block
     * @param blockY world-space Y of the target block
     * @param blockZ world-space Z of the target block
     */
    public void render(ShaderProgram shader, int blockX, int blockY, int blockZ) {
        Matrix4f model = new Matrix4f().translation(blockX, blockY, blockZ);
        shader.setUniform("modelMatrix", model);

        GL30.glBindVertexArray(vaoId);
        GL11.glDrawArrays(GL11.GL_LINES, 0, VERTEX_COUNT);
        GL30.glBindVertexArray(0);
    }

    /**
     * Deletes the VAO and VBO from GPU memory. Call on shutdown.
     */
    public void cleanup() {
        GL15.glDeleteBuffers(vboId);
        GL30.glDeleteVertexArrays(vaoId);
    }

    /**
     * Builds a wireframe cube as 12 line segments (24 vertices) in interleaved
     * [x, y, z, r, g, b] format. All vertices are black. The cube spans from
     * {@code -e} to {@code 1+e} on each axis, slightly overshooting the unit block.
     *
     * @param e the expansion amount beyond (0,0,0)→(1,1,1)
     * @return float array of 144 values (24 vertices × 6 floats each)
     */
    private static float[] buildWireCube(float e) {
        float mn = -e;
        float mx = 1.0f + e;
        float r = 0.0f, g = 0.0f, b = 0.0f; // black outline

        return new float[]{
            // Bottom face edges (y = mn) — 4 edges, 8 vertices
            mn, mn, mn,  r, g, b,   mx, mn, mn,  r, g, b,
            mx, mn, mn,  r, g, b,   mx, mn, mx,  r, g, b,
            mx, mn, mx,  r, g, b,   mn, mn, mx,  r, g, b,
            mn, mn, mx,  r, g, b,   mn, mn, mn,  r, g, b,
            // Top face edges (y = mx) — 4 edges, 8 vertices
            mn, mx, mn,  r, g, b,   mx, mx, mn,  r, g, b,
            mx, mx, mn,  r, g, b,   mx, mx, mx,  r, g, b,
            mx, mx, mx,  r, g, b,   mn, mx, mx,  r, g, b,
            mn, mx, mx,  r, g, b,   mn, mx, mn,  r, g, b,
            // Vertical connecting edges — 4 edges, 8 vertices
            mn, mn, mn,  r, g, b,   mn, mx, mn,  r, g, b,
            mx, mn, mn,  r, g, b,   mx, mx, mn,  r, g, b,
            mx, mn, mx,  r, g, b,   mx, mx, mx,  r, g, b,
            mn, mn, mx,  r, g, b,   mn, mx, mx,  r, g, b,
        };
    }
}