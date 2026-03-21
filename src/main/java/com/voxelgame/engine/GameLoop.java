package com.voxelgame.engine;

import org.lwjgl.opengl.GL11;

/**
 * Drives the main engine loop: initialize, loop (update + render), shutdown.
 * Owns the Window and is the top-level coordinator for all engine systems.
 */
public class GameLoop {

    private static final int TARGET_UPS = 60; // Updates (logic ticks) per second

    private final Window window;

    /**
     * Constructs the GameLoop and its owned subsystems.
     */
    public GameLoop() {
        window = new Window("Voxel Game", 1280, 720);
    }

    /**
     * Entry point for the engine. Initializes all systems, runs the loop,
     * then cleans up on exit.
     */
    public void run() {
        init();
        loop();
        cleanup();
    }

    /**
     * Initializes all engine subsystems. Order matters — window must come first
     * because the OpenGL context must exist before any GL calls.
     */
    private void init() {
        window.init();
        System.out.println("Engine initialized. OpenGL context active.");
    }

    /**
     * The main loop. Runs until the window is closed.
     * Tracks and prints FPS and UPS to stdout every second.
     */
    private void loop() {
        // Time tracking for the fixed update step
        double previousTime = System.currentTimeMillis();
        double updateInterval = 1000.0 / TARGET_UPS; // milliseconds per update tick
        double accumulator = 0.0;

        // FPS/UPS counters for diagnostics
        int frames = 0;
        int updates = 0;
        double diagnosticTimer = System.currentTimeMillis();

        while (!window.shouldClose()) {
            double currentTime = System.currentTimeMillis();
            double elapsed = currentTime - previousTime;
            previousTime = currentTime;
            accumulator += elapsed;

            // Process as many fixed-rate logic ticks as the elapsed time demands
            while (accumulator >= updateInterval) {
                update();
                updates++;
                accumulator -= updateInterval;
            }

            render();
            frames++;

            // Print diagnostics once per second
            if (System.currentTimeMillis() - diagnosticTimer >= 1000.0) {
                System.out.printf("FPS: %d | UPS: %d%n", frames, updates);
                frames = 0;
                updates = 0;
                diagnosticTimer = System.currentTimeMillis();
            }

            window.update();
        }
    }

    /**
     * Game logic update — called TARGET_UPS times per second regardless of frame rate.
     * Empty for now. Physics, input, and simulation will live here.
     */
    private void update() {
        // Nothing to update yet
    }

    /**
     * Render the current frame. Clears the screen for now.
     * Draw calls will be added here in Phase 0.
     */
    private void render() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
    }

    /**
     * Shuts down all engine subsystems in reverse initialization order.
     */
    private void cleanup() {
        window.cleanup();
        System.out.println("Engine shut down cleanly.");
    }
}