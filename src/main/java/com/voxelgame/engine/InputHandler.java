package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;

/**
 * Handles keyboard and mouse input via GLFW polling.
 * Mouse cursor is captured and hidden on init — standard FPS behaviour.
 * Call update() once per frame before reading any delta values.
 */
public class InputHandler {

    private final long windowHandle;

    private double lastMouseX;
    private double lastMouseY;

    private float mouseDeltaX;
    private float mouseDeltaY;

    /** True on the very first update() call — used to skip the initial cursor jump. */
    private boolean firstUpdate = true;

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
     * Samples the current cursor position and computes mouse deltas.
     * Must be called once per frame, after glfwPollEvents().
     */
    public void update() {
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
}