package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiRenderer;
import org.lwjgl.glfw.GLFW;

/**
 * In-game pause menu. Displayed as an overlay on top of the rendered world.
 *
 * <p>Three actions: Resume (recaptures cursor), Main Menu (disconnects and returns
 * to world selection), Quit (closes the application).
 *
 * <p>This screen is an overlay — {@link #isOverlay()} returns true so the game
 * world is rendered behind it. The dark translucent backdrop simulates "frosted
 * glass" over the world without stopping its render.</p>
 */
public class PauseMenuScreen implements Screen {

    private static final int PANEL_W    = 280;
    private static final int PANEL_H    = 220;
    private static final int BUTTON_W   = 200;
    private static final int BUTTON_H   = 40;
    private static final int BUTTON_GAP = 12;

    // Slightly more opaque than main menu — world is visible behind this
    private static final float PANEL_R = 0.08f, PANEL_G = 0.08f, PANEL_B = 0.08f, PANEL_A = 0.80f;

    private static final float BTN_R     = 0.25f, BTN_G = 0.25f, BTN_B = 0.25f, BTN_A = 1.0f;
    private static final float BTN_HOV_R = 0.40f, BTN_HOV_G = 0.40f, BTN_HOV_B = 0.40f;
    private static final float TXT_R     = 1.00f, TXT_G = 1.00f, TXT_B = 1.00f, TXT_A = 1.0f;
    private static final float TITLE_R   = 1.00f, TITLE_G = 0.85f, TITLE_B = 0.30f, TITLE_A = 1.0f;

    private final ScreenManager screenManager;
    private final Runnable      onResume;
    private final Runnable      onMainMenu;
    private final Runnable      onQuit;

    private int mouseX, mouseY;

    /**
     * Creates the pause menu.
     *
     * @param screenManager the active screen manager — used to dismiss this screen on resume
     * @param onResume      called when the player clicks Resume; should recapture the cursor
     * @param onMainMenu    called when the player clicks Main Menu; should disconnect and show menu
     * @param onQuit        called when the player clicks Quit; should close the window
     */
    public PauseMenuScreen(ScreenManager screenManager,
                           Runnable onResume,
                           Runnable onMainMenu,
                           Runnable onQuit) {
        this.screenManager = screenManager;
        this.onResume      = onResume;
        this.onMainMenu    = onMainMenu;
        this.onQuit        = onQuit;
    }

    /** This screen renders over the world — do not skip the world render pass. */
    @Override
    public boolean isOverlay() { return true; }

    @Override
    public void onShow() { /* nothing to reset */ }

    @Override
    public void onHide() { /* nothing to tear down */ }

    @Override
    public void render(UiRenderer r, int sw, int sh) {
        double[] cx = {0}, cy = {0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        int panelX = (sw - PANEL_W) / 2;
        int panelY = (sh - PANEL_H) / 2;

        r.drawRect(panelX, panelY, PANEL_W, PANEL_H, PANEL_R, PANEL_G, PANEL_B, PANEL_A);

        String title = "PAUSED";
        int titleX = panelX + (PANEL_W - r.measureText(title)) / 2;
        r.drawText(titleX, panelY + 14, title, TITLE_R, TITLE_G, TITLE_B, TITLE_A);

        int btnX  = (sw - BUTTON_W) / 2;
        int btn1Y = panelY + 60;
        int btn2Y = btn1Y + BUTTON_H + BUTTON_GAP;
        int btn3Y = btn2Y + BUTTON_H + BUTTON_GAP;

        drawButton(r, "Resume",    btnX, btn1Y, BUTTON_W, BUTTON_H);
        drawButton(r, "Main Menu", btnX, btn2Y, BUTTON_W, BUTTON_H);
        drawButton(r, "Quit",      btnX, btn3Y, BUTTON_W, BUTTON_H);
    }

    @Override
    public void onMouseClick(int x, int y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        int sw = getScreenWidth(), sh = getScreenHeight();

        int btnX  = (sw - BUTTON_W) / 2;
        int btn1Y = (sh - PANEL_H) / 2 + 60;
        int btn2Y = btn1Y + BUTTON_H + BUTTON_GAP;
        int btn3Y = btn2Y + BUTTON_H + BUTTON_GAP;

        if      (hits(x, y, btnX, btn1Y, BUTTON_W, BUTTON_H)) { screenManager.setScreen(null); onResume.run(); }
        else if (hits(x, y, btnX, btn2Y, BUTTON_W, BUTTON_H)) { onMainMenu.run(); }
        else if (hits(x, y, btnX, btn3Y, BUTTON_W, BUTTON_H)) { onQuit.run(); }
    }

    @Override
    public void onKeyPress(int key, int mods) {
        // Escape while paused = Resume (same as clicking the button)
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            screenManager.setScreen(null);
            onResume.run();
        }
    }

    @Override
    public void onCharTyped(char c) { /* no text input */ }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void drawButton(UiRenderer r, String label, int x, int y, int w, int h) {
        boolean hovered = hits(mouseX, mouseY, x, y, w, h);
        float br = hovered ? BTN_HOV_R : BTN_R;
        float bg = hovered ? BTN_HOV_G : BTN_G;
        float bb = hovered ? BTN_HOV_B : BTN_B;
        r.drawRect(x, y, w, h, br, bg, bb, BTN_A);

        int tx = x + (w - r.measureText(label)) / 2;
        int ty = y + (h - 16) / 2;
        r.drawText(tx, ty, label, TXT_R, TXT_G, TXT_B, TXT_A);
    }

    private boolean hits(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

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