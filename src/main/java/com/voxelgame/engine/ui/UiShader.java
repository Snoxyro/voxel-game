package com.voxelgame.engine.ui;

import com.voxelgame.engine.ShaderProgram;
import org.joml.Matrix4f;

/**
 * Thin wrapper around {@link ShaderProgram} for the UI render pass.
 *
 * <p>The UI shader uses an orthographic projection — no camera, no perspective.
 * Pixel coordinate (0,0) maps to the top-left corner of the window;
 * (screenWidth, screenHeight) maps to the bottom-right. Y increases downward,
 * matching screen-space convention used by every 2D UI system.
 *
 * <p>{@link #setProjection(int, int)} must be called whenever the window is
 * resized (the projection matrix embeds the viewport dimensions).
 */
public final class UiShader {

    private final ShaderProgram program;

    /**
     * Compiles and links the UI shader.
     * Must be called on the main (GL) thread.
     */
    public UiShader() {
        program = new ShaderProgram("/shaders/ui.vert", "/shaders/ui.frag");
    }

    /** Binds this shader for subsequent draw calls. */
    public void bind() {
        program.bind();
        // Ensure the texture sampler always reads from unit 0.
        program.setUniform("uTexture", 0);
    }

    /** Unbinds this shader. */
    public void unbind() { program.unbind(); }

    /**
     * Rebuilds the orthographic projection matrix for the given viewport.
     * Call this once at startup and again on every window resize.
     *
     * @param screenWidth  current framebuffer width in pixels
     * @param screenHeight current framebuffer height in pixels
     */
    public void setProjection(int screenWidth, int screenHeight) {
        // ortho2D(left, right, bottom, top):
        //   left=0, right=width   → X maps screen pixels left-to-right
        //   bottom=height, top=0  → Y maps screen pixels top-to-bottom (Y↓)
        Matrix4f proj = new Matrix4f().ortho2D(0, screenWidth, screenHeight, 0);
        program.setUniform("uProjection", proj);
    }

    /** Frees the GPU shader program. */
    public void cleanup() { program.cleanup(); }
}