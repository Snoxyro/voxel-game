package com.voxelgame.engine;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.joml.Vector3f;

/**
 * Drives the main engine loop: initialize, loop (update + render), shutdown.
 * Owns the Window and is the top-level coordinator for all engine systems.
 */
public class GameLoop {

    private static final int TARGET_UPS = 60;
    private static final float MOVE_SPEED = 0.05f;
    private static final float MOUSE_SENSITIVITY = 0.1f;

    private final Window window;
    private Camera camera;
    private InputHandler inputHandler;
    private ShaderProgram shaderProgram;
    private Mesh triangleMesh;

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
        camera = new Camera(1280, 720);

        // Initialize input handler after window creation
        inputHandler = new InputHandler(window.getWindowHandle());
        inputHandler.init();

        // Triangle vertex positions in NDC (Normalized Device Coordinates):
        // OpenGL's coordinate space goes from -1.0 to +1.0 on both axes.
        // (0, 0) is the center of the screen.
        // These three points form a triangle centered on screen.
        float[] vertices = {
             0.0f,  0.5f, 0.0f,  // top center
            -0.5f, -0.5f, 0.0f,  // bottom left
             0.5f, -0.5f, 0.0f   // bottom right
        };

        triangleMesh  = new Mesh(vertices);
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

            window.update();
        }
    }

    /**
     * Game logic update — called TARGET_UPS times per second.
     */
    private void update() {
        // Update input state (mouse deltas)
        inputHandler.update();

        // Handle camera rotation from mouse movement
        float deltaX = inputHandler.getMouseDeltaX();
        float deltaY = inputHandler.getMouseDeltaY();

        camera.setYaw(camera.getYaw() + deltaX * MOUSE_SENSITIVITY);
        camera.setPitch(camera.getPitch() - deltaY * MOUSE_SENSITIVITY);

        // Handle camera movement from keyboard input
        Vector3f movement = new Vector3f();

        // Calculate forward direction from camera yaw (ignore pitch for movement)
        float yawRad = (float) Math.toRadians(camera.getYaw());
        Vector3f forward = new Vector3f(
            (float) Math.cos(yawRad),
            0.0f,
            (float) Math.sin(yawRad)
        ).normalize();

        // Calculate right direction (perpendicular to forward)
        Vector3f right = new Vector3f(forward).cross(0.0f, 1.0f, 0.0f).normalize();

        // WASD movement
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_W)) {
            movement.add(forward);
        }
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_S)) {
            movement.sub(forward);
        }
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_A)) {
            movement.sub(right);
        }
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_D)) {
            movement.add(right);
        }

        // Apply movement to camera position
        if (movement.lengthSquared() > 0.0f) {
            movement.normalize().mul(MOVE_SPEED);
            camera.getPosition().add(movement);
        }
    }

    /**
     * Renders the current frame.
     */
    private void render() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        shaderProgram.bind();
        triangleMesh.render();
        shaderProgram.unbind();

        shaderProgram.setUniform("projectionMatrix", camera.getProjectionMatrix());
        shaderProgram.setUniform("viewMatrix", camera.getViewMatrix());
    }

    /**
     * Shuts down all engine subsystems in reverse initialization order.
     */
    private void cleanup() {
        triangleMesh.cleanup();
        shaderProgram.cleanup();
        window.cleanup();
        System.out.println("Engine shut down cleanly.");
    }
}