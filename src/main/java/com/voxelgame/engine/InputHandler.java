package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.nio.DoubleBuffer;

/**
 * Handles keyboard and mouse input via GLFW polling.
 * Tracks mouse movement deltas and provides key state queries.
 * All input must be polled on the main thread.
 */
public class InputHandler {

    private final long windowHandle;

    private double previousMouseX;
    private double previousMouseY;
    private float mouseDeltaX;
    private float mouseDeltaY;

    /** True after the first update() call — prevents a large delta jump on startup. */
    private boolean firstUpdate;

    /**
     * Creates an InputHandler for the given GLFW window.
     *
     * @param windowHandle the GLFW window handle (long ID)
     */
    public InputHandler(long windowHandle) {
        this.windowHandle = windowHandle;
        this.previousMouseX = 0.0;
        this.previousMouseY = 0.0;
        this.mouseDeltaX = 0.0f;
        this.mouseDeltaY = 0.0f;
        this.firstUpdate = true;
    }

    /**
     * Initializes input mode — captures and hides the mouse cursor.
     * Must be called once after the window is created, before the main loop.
     */
    public void init() {
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
    }

    /**
     * Checks if a keyboard key is currently pressed down.
     *
     * @param glfwKey the GLFW key constant (e.g. GLFW.GLFW_KEY_W)
     * @return true if the key is pressed, false otherwise
     */
    public boolean isKeyDown(int glfwKey) {
        return GLFW.glfwGetKey(windowHandle, glfwKey) == GLFW.GLFW_PRESS;
    }

    /**
     * Returns horizontal mouse movement since the last update() call.
     * Positive values mean the mouse moved right.
     *
     * @return delta X in pixels
     */
    public float getMouseDeltaX() {
        return mouseDeltaX;
    }

    /**
     * Returns vertical mouse movement since the last update() call.
     * Positive values mean the mouse moved down.
     *
     * @return delta Y in pixels
     */
    public float getMouseDeltaY() {
        return mouseDeltaY;
    }

    /**
     * Samples the current mouse position and calculates deltas since the previous frame.
     * Must be called once per frame in the update loop.
     * The first call produces zero delta to avoid a startup jump.
     */
    public void update() {
        // Allocate off-heap buffers for GLFW cursor position query
        DoubleBuffer xBuffer = MemoryUtil.memAllocDouble(1);
        DoubleBuffer yBuffer = MemoryUtil.memAllocDouble(1);

        // Get current cursor position from GLFW
        GLFW.glfwGetCursorPos(windowHandle, xBuffer, yBuffer);
        double currentMouseX = xBuffer.get(0);
        double currentMouseY = yBuffer.get(0);

        // Free the off-heap buffers immediately after reading
        MemoryUtil.memFree(xBuffer);
        MemoryUtil.memFree(yBuffer);

        // Calculate deltas (skip first frame to avoid large initial jump)
        if (firstUpdate) {
            mouseDeltaX = 0.0f;
            mouseDeltaY = 0.0f;
            firstUpdate = false;
        } else {
            mouseDeltaX = (float) (currentMouseX - previousMouseX);
            mouseDeltaY = (float) (currentMouseY - previousMouseY);
        }

        // Store current position for next frame's delta calculation
        previousMouseX = currentMouseX;
        previousMouseY = currentMouseY;
    }
}
