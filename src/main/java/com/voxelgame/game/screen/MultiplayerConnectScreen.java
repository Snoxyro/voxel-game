package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiTheme;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Multiplayer connect screen — direct server connection by hostname and port.
 *
 * <h3>State</h3>
 * <ul>
 *   <li><b>Idle</b> — both input fields active, Connect button enabled.</li>
 *   <li><b>Connecting</b> — all inputs and buttons locked, status reads
 *       "Connecting…". Background thread is running.</li>
 *   <li><b>Error</b> — connection failed; status shows the error, inputs
 *       re-enabled so the player can correct the address and retry.</li>
 * </ul>
 *
 * <h3>Edge case handling</h3>
 * <ul>
 *   <li><b>Spam Connect</b> — {@link #connecting} is {@code true} while an
 *       attempt is in progress; button and Enter key are ignored.</li>
 *   <li><b>Back while connecting</b> — {@link #onHide()} sets
 *       {@link #cancelledFlag} so the background thread discards its result
 *       and cleans up the partial connection without touching the GL thread.</li>
 *   <li><b>Re-entry after Back</b> — {@link #onShow()} resets both flags, so
 *       every visit starts in a clean idle state.</li>
 * </ul>
 */
public class MultiplayerConnectScreen implements Screen {

    // ── Connect callback interface ────────────────────────────────────────────

    /**
     * Supplied by {@link com.voxelgame.engine.GameLoop}. Starts the blocking
     * network connect on a background thread without involving the GL thread.
     */
    @FunctionalInterface
    public interface ConnectHandler {
        /**
         * Begin a connection attempt on a background daemon thread.
         *
         * @param host          server hostname or IP address
         * @param port          server TCP port (1–65535)
         * @param cancelledFlag set to {@code true} by {@link #onHide()} when the
         *                      player navigates away; the background thread must
         *                      check this before posting any main-thread action
         * @param onFailure     called on the GL thread if the attempt fails —
         *                      receives a short, human-readable error message
         */
        void connect(String host, int port,
                     AtomicBoolean cancelledFlag,
                     Consumer<String> onFailure);
    }

    // ── Layout constants ──────────────────────────────────────────────────────

    private static final int PANEL_W  = 360;
    private static final int PANEL_H  = 290;
    private static final int FIELD_H  = 30;
    private static final int BUTTON_W = 155;
    private static final int BUTTON_H = 36;
    private static final int BTN_GAP  = 12;
    private static final int PAD      = 16;  // left/right panel padding

    private static final float CARET_BLINK_INTERVAL = 0.5f;

    // ── Input state ───────────────────────────────────────────────────────────

    private String  hostText     = "localhost";
    private String  portText     = "24463";
    private int     hostCaretPos = "localhost".length();
    private int     portCaretPos = "24463".length();
    /** True when the port field has keyboard focus; false = host field focused. */
    private boolean portFieldActive = false;

    private float   caretBlinkTimer = 0f;
    private boolean caretVisible    = true;
    /** Cached single-char pixel width — used to map a click X to a caret position. */
    private int     charPixelWidth  = 8;

    // ── Connection state ──────────────────────────────────────────────────────

    /**
     * True while a connection attempt is running. Written and read exclusively on
     * the GL thread — no atomic needed.
     */
    private boolean connecting    = false;

    /**
     * Shown below the input fields. Empty while idle, "Connecting..." while
     * connecting, or a short error string after a failed attempt.
     */
    private String  statusMessage = "";

    /**
     * Set to {@code true} in {@link #onHide()} so that a running background thread
     * discards its result silently instead of posting a stale main-thread action.
     * Reset in {@link #onShow()} for every fresh visit.
     */
    private final AtomicBoolean cancelledFlag = new AtomicBoolean(false);

    private int mouseX, mouseY;

    // ── Callbacks ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private final ScreenManager  screenManager;
    private final ConnectHandler onConnectRequested;
    private final Runnable       onBack;

    /**
     * Creates the multiplayer connect screen.
     *
     * @param screenManager      the active screen manager
     * @param onConnectRequested supplied by GameLoop — starts the background connection
     * @param onBack             callback for the Back button — returns to the main menu
     */
    public MultiplayerConnectScreen(ScreenManager screenManager,
                                    ConnectHandler onConnectRequested,
                                    Runnable onBack) {
        this.screenManager      = screenManager;
        this.onConnectRequested = onConnectRequested;
        this.onBack             = onBack;
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    public void onShow() {
        // Reset all connection state — every visit starts completely clean
        connecting      = false;
        statusMessage   = "";
        cancelledFlag.set(false);
        caretBlinkTimer = 0f;
        caretVisible    = true;
        portFieldActive = false;
        // Position carets at the end of the pre-filled defaults
        hostCaretPos    = hostText.length();
        portCaretPos    = portText.length();
    }

    @Override
    public void onHide() {
        // If a background thread is still running, signal it to abort.
        // The thread checks this flag before posting any main-thread action
        // and calls network.disconnect() itself to clean up.
        if (connecting) {
            cancelledFlag.set(true);
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    @Override
    public void render(UiTheme theme, float deltaTime, int screenWidth, int screenHeight) {
        double[] cx = {0}, cy = {0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        // Cache char width for click-to-caret mapping (must match renderer's advance)
        charPixelWidth = Math.max(1, theme.measureText("X"));

        // Advance blink timer (~60 FPS fixed step; exact rate not critical for a blink)
        caretBlinkTimer += deltaTime;
        if (caretBlinkTimer >= CARET_BLINK_INTERVAL) {
            caretBlinkTimer -= CARET_BLINK_INTERVAL;
            caretVisible = !caretVisible;
        }

        int panelX = (screenWidth - PANEL_W) / 2;
        int panelY = (screenHeight - PANEL_H) / 2;
        int fieldX = panelX + PAD;
        int fieldW = PANEL_W - PAD * 2;

        theme.drawPanel(panelX, panelY, PANEL_W, PANEL_H);
        theme.drawTitle(panelX + PANEL_W / 2.0f, panelY + 14, "MULTIPLAYER");

        // ── Server address field ──────────────────────────────────────────────
        theme.drawLabel(fieldX, panelY + 58, "Server Address");
        theme.drawInputField(fieldX, panelY + 75, fieldW, FIELD_H,
            hostText, hostCaretPos,
            !portFieldActive,              // focused when port field is NOT active
            caretVisible && !connecting);

        // ── Port field (narrower — port numbers are short) ────────────────────
        theme.drawLabel(fieldX, panelY + 122, "Port");
        theme.drawInputField(fieldX, panelY + 138, 150, FIELD_H,
            portText, portCaretPos,
            portFieldActive,
            caretVisible && !connecting);

        // ── Status line ───────────────────────────────────────────────────────
        if (!statusMessage.isEmpty()) {
            if (connecting) {
                // "Connecting..." — neutral dim text
                theme.drawDimLabel(fieldX, panelY + 185, statusMessage);
            } else {
                // Error message — warning color
                theme.drawWarnLabel(fieldX, panelY + 185, statusMessage);
            }
        }

        // ── Buttons (side by side, centered in panel) ─────────────────────────
        int totalBtnsW = BUTTON_W * 2 + BTN_GAP;
        int btnRowX    = panelX + (PANEL_W - totalBtnsW) / 2;
        int btnY       = panelY + PANEL_H - BUTTON_H - 16;

        int connectBtnX = btnRowX;
        int backBtnX    = btnRowX + BUTTON_W + BTN_GAP;

        // Connect button is disabled while a connection attempt is running
        String connectLabel = connecting ? "Connecting..." : "Connect";
        boolean connectHovered = isHovered(connectBtnX, btnY, BUTTON_W, BUTTON_H) && !connecting;
        theme.drawButton(connectBtnX, btnY, BUTTON_W, BUTTON_H,
            connectLabel, connectHovered, !connecting);

        theme.drawButton(backBtnX, btnY, BUTTON_W, BUTTON_H,
            "Back", isHovered(backBtnX, btnY, BUTTON_W, BUTTON_H), true);
    }

    // ── Input ─────────────────────────────────────────────────────────────────

    @Override
    public void onMouseClick(int x, int y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;

        int screenWidth = getScreenWidth(), screenHeight = getScreenHeight();
        int panelX = (screenWidth - PANEL_W) / 2;
        int panelY = (screenHeight - PANEL_H) / 2;
        int fieldX = panelX + PAD;
        int fieldW = PANEL_W - PAD * 2;

        int totalBtnsW = BUTTON_W * 2 + BTN_GAP;
        int btnRowX    = panelX + (PANEL_W - totalBtnsW) / 2;
        int btnY       = panelY + PANEL_H - BUTTON_H - 16;

        // ── Button clicks ──────────────────────────────────────────────────────
        if (!connecting && hits(x, y, btnRowX, btnY, BUTTON_W, BUTTON_H)) {
            attemptConnect();
            return;
        }
        if (hits(x, y, btnRowX + BUTTON_W + BTN_GAP, btnY, BUTTON_W, BUTTON_H)) {
            onBack.run();
            return;
        }

        // ── Field focus by click — also positions caret at click site ─────────
        if (connecting) return; // fields locked during connection attempt
        if (hits(x, y, fieldX, panelY + 75, fieldW, FIELD_H)) {
            portFieldActive = false;
            hostCaretPos = clickToCaret(x, fieldX + 6, hostText);
        } else if (hits(x, y, fieldX, panelY + 138, 150, FIELD_H)) {
            portFieldActive = true;
            portCaretPos = clickToCaret(x, fieldX + 6, portText);
        }
    }

    @Override
    public void onKeyPress(int key, int mods) {
        // All keyboard input is suppressed during a connection attempt,
        // except Escape which always navigates Back.
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onBack.run();
            return;
        }
        if (connecting) return;

        if (key == GLFW.GLFW_KEY_TAB) {
            portFieldActive = !portFieldActive;
            return;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            attemptConnect();
            return;
        }

        // Route edit keys to the focused field
        if (portFieldActive) {
            portCaretPos = handleEditKey(key, portText, portCaretPos, s -> portText = s);
        } else {
            hostCaretPos = handleEditKey(key, hostText, hostCaretPos, s -> hostText = s);
        }
    }

    @Override
    public void onCharTyped(char c) {
        if (connecting) return;
        if (portFieldActive) {
            // Port is numeric-only
            if (c >= '0' && c <= '9') {
                portText     = portText.substring(0, portCaretPos) + c
                             + portText.substring(portCaretPos);
                portCaretPos++;
            }
        } else {
            // Host accepts any printable ASCII (IPs, hostnames, domains)
            if (c >= 32 && c < 127) {
                hostText     = hostText.substring(0, hostCaretPos) + c
                             + hostText.substring(hostCaretPos);
                hostCaretPos++;
            }
        }
    }

    // ── Connection logic ──────────────────────────────────────────────────────

    /**
     * Validates inputs and begins a connection attempt.
     * Called from the Connect button click and the Enter key.
     * Sets {@link #connecting} to disable all inputs for the duration.
     */
    private void attemptConnect() {
        String host = hostText.trim();
        if (host.isEmpty()) {
            statusMessage = "Enter a server address.";
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException e) {
            statusMessage = "Invalid port number.";
            return;
        }
        if (port < 1 || port > 65535) {
            statusMessage = "Port must be 1-65535.";
            return;
        }

        // Inputs valid — lock the UI and hand off to GameLoop
        connecting    = true;
        statusMessage = "Connecting...";
        cancelledFlag.set(false);

        onConnectRequested.connect(host, port, cancelledFlag, this::handleConnectionFailed);
    }

    /**
     * Called on the GL thread (via {@code pendingMainThreadAction}) when the
     * connection attempt fails. Restores idle state and shows the error.
     *
     * @param errorMessage short human-readable message from GameLoop
     */
    private void handleConnectionFailed(String errorMessage) {
        connecting    = false;
        statusMessage = errorMessage;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Applies one edit key (Backspace, Delete, arrows, Home, End) to a text field.
     * Returns the new caret position. Text mutations go through {@code setText}.
     *
     * @param key     GLFW key code
     * @param text    current field value
     * @param caret   current caret position
     * @param setText lambda that writes back the mutated string to the field
     * @return new caret position (unchanged if the key had no effect)
     */
    private int handleEditKey(int key, String text, int caret,
                               Consumer<String> setText) {
        return switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (caret > 0) {
                    setText.accept(text.substring(0, caret - 1) + text.substring(caret));
                    yield caret - 1;
                }
                yield caret;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (caret < text.length()) {
                    setText.accept(text.substring(0, caret) + text.substring(caret + 1));
                }
                yield caret;
            }
            case GLFW.GLFW_KEY_LEFT  -> Math.max(0, caret - 1);
            case GLFW.GLFW_KEY_RIGHT -> Math.min(text.length(), caret + 1);
            case GLFW.GLFW_KEY_HOME  -> 0;
            case GLFW.GLFW_KEY_END   -> text.length();
            default                  -> caret;
        };
    }

    /**
     * Maps a mouse X coordinate to a caret index inside {@code text}.
     * Uses the cached {@link #charPixelWidth} set each render frame.
     *
     * @param mouseX    absolute screen X of the click
     * @param textStartX absolute screen X where the text begins
     * @param text      the field's current string
     * @return clamped caret position [0, text.length()]
     */
    private int clickToCaret(int mouseX, int textStartX, String text) {
        int offset = mouseX - textStartX;
        int pos    = offset / charPixelWidth;
        return Math.max(0, Math.min(text.length(), pos));
    }

    private boolean isHovered(int x, int y, int w, int h) {
        return hits(mouseX, mouseY, x, y, w, h);
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