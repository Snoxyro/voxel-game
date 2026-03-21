package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Manages the OS window and OpenGL context via GLFW.
 * All GLFW and OpenGL calls must happen on the main thread.
 */
public class Window {

    private final String title;
    private final int width;
    private final int height;

    /** The GLFW window handle — a long integer ID used to reference this window. */
    private long windowHandle;

    /**
     * Creates a Window instance. Does not open the window yet — call init() for that.
     *
     * @param title  the window title bar text
     * @param width  window width in pixels
     * @param height window height in pixels
     */
    public Window(String title, int width, int height) {
        this.title = title;
        this.width = width;
        this.height = height;
    }

    /**
     * Initializes GLFW, creates the OS window, and sets up the OpenGL context.
     * Must be called on the main thread.
     *
     * @throws RuntimeException if GLFW fails to initialize or the window cannot be created
     */
    public void init() {
        // Route GLFW error messages to System.err so we can see what went wrong
        GLFWErrorCallback.createPrint(System.err).set();

        if (!GLFW.glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // Tell GLFW which OpenGL version we want: 4.5 core profile
        // Core profile means deprecated legacy OpenGL features are removed — good, we don't want them
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        // Create the actual OS window. MemoryUtil.NULL means no monitor (windowed) and no shared context
        windowHandle = GLFW.glfwCreateWindow(width, height, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (windowHandle == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        // Make the OpenGL context on this window active on the current thread
        // After this line, all GL calls operate on this window's context
        GLFW.glfwMakeContextCurrent(windowHandle);

        // Enable v-sync: 1 means wait for 1 screen refresh between frames (caps at monitor Hz)
        GLFW.glfwSwapInterval(1);

        // This line is LWJGL-specific — it connects LWJGL's OpenGL bindings to the
        // current GLFW context so we can actually call GL functions
        GL.createCapabilities();

        // Set the clear color to a dark grey so we can confirm the context is working
        GL11.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        GLFW.glfwShowWindow(windowHandle);
    }

    /**
     * Returns true if the OS or user has requested the window to close
     * (e.g. clicking the X button).
     *
     * @return true if the window should close
     */
    public boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(windowHandle);
    }

    /**
     * Called once per frame. Swaps the front and back buffers to display
     * the rendered frame, then polls for OS input events.
     */
    public void update() {
        GLFW.glfwSwapBuffers(windowHandle);
        GLFW.glfwPollEvents();
    }

    /**
     * Releases all GLFW and window resources. Must be called on shutdown.
     */
    public void cleanup() {
        GLFW.glfwDestroyWindow(windowHandle);
        GLFW.glfwTerminate();
    }
}