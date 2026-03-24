package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiTheme;
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
    public void render(UiTheme theme, int sw, int sh) {
        double[] cx = {0}, cy = {0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        int panelX = (sw - PANEL_W) / 2;
        int panelY = (sh - PANEL_H) / 2;

        theme.drawOverlayDim(sw, sh);
        theme.drawPanel(panelX, panelY, PANEL_W, PANEL_H);

        String title = "PAUSED";
        theme.drawTitle(panelX + PANEL_W / 2.0f, panelY + 14, title);

        int btnX  = (sw - BUTTON_W) / 2;
        int btn1Y = panelY + 60;
        int btn2Y = btn1Y + BUTTON_H + BUTTON_GAP;
        int btn3Y = btn2Y + BUTTON_H + BUTTON_GAP;

        theme.drawButton(btnX, btn1Y, BUTTON_W, BUTTON_H, "Resume",
            hits(mouseX, mouseY, btnX, btn1Y, BUTTON_W, BUTTON_H));
        theme.drawButton(btnX, btn2Y, BUTTON_W, BUTTON_H, "Main Menu",
            hits(mouseX, mouseY, btnX, btn2Y, BUTTON_W, BUTTON_H));
        theme.drawButton(btnX, btn3Y, BUTTON_W, BUTTON_H, "Quit",
            hits(mouseX, mouseY, btnX, btn3Y, BUTTON_W, BUTTON_H));
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