package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.GlyphAtlas;
import com.voxelgame.engine.ui.UiRenderer;
import com.voxelgame.engine.ui.UiShader;

/**
 * Manages the currently active {@link Screen}.
 *
 * <p>At most one screen is active at a time. When no screen is active
 * ({@link #hasActiveScreen()} returns false), the game runs normally.
 * When a screen is active, {@link GameLoop} skips game update/render and
 * calls {@link #renderActiveScreen} instead.
 *
 * <p>The {@link UiRenderer} and its dependencies are owned here — they are
 * created once and shared across all screens for the lifetime of the session.
 */
public final class ScreenManager {

    private final UiShader   uiShader;
    private final GlyphAtlas glyphAtlas;
    private final UiRenderer uiRenderer;

    private Screen activeScreen = null;

    /**
     * Creates the ScreenManager and initialises all UI rendering resources.
     * Must be called on the main (GL) thread.
     */
    public ScreenManager() {
        uiShader   = new UiShader();
        glyphAtlas = new GlyphAtlas();
        uiRenderer = new UiRenderer(uiShader, glyphAtlas);
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    /**
     * Sets the active screen, calling {@link Screen#onHide()} on the previous
     * one and {@link Screen#onShow()} on the new one.
     *
     * @param screen the screen to show, or {@code null} to return to in-game
     */
    public void setScreen(Screen screen) {
        if (activeScreen != null) {
            activeScreen.onHide();
        }
        activeScreen = screen;
        if (activeScreen != null) {
            activeScreen.onShow();
        }
    }

    /** Returns true when a screen is active and the game loop should yield to it. */
    public boolean hasActiveScreen() {
        return activeScreen != null;
    }

    // -------------------------------------------------------------------------
    // Per-frame rendering — called by GameLoop when hasActiveScreen() is true
    // -------------------------------------------------------------------------

    /**
     * Renders the active screen for this frame.
     * Must be called on the main (GL) thread.
     *
     * @param screenWidth  current framebuffer width in pixels
     * @param screenHeight current framebuffer height in pixels
     */
    public void renderActiveScreen(int screenWidth, int screenHeight) {
        if (activeScreen == null) return;

        uiRenderer.begin(screenWidth, screenHeight);
        activeScreen.render(uiRenderer, screenWidth, screenHeight);
        uiRenderer.end();
    }

    // -------------------------------------------------------------------------
    // Input forwarding — called by GameLoop's input callbacks
    // -------------------------------------------------------------------------

    /**
     * Forwards a mouse click to the active screen.
     * Returns true if the event was consumed (a screen was active).
     */
    public boolean onMouseClick(int x, int y, int button) {
        if (activeScreen == null) return false;
        activeScreen.onMouseClick(x, y, button);
        return true;
    }

    /**
     * Forwards a key press to the active screen.
     * Returns true if the event was consumed.
     */
    public boolean onKeyPress(int key, int mods) {
        if (activeScreen == null) return false;
        activeScreen.onKeyPress(key, mods);
        return true;
    }

    /**
     * Forwards a typed character to the active screen.
     * Returns true if the event was consumed.
     */
    public boolean onCharTyped(char c) {
        if (activeScreen == null) return false;
        activeScreen.onCharTyped(c);
        return true;
    }

    // -------------------------------------------------------------------------

    /**
     * Exposes the {@link UiRenderer} for screens that need to draw outside the
     * normal render pass (e.g. the in-game HUD, which runs alongside the world).
     */
    public UiRenderer getUiRenderer() { return uiRenderer; }

    /**
     * Releases all UI rendering resources.
     * Must be called on the main (GL) thread during shutdown.
     */
    public void cleanup() {
        uiRenderer.cleanup(); // also cleans up glyphAtlas
        uiShader.cleanup();
    }
}