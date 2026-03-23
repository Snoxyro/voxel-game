package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiRenderer;
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
    private static final int PANEL_H     = 280;
    private static final int BUTTON_W    = 240;
    private static final int BUTTON_H    = 40;
    private static final int BUTTON_GAP  = 14;  // vertical gap between buttons
    private static final int TITLE_SIZE  = 2;   // glyph scale multiplier (not used yet — reserved)

    // Panel background color — dark, semi-transparent Minecraft-style
    private static final float PANEL_R = 0.10f, PANEL_G = 0.10f, PANEL_B = 0.10f, PANEL_A = 0.85f;

    // Button colors
    private static final float BTN_R     = 0.25f, BTN_G = 0.25f, BTN_B = 0.25f, BTN_A = 1.0f;
    private static final float BTN_HOV_R = 0.40f, BTN_HOV_G = 0.40f, BTN_HOV_B = 0.40f, BTN_HOV_A = 1.0f;
    private static final float BTN_TXT_R = 1.00f, BTN_TXT_G = 1.00f, BTN_TXT_B = 1.00f, BTN_TXT_A = 1.0f;

    // Title color — slightly yellow-tinted like Minecraft's title
    private static final float TITLE_R = 1.0f, TITLE_G = 0.85f, TITLE_B = 0.30f, TITLE_A = 1.0f;

    // -------------------------------------------------------------------------

    private final ScreenManager screenManager;
    private final Runnable       onEnterGame; // called when Singleplayer is clicked
    private final Runnable       onQuit;      // called when Quit is clicked

    /** Current mouse cursor position — updated every render frame. */
    private int mouseX;
    private int mouseY;

    /**
     * Creates the main menu screen.
     *
     * @param screenManager the active screen manager — used to dismiss this screen
     * @param onEnterGame   callback invoked when the player clicks Singleplayer;
     *                      should recapture the cursor and start the game
     * @param onQuit        callback invoked when the player clicks Quit;
     *                      should close the GLFW window
     */
    public MainMenuScreen(ScreenManager screenManager, Runnable onEnterGame, Runnable onQuit) {
        this.screenManager = screenManager;
        this.onEnterGame   = onEnterGame;
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
    public void render(UiRenderer r, int sw, int sh) {
        // --- Poll cursor position each frame for hover detection ---
        // We read this here rather than in onMouseClick so hover highlights
        // update smoothly as the cursor moves.
        double[] cx = {0}, cy = {0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        // --- Layout: compute positions relative to screen center ---
        int panelX = (sw - PANEL_W) / 2;
        int panelY = (sh - PANEL_H) / 2;

        // Three buttons stacked vertically, centered inside the panel
        int btnX = (sw - BUTTON_W) / 2;
        // First button starts 80px from panel top (leaves room for title)
        int btn1Y = panelY + 80;
        int btn2Y = btn1Y + BUTTON_H + BUTTON_GAP;
        int btn3Y = btn2Y + BUTTON_H + BUTTON_GAP;

        // --- Background panel ---
        r.drawRect(panelX, panelY, PANEL_W, PANEL_H, PANEL_R, PANEL_G, PANEL_B, PANEL_A);

        // --- Title ---
        String title = "VOXEL GAME";
        // Center text horizontally inside the panel.
        // GlyphAtlas CELL_W = 16 per character — measure via UiRenderer would be
        // cleaner but we don't expose measureText here yet; hardcode for now.
        int titleTextW = title.length() * 16; // approx: each char ~16px wide
        int titleX = (sw - titleTextW) / 2;
        int titleY = panelY + 28;
        r.drawText(titleX, titleY, title, TITLE_R, TITLE_G, TITLE_B, TITLE_A);

        // --- Buttons ---
        drawButton(r, btnX, btn1Y, BUTTON_W, BUTTON_H, "Singleplayer");
        drawButton(r, btnX, btn2Y, BUTTON_W, BUTTON_H, "Multiplayer");
        drawButton(r, btnX, btn3Y, BUTTON_W, BUTTON_H, "Quit");
    }

    /**
     * Draws a single button, highlighted if the cursor is hovering over it.
     */
    private void drawButton(UiRenderer r, int x, int y, int w, int h, String label) {
        boolean hovered = mouseX >= x && mouseX <= x + w
                       && mouseY >= y && mouseY <= y + h;

        if (hovered) {
            r.drawRect(x, y, w, h, BTN_HOV_R, BTN_HOV_G, BTN_HOV_B, BTN_HOV_A);
        } else {
            r.drawRect(x, y, w, h, BTN_R, BTN_G, BTN_B, BTN_A);
        }

        // Center text inside button
        int textW = label.length() * 8; // GlyphAtlas charWidth avg — rough center
        int textX = x + (w - textW) / 2;
        int textY = y + (h - 16) / 2;   // 16 = GlyphAtlas.CELL_H
        r.drawText(textX, textY, label, BTN_TXT_R, BTN_TXT_G, BTN_TXT_B, BTN_TXT_A);
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

        if (hits(x, y, btnX, btn1Y, BUTTON_W, BUTTON_H)) {
            // Singleplayer — dismiss menu and enter the world
            screenManager.setScreen(null);
            onEnterGame.run();
        } else if (hits(x, y, btnX, btn2Y, BUTTON_W, BUTTON_H)) {
            // Multiplayer — stub until 6B-5
            System.out.println("[UI] Multiplayer — not yet implemented.");
        } else if (hits(x, y, btnX, btn3Y, BUTTON_W, BUTTON_H)) {
            onQuit.run();
        }
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