package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import com.voxelgame.game.Block;
import com.voxelgame.game.Chunk;
import com.voxelgame.game.ChunkPos;
import com.voxelgame.game.World;

/**
 * Drives the main engine loop: initialize, loop (update + render), shutdown.
 * Owns the Window and is the top-level coordinator for all engine systems.
 */
public class GameLoop {

    private static final int TARGET_UPS = 60;

    private final Window window;
    private Camera camera;
    private InputHandler inputHandler;
    private ShaderProgram shaderProgram;
    private World world;

    private static final float MOVEMENT_SPEED = 0.05f;
    private static final float MOUSE_SENSITIVITY = 0.1f;
    
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
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);

        camera = new Camera(1280,720);
        camera.getPosition().set(8.0f, 5.0f, 20.0f);
        inputHandler = new InputHandler(window.getWindowHandle());
        inputHandler.init();

        world = new World();

        // Seed a 4x4 grid of flat chunks
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                Chunk chunk = new Chunk();
                for (int x = 0; x < Chunk.SIZE; x++) {
                    for (int z = 0; z < Chunk.SIZE; z++) {
                        chunk.setBlock(x, 0, z, Block.GRASS);
                    }
                }
                world.addChunk(new ChunkPos(cx, cz), chunk);
            }
        }

        shaderProgram = new ShaderProgram("/shaders/default.vert", "/shaders/default.frag");

        System.out.println("Engine initialized. OpenGL context active.");
    }

    /**
     * The main loop. Runs until the window is closed.
     */
    private void loop() {
        double previousTime    = System.currentTimeMillis();
        double updateInterval  = 1000.0 / TARGET_UPS;
        double accumulator     = 0.0;
        int    frames          = 0;
        int    updates         = 0;
        double diagnosticTimer = System.currentTimeMillis();

        while (!window.shouldClose()) {
            window.pollEvents(); // process OS events FIRST
            
            double currentTime = System.currentTimeMillis();
            double elapsed     = currentTime - previousTime;
            previousTime       = currentTime;
            accumulator       += elapsed;

            while (accumulator >= updateInterval) {
                update();
                updates++;
                accumulator -= updateInterval;
            }

            render();
            frames++;

            if (System.currentTimeMillis() - diagnosticTimer >= 1000.0) {
                System.out.printf("FPS: %d | UPS: %d%n", frames, updates);
                frames          = 0;
                updates         = 0;
                diagnosticTimer = System.currentTimeMillis();
            }

            window.swapBuffers(); // present frame AFTER render
        }
    }

    /**
     * Game logic update — called TARGET_UPS times per second.
     */
    private void update() {
        inputHandler.update();

        // --- Mouse look ---
        float yaw   = camera.getYaw()   + inputHandler.getMouseDeltaX() * MOUSE_SENSITIVITY;
        float pitch = camera.getPitch() - inputHandler.getMouseDeltaY() * MOUSE_SENSITIVITY;
        // Pitch delta is subtracted because screen Y increases downward but pitch up is positive
        camera.setYaw(yaw);
        camera.setPitch(pitch); // Camera.setPitch already clamps to ±89°

        // --- Keyboard movement ---
        // Build the forward and right vectors from the current yaw.
        // We ignore pitch for movement so you don't fly up just by looking up.
        float yawRad  = (float) Math.toRadians(camera.getYaw());
        org.joml.Vector3f forward = new org.joml.Vector3f(
            (float)  Math.cos(yawRad), 0.0f,
            (float)  Math.sin(yawRad)
        ).normalize();
        org.joml.Vector3f right = new org.joml.Vector3f(
            (float) -Math.sin(yawRad), 0.0f,
            (float)  Math.cos(yawRad)
        ).normalize();

        org.joml.Vector3f position = camera.getPosition();

        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_W)) position.add(new org.joml.Vector3f(forward).mul(MOVEMENT_SPEED));
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_S)) position.sub(new org.joml.Vector3f(forward).mul(MOVEMENT_SPEED));
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_A)) position.sub(new org.joml.Vector3f(right).mul(MOVEMENT_SPEED));
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_D)) position.add(new org.joml.Vector3f(right).mul(MOVEMENT_SPEED));

        // Vertical movement — Space goes up, Shift goes down
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_SPACE))      position.y += MOVEMENT_SPEED;
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) position.y -= MOVEMENT_SPEED;

        // Escape releases the cursor and closes the window
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_ESCAPE)) {
            GLFW.glfwSetWindowShouldClose(window.getWindowHandle(), true);
        }
    }

    /**
     * Renders the current frame.
     */
    private void render() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", camera.getProjectionMatrix());
        shaderProgram.setUniform("viewMatrix", camera.getViewMatrix());
        world.render(shaderProgram);
        shaderProgram.unbind();
    }

    /**
     * Shuts down all engine subsystems in reverse initialization order.
     */
    private void cleanup() {
        world.cleanup();
        shaderProgram.cleanup();
        window.cleanup();
        System.out.println("Engine shut down cleanly.");
    }
}