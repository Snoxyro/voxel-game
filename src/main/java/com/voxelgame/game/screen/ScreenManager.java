package com.voxelgame.game.screen;

import com.voxelgame.engine.GameLoop;
import com.voxelgame.engine.ui.DarkTheme;
import com.voxelgame.engine.ui.GlyphAtlas;
import com.voxelgame.engine.ui.UiRenderer;
import com.voxelgame.engine.ui.UiShader;
import com.voxelgame.engine.ui.UiTheme;

/**
 * Manages the currently active {@link Screen} and the active {@link UiTheme}.
 *
 * <p>At most one screen is active at a time. When no screen is active
 * ({@link #hasActiveScreen()} returns false), the game runs normally.
 * When a screen is active, {@link GameLoop} skips game update/render and
 * calls {@link #renderActiveScreen} instead.
 *
 * <p>The GL resources ({@link UiShader}, {@link GlyphAtlas}, {@link UiRenderer})
 * are owned here and shared for the entire session. The active {@link UiTheme}
 * wraps the renderer and is swappable at runtime — switching themes is a single
 * reference change with no GL work.
 */
public final class ScreenManager {

    // GL resources — owned here, freed in cleanup()
    private final UiShader   uiShader;
    private final GlyphAtlas glyphAtlas;
    private final UiRenderer uiRenderer;

    // The active theme — swappable via setTheme(). DarkTheme is the default.
    private UiTheme activeTheme;

    private Screen activeScreen = null;

    /**
     * Creates the ScreenManager and initialises all UI rendering resources.
     * Must be called on the main (GL) thread.
     */
    public ScreenManager() {
        uiShader    = new UiShader();
        glyphAtlas  = new GlyphAtlas();
        uiRenderer  = new UiRenderer(uiShader, glyphAtlas);
        activeTheme = new DarkTheme(uiRenderer); // default theme
    }

    // ── Theme management ──────────────────────────────────────────────────────

    /**
     * Replaces the active theme. Takes effect on the next rendered frame.
     * The renderer is shared — only the color data and draw behavior change.
     *
     * @param theme the new theme to activate; must not be null
     */
    public void setTheme(UiTheme theme) {
        if (theme == null) throw new IllegalArgumentException("theme must not be null");
        this.activeTheme = theme;
    }

    /** Returns the currently active theme. */
    public UiTheme getTheme() {
        return activeTheme;
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

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

    /** Returns true if the active screen is an overlay (renders over the world). */
    public boolean isActiveScreenOverlay() {
        return activeScreen != null && activeScreen.isOverlay();
    }

    // ── Per-frame input forwarding ─────────────────────────────────────────────

    /**
     * Forwards a mouse click to the active screen.
     * @return true if the screen consumed the event
     */
    public boolean onMouseClick(int x, int y, int button) {
        if (activeScreen == null) return false;
        activeScreen.onMouseClick(x, y, button);
        return true;
    }

    /**
     * Forwards a key press to the active screen.
     * @return true if the screen consumed the event
     */
    public boolean onKeyPress(int key, int mods) {
        if (activeScreen == null) return false;
        activeScreen.onKeyPress(key, mods);
        return true;
    }

    /**
     * Forwards a typed character to the active screen.
     * @return true if the screen consumed the event
     */
    public boolean onCharTyped(char c) {
        if (activeScreen == null) return false;
        activeScreen.onCharTyped(c);
        return true;
    }

    // ── Per-frame rendering ───────────────────────────────────────────────────

    /**
     * Renders the active screen for this frame using the active theme.
     * Must be called on the main (GL) thread.
     *
     * @param deltaTime seconds elapsed since the last frame
     * @param screenW   framebuffer width in pixels
     * @param screenH   framebuffer height in pixels
     */
    public void renderActiveScreen(float deltaTime, int screenW, int screenH) {
        if (activeScreen == null) return;
        activeTheme.begin(screenW, screenH);
        activeScreen.render(activeTheme, deltaTime, screenW, screenH);
        activeTheme.end();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /**
     * Frees all GL resources. Must be called on the main (GL) thread during shutdown.
     */
    public void cleanup() {
        uiRenderer.cleanup(); // also cleans up glyphAtlas inside
        uiShader.cleanup();
    }
}