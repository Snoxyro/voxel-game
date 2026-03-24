package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * Handles keyboard and mouse input via GLFW polling.
 * Mouse cursor is captured and hidden on init — standard FPS behaviour.
 * Call update() once per frame before reading any delta or click values.
 */
public class InputHandler {

    private final long windowHandle;

    private double lastMouseX;
    private double lastMouseY;

    private float mouseDeltaX;
    private float mouseDeltaY;

    /** True on the very first update() call — used to skip the initial cursor jump. */
    private boolean firstUpdate = true;

    /** GLFW supports mouse buttons 0–7. Track previous and just-pressed state for all. */
    private static final int MOUSE_BUTTON_COUNT = 8;
    private final boolean[] lastMouseButtons       = new boolean[MOUSE_BUTTON_COUNT];
    private final boolean[] mouseButtonJustPressed = new boolean[MOUSE_BUTTON_COUNT];

    // Keys pressed this frame — cleared at the start of each update tick.
    // Used for edge detection (pressed this frame but not last frame).
    private final Set<Integer> justPressedKeys = new HashSet<>();

    /**
     * Creates an InputHandler for the given GLFW window.
     * Does not capture the cursor yet — call init() for that.
     *
     * @param windowHandle the GLFW window handle
     */
    public InputHandler(long windowHandle) {
        this.windowHandle = windowHandle;
    }

    /**
     * Captures and hides the mouse cursor.
     * Must be called after the window is created, on the main thread.
     */
    public void init() {
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    /**
     * Samples the current cursor position, computes mouse deltas, and
     * detects mouse button press transitions.
     * Must be called once per frame, after glfwPollEvents().
     */
    public void update() {
        // --- Cursor position and mouse delta ---
        DoubleBuffer xBuffer = MemoryUtil.memAllocDouble(1);
        DoubleBuffer yBuffer = MemoryUtil.memAllocDouble(1);

        try {
            GLFW.glfwGetCursorPos(windowHandle, xBuffer, yBuffer);
            double currentX = xBuffer.get(0);
            double currentY = yBuffer.get(0);

            if (firstUpdate) {
                // Skip delta on the first frame to avoid a violent camera snap
                lastMouseX  = currentX;
                lastMouseY  = currentY;
                firstUpdate = false;
            } else {
                mouseDeltaX = (float) (currentX - lastMouseX);
                mouseDeltaY = (float) (currentY - lastMouseY);
                lastMouseX  = currentX;
                lastMouseY  = currentY;
            }
        } finally {
            MemoryUtil.memFree(xBuffer);
            MemoryUtil.memFree(yBuffer);
        }

        // --- Mouse button edge detection (all 8 GLFW buttons) ---
        for (int i = 0; i < MOUSE_BUTTON_COUNT; i++) {
            boolean cur = GLFW.glfwGetMouseButton(windowHandle, i) == GLFW.GLFW_PRESS;
            mouseButtonJustPressed[i] = cur && !lastMouseButtons[i];
            lastMouseButtons[i] = cur;
        }
    }
    
    /**
     * Synchronises the last-seen mouse button state to the current physical state,
     * then clears all just-pressed flags.
     *
     * <p>Call this after a UI click is consumed (e.g. Resume from pause menu) to
     * prevent the click from bleeding into game input on the same frame. Without
     * this, {@link #update()} will see the button transition from "up last frame"
     * to "down this frame" and fire {@link #wasMouseButtonJustPressed(int)} even
     * though the press was already handled by the UI.
     */
    public void consumeMouseClick() {
        for (int i = 0; i < MOUSE_BUTTON_COUNT; i++) {
            lastMouseButtons[i] = GLFW.glfwGetMouseButton(windowHandle, i) == GLFW.GLFW_PRESS;
            mouseButtonJustPressed[i] = false;
        }
    }

    /** Called from the GLFW key callback when a key is pressed (not repeated). */
    public void onKeyPressed(int key) {
        justPressedKeys.add(key);
    }

    /** Returns true if the key was pressed this frame (not held from last frame). */
    public boolean wasKeyJustPressed(int key) {
        return justPressedKeys.contains(key);
    }

    /** Clears edge-detection state. Call once per game loop tick, after processing input. */
    public void clearJustPressed() {
        justPressedKeys.clear();
    }

    /**
     * Returns true while the given key is held down.
     *
     * @param glfwKey a GLFW key constant, e.g. {@code GLFW.GLFW_KEY_W}
     * @return true if the key is currently pressed
     */
    public boolean isKeyDown(int glfwKey) {
        return GLFW.glfwGetKey(windowHandle, glfwKey) == GLFW.GLFW_PRESS;
    }

    /**
     * Returns true on the single frame where the given mouse button was pressed.
     * Use for block interaction — prevents holding from rapid-firing.
     *
     * @param glfwButton a {@code GLFW_MOUSE_BUTTON_*} constant (0–7)
     * @return true if the button just transitioned from up to down
     */
    public boolean wasMouseButtonJustPressed(int glfwButton) {
        if (glfwButton < 0 || glfwButton >= MOUSE_BUTTON_COUNT) return false;
        return mouseButtonJustPressed[glfwButton];
    }

    /**
     * Returns true while the given mouse button is held down.
     *
     * @param glfwButton a {@code GLFW_MOUSE_BUTTON_*} constant (0–7)
     * @return true if the button is currently pressed
     */
    public boolean isMouseButtonDown(int glfwButton) {
        if (glfwButton < 0 || glfwButton >= MOUSE_BUTTON_COUNT) return false;
        return GLFW.glfwGetMouseButton(windowHandle, glfwButton) == GLFW.GLFW_PRESS;
    }

    /**
     * Returns horizontal mouse movement since the last update() call.
     * Positive values mean the mouse moved right.
     *
     * @return mouse delta on the X axis in pixels
     */
    public float getMouseDeltaX() {
        return mouseDeltaX;
    }

    /**
     * Returns vertical mouse movement since the last update() call.
     * Positive values mean the mouse moved down (screen-space convention).
     *
     * @return mouse delta on the Y axis in pixels
     */
    public float getMouseDeltaY() {
        return mouseDeltaY;
    }

    /**
     * Discards accumulated mouse movement by snapping the last-known cursor
     * position to the current cursor position.
     *
     * Call this immediately after recapturing the cursor (switching from
     * GLFW_CURSOR_NORMAL to GLFW_CURSOR_DISABLED) to prevent the camera from
     * lurching by the distance the free cursor traveled during a menu.
     */
    public void resetMouseDelta() {
        double[] x = {0}, y = {0};
        GLFW.glfwGetCursorPos(windowHandle, x, y);
        lastMouseX = x[0];
        lastMouseY = y[0];
    }
}