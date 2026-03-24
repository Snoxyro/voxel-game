package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiTheme;

/**
 * A full-screen UI state — main menu, world selection, pause menu, etc.
 *
 * <p>When a {@link Screen} is active, the game world is neither updated nor
 * rendered. The screen owns the entire frame for its duration.
 *
 * <p>Screens are created once and reused; {@link #onShow()} and {@link #onHide()}
 * bracket each active period. Screens must not hold references to transient
 * game state — they receive what they need via constructor injection.
 */
public interface Screen {
    /**
     * Returns true if this screen renders on top of the world rather than replacing it.
     * Full-screen menus (main menu, world select) return false (the default).
     * Overlay screens (pause menu, inventory) return true.
     *
     * <p>When false: world update and render are both skipped while this screen is active.
     * When true: world render still runs each frame; this screen draws on top.</p>
     */
    default boolean isOverlay() { return false; }

    /**
     * Called once when this screen becomes the active screen.
     * Use for resetting per-show state (e.g. clearing an input field).
     */
    void onShow();

    /**
     * Called once just before this screen is replaced by another.
     * Use for any teardown that should happen on each hide, not on cleanup.
     */
    void onHide();

    /**
     * Renders the screen for one frame.
     *
     * @param theme        the active {@link UiTheme}
     * @param deltaTime    seconds elapsed since the last frame — use for animations
     *                     and timers instead of hardcoded frame-rate assumptions
     * @param screenWidth  current framebuffer width in pixels
     * @param screenHeight current framebuffer height in pixels
     */
    void render(UiTheme theme, float deltaTime, int screenWidth, int screenHeight);

    /**
     * Handles a mouse button press.
     *
     * @param x      cursor X in screen pixels (from top-left)
     * @param y      cursor Y in screen pixels (from top-left)
     * @param button GLFW mouse button constant (e.g. {@code GLFW_MOUSE_BUTTON_LEFT})
     */
    void onMouseClick(int x, int y, int button);

    /**
     * Handles a key press.
     *
     * @param key      GLFW key constant (e.g. {@code GLFW_KEY_ESCAPE})
     * @param mods     GLFW modifier bitmask
     */
    void onKeyPress(int key, int mods);

    /**
     * Handles a printable character typed by the user.
     * Use this for text input fields — it respects keyboard layout and modifiers.
     *
     * @param c the typed character
     */
    void onCharTyped(char c);
}