package com.voxelgame.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * Renders 2D screen-space HUD elements using a dedicated shader that bypasses
 * the MVP matrix pipeline entirely.
 *
 * <p>Coordinates are in NDC (Normalised Device Coordinates): X and Y both span
 * [-1, 1], with (0, 0) at the screen centre. This shader receives 2D positions
 * directly — no projection or view transform is applied.
 *
 * <p>Call {@link #renderCrosshair()} after the 3D world has been rendered.
 * Depth testing is temporarily disabled so the HUD always draws on top.
 *
 * <h3>Aspect ratio correction</h3>
 * NDC x maps to the full pixel width and NDC y maps to the full pixel height.
 * For a 16:9 window, one NDC unit in x covers more pixels than one NDC unit in y.
 * The crosshair compensates by scaling vertical bar x-coordinates by (9/16)
 * and vertical bar y-coordinates by (16/9), producing equal pixel dimensions
 * on both arms. This assumes a 16:9 display.
 */
public class HudRenderer {

    /** 2 bars × 2 triangles × 3 vertices = 12 vertices total. */
    private static final int CROSSHAIR_VERTEX_COUNT = 12;

    private final ShaderProgram hudShader;
    private final int crosshairVaoId;
    private final int crosshairVboId;

    /**
     * Initialises the HUD shader and uploads the crosshair geometry to the GPU.
     */
    public HudRenderer() {
        hudShader = new ShaderProgram("/shaders/hud.vert", "/shaders/hud.frag");

        float[] vertices = buildCrosshair();

        crosshairVaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(crosshairVaoId);

        FloatBuffer buf = MemoryUtil.memAllocFloat(vertices.length);
        buf.put(vertices).flip();

        crosshairVboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, crosshairVboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(buf);

        // Position only — vec2, 2 floats per vertex, no color attribute
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Draws a semi-transparent white crosshair at the screen centre.
     * Temporarily disables depth testing and face culling, enables alpha blending,
     * then restores all three states afterward.
     */
    public void renderCrosshair() {
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        hudShader.bind();
        GL30.glBindVertexArray(crosshairVaoId);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, CROSSHAIR_VERTEX_COUNT);
        GL30.glBindVertexArray(0);
        hudShader.unbind();

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * Deletes all GPU resources. Call on shutdown.
     */
    public void cleanup() {
        hudShader.cleanup();
        GL15.glDeleteBuffers(crosshairVboId);
        GL30.glDeleteVertexArrays(crosshairVaoId);
    }

    /**
     * Builds two overlapping quads in NDC forming a + crosshair.
     * Horizontal bar: wide in x, thin in y.
     * Vertical bar: thin in x, tall in y — scaled for 16:9 pixel equivalence.
     *
     * @return float array of 12 vertices × 2 floats = 24 floats
     */
    private static float[] buildCrosshair() {
        float armLen = 0.022f; // half-length of each arm in NDC

        // Horizontal bar thickness in y NDC units (~2px on 720p)
        float hy = 0.003f;
        // Vertical bar thickness in x NDC units — scaled to produce the same pixel
        // width as hy produces pixel height on a 16:9 display (9/16 = 0.5625)
        float vx = hy * (9f / 16f);

        // Horizontal bar height in y, vertical bar length in y — inverse scaling
        float vy = armLen * (16f / 9f);
        float hx = armLen;

        return new float[]{
            // Horizontal bar — 2 triangles
            -hx, -hy,   hx, -hy,   hx,  hy,
            -hx, -hy,   hx,  hy,  -hx,  hy,
            // Vertical bar — 2 triangles
            -vx, -vy,   vx, -vy,   vx,  vy,
            -vx, -vy,   vx,  vy,  -vx,  vy,
        };
    }
}