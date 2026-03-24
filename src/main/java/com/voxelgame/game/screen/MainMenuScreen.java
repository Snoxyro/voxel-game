package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiTheme;
import org.lwjgl.glfw.GLFW;

/**
 * The main menu screen — first thing the player sees on launch.
 *
 * <p>Buttons: Singleplayer (enters the world), Multiplayer (stub for 6B-5),
 * Quit (closes the application).
 *
 * <p>Cursor is released when this screen is shown and recaptured via the
 * {@code onEnterGame} callback when the player clicks Singleplayer.
 *
 * <p>Visual style: centered dark panel, title text, three stacked buttons
 * with hover highlight. All layout is proportional to screen size so it
 * scales cleanly across resolutions.
 */
public class MainMenuScreen implements Screen {

    // -------------------------------------------------------------------------
    // Layout constants — all in pixels, relative to a virtual 1280×720 canvas.
    // Actual positions are recomputed from screen center each frame so the
    // menu stays centered on any resolution.
    // -------------------------------------------------------------------------

    private static final int PANEL_W     = 320;
    private static final int PANEL_H     = 340;   // was 280 — extra button + gap
    private static final int BUTTON_W    = 240;
    private static final int BUTTON_H    = 40;
    private static final int BUTTON_GAP  = 14;
    @SuppressWarnings("unused")
    private static final int TITLE_SIZE  = 2; // not used directly — title size is determined by UiTheme's font metrics

    private final ScreenManager screenManager;
    private final Runnable       onEnterGame;
    private final Runnable       onMultiplayer;
    private final Runnable       onSettings;
    private final Runnable       onQuit;

    /** Current mouse cursor position — updated every render frame. */
    private int mouseX;
    private int mouseY;

    /**
     * Creates the main menu screen.
     *
     * @param screenManager the active screen manager — used to dismiss this screen
     * @param onEnterGame   callback invoked when the player clicks Singleplayer
     * @param onMultiplayer callback invoked when the player clicks Multiplayer
     * @param onSettings    callback invoked when the player clicks Settings
     * @param onQuit        callback invoked when the player clicks Quit
     */
    public MainMenuScreen(ScreenManager screenManager,
                        Runnable onEnterGame,
                        Runnable onMultiplayer,
                        Runnable onSettings,
                        Runnable onQuit) {
        this.screenManager = screenManager;
        this.onEnterGame   = onEnterGame;
        this.onMultiplayer = onMultiplayer;
        this.onSettings    = onSettings;
        this.onQuit        = onQuit;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onShow() {
        // Nothing to reset — layout is computed fresh each frame.
    }

    @Override
    public void onHide() {
        // Nothing to tear down.
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(UiTheme theme, float deltaTime, int screenWidth, int screenHeight) {
        // --- Poll cursor position each frame for hover detection ---
        // We read this here rather than in onMouseClick so hover highlights
        // update smoothly as the cursor moves.
        double[] cx = {0}, cy = {0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        // --- Layout: compute positions relative to screen center ---
        int panelX = (screenWidth - PANEL_W) / 2;
        int panelY = (screenHeight - PANEL_H) / 2;

        int btnX = (screenWidth - BUTTON_W) / 2;
        int btn1Y = panelY + 80;
        int btn2Y = btn1Y + BUTTON_H + BUTTON_GAP;
        int btn3Y = btn2Y + BUTTON_H + BUTTON_GAP;
        int btn4Y = btn3Y + BUTTON_H + BUTTON_GAP;

        theme.drawPanel(panelX, panelY, PANEL_W, PANEL_H);

        String title  = "VOXEL GAME";
        int    titleY = panelY + 28;
        theme.drawTitle(panelX + PANEL_W / 2.0f, titleY, title);

        theme.drawButton(btnX, btn1Y, BUTTON_W, BUTTON_H, "Singleplayer",
            hits(mouseX, mouseY, btnX, btn1Y, BUTTON_W, BUTTON_H));
        theme.drawButton(btnX, btn2Y, BUTTON_W, BUTTON_H, "Multiplayer",
            hits(mouseX, mouseY, btnX, btn2Y, BUTTON_W, BUTTON_H));
        theme.drawButton(btnX, btn3Y, BUTTON_W, BUTTON_H, "Settings",
            hits(mouseX, mouseY, btnX, btn3Y, BUTTON_W, BUTTON_H));
        theme.drawButton(btnX, btn4Y, BUTTON_W, BUTTON_H, "Quit",
            hits(mouseX, mouseY, btnX, btn4Y, BUTTON_W, BUTTON_H));
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    @Override
    public void onMouseClick(int x, int y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;

        int sw = getScreenWidth();
        int sh = getScreenHeight();

        int btnX = (sw - BUTTON_W) / 2;
        int panelY = (sh - PANEL_H) / 2;
        int btn1Y = panelY + 80;
        int btn2Y = btn1Y + BUTTON_H + BUTTON_GAP;
        int btn3Y = btn2Y + BUTTON_H + BUTTON_GAP;
        int btn4Y = btn3Y + BUTTON_H + BUTTON_GAP;

        if      (hits(x, y, btnX, btn1Y, BUTTON_W, BUTTON_H)) { screenManager.setScreen(null); onEnterGame.run(); }
        else if (hits(x, y, btnX, btn2Y, BUTTON_W, BUTTON_H)) { onMultiplayer.run(); }
        else if (hits(x, y, btnX, btn3Y, BUTTON_W, BUTTON_H)) { onSettings.run(); }
        else if (hits(x, y, btnX, btn4Y, BUTTON_W, BUTTON_H)) { onQuit.run(); }
    }

    @Override
    public void onKeyPress(int key, int mods) {
        // No key bindings on the main menu for now.
    }

    @Override
    public void onCharTyped(char c) {
        // No text input on the main menu.
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns true if (px, py) falls within the rectangle. */
    private boolean hits(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /**
     * Reads the current framebuffer width from GLFW.
     * Used to recompute layout on click — must match what render() computed.
     */
    private int getScreenWidth() {
        int[] w = {0}, h = {0};
        GLFW.glfwGetFramebufferSize(GLFW.glfwGetCurrentContext(), w, h);
        return w[0];
    }

    private int getScreenHeight() {
        int[] w = {0}, h = {0};
        GLFW.glfwGetFramebufferSize(GLFW.glfwGetCurrentContext(), w, h);
        return h[0];
    }
}