package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;

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

    // Mouse button state — track previous frame to detect "just pressed" transitions
    private boolean lastMouseLeft  = false;
    private boolean lastMouseRight = false;

    /** True only on the single frame where the button transitioned from up to down. */
    private boolean mouseLeftJustPressed  = false;
    private boolean mouseRightJustPressed = false;

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

        // --- Mouse button edge detection ---
        // A "just pressed" event fires only on the frame the button goes down,
        // not every frame it is held. This prevents holding a button from
        // rapidly placing/breaking many blocks.
        boolean curLeft  = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT)  == GLFW.GLFW_PRESS;
        boolean curRight = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        mouseLeftJustPressed  = curLeft  && !lastMouseLeft;
        mouseRightJustPressed = curRight && !lastMouseRight;

        lastMouseLeft  = curLeft;
        lastMouseRight = curRight;
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
     * Returns true on the single frame where the left mouse button was pressed.
     * Use for block breaking — prevents holding the button from rapid-firing.
     *
     * @return true if the left button just transitioned from up to down
     */
    public boolean wasMouseLeftClicked() {
        return mouseLeftJustPressed;
    }

    /**
     * Returns true on the single frame where the right mouse button was pressed.
     * Use for block placement.
     *
     * @return true if the right button just transitioned from up to down
     */
    public boolean wasMouseRightClicked() {
        return mouseRightJustPressed;
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