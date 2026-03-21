package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

/**
 * Manages the OS window and OpenGL context via GLFW.
 * All GLFW and OpenGL calls must happen on the main thread.
 *
 * <p>Tracks the current framebuffer dimensions so callers can react to resizes.
 * The GL viewport is updated automatically via a framebuffer size callback.
 */
public class Window {

    private final String title;

    /**
     * Current framebuffer dimensions in pixels — updated by the resize callback.
     * These may differ from the logical window size on HiDPI (Retina) displays.
     */
    private int framebufferWidth;
    private int framebufferHeight;

    /** The GLFW window handle — a long integer ID used to reference this window. */
    private long windowHandle;

    /** Held so GLFW doesn't GC the callback while the window is alive. */
    private GLFWFramebufferSizeCallback framebufferSizeCallback;

    /**
     * Creates a Window instance. Does not open the window yet — call init() for that.
     *
     * @param title  the window title bar text
     * @param width  initial window width in pixels
     * @param height initial window height in pixels
     */
    public Window(String title, int width, int height) {
        this.title            = title;
        this.framebufferWidth  = width;
        this.framebufferHeight = height;
    }

    /**
     * Initializes GLFW, creates the OS window, and sets up the OpenGL context.
     * Registers a framebuffer size callback that keeps the GL viewport in sync
     * with the window dimensions whenever the user resizes the window.
     * Must be called on the main thread.
     *
     * @throws RuntimeException if GLFW fails to initialize or the window cannot be created
     */
    public void init() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!GLFW.glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }

        // Tell GLFW which OpenGL version we want: 4.5 core profile
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 5);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);

        windowHandle = GLFW.glfwCreateWindow(
            framebufferWidth, framebufferHeight, title,
            MemoryUtil.NULL, MemoryUtil.NULL
        );
        if (windowHandle == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        GLFW.glfwMakeContextCurrent(windowHandle);
        GLFW.glfwSwapInterval(1);
        GL.createCapabilities();

        // Query the actual framebuffer size — on HiDPI displays this may already
        // differ from the requested logical window size.
        int[] fbw = new int[1], fbh = new int[1];
        GLFW.glfwGetFramebufferSize(windowHandle, fbw, fbh);
        framebufferWidth  = fbw[0];
        framebufferHeight = fbh[0];
        GL11.glViewport(0, 0, framebufferWidth, framebufferHeight);

        // Framebuffer size callback — fires whenever the user resizes the window.
        // Updates the GL viewport so rendering fills the new area, and stores the
        // new dimensions so the camera can update its aspect ratio.
        framebufferSizeCallback = GLFWFramebufferSizeCallback.create((window, width, height) -> {
            framebufferWidth  = width;
            framebufferHeight = height;
            GL11.glViewport(0, 0, width, height);
        });
        GLFW.glfwSetFramebufferSizeCallback(windowHandle, framebufferSizeCallback);

        GL11.glClearColor(0.5f, 0.7f, 1.0f, 1.0f); // Sky blue background
        GLFW.glfwShowWindow(windowHandle);
    }

    /**
     * Returns true if the OS or user has requested the window to close.
     *
     * @return true if the window should close
     */
    public boolean shouldClose() {
        return GLFW.glfwWindowShouldClose(windowHandle);
    }

    /** Polls pending OS input events. Call at the start of each frame. */
    public void pollEvents() {
        GLFW.glfwPollEvents();
    }

    /** Swaps front and back buffers to display the rendered frame. */
    public void swapBuffers() {
        GLFW.glfwSwapBuffers(windowHandle);
    }

    /**
     * Releases all GLFW and window resources. Must be called on shutdown.
     */
    public void cleanup() {
        if (framebufferSizeCallback != null) framebufferSizeCallback.free();
        GLFW.glfwDestroyWindow(windowHandle);
        GLFW.glfwTerminate();
    }

    /** @return the GLFW window handle */
    public long getWindowHandle() { return windowHandle; }

    /** @return current framebuffer width in pixels */
    public int getFramebufferWidth()  { return framebufferWidth; }

    /** @return current framebuffer height in pixels */
    public int getFramebufferHeight() { return framebufferHeight; }
}