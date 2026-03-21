package com.voxelgame.engine;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import com.voxelgame.game.Block;
import com.voxelgame.game.ChunkPos;
import com.voxelgame.game.RayCaster;
import com.voxelgame.game.RaycastResult;
import com.voxelgame.game.TerrainGenerator;
import com.voxelgame.game.World;

/**
 * Drives the main engine loop: initialize, loop (update + render), shutdown.
 * Owns the Window and is the top-level coordinator for all engine systems.
 */
public class GameLoop {

    private static final int   TARGET_UPS       = 60;
    private static final float MOVEMENT_SPEED   = 0.15f;
    private static final float MOUSE_SENSITIVITY = 0.1f;

    /** Maximum block interaction range in blocks. */
    private static final float REACH = 5.0f;

    private final Window window;
    private Camera             camera;
    private InputHandler       inputHandler;
    private ShaderProgram      shaderProgram;
    private World              world;
    private BlockHighlightRenderer blockHighlight;
    private HudRenderer            hudRenderer;

    /**
     * Result of the raycast cast during the last update() tick.
     * Shared between update() (writes) and render() (reads).
     */
    private RaycastResult lastRaycast = RaycastResult.miss();

    /**
     * The block type placed on right-click.
     * Toggle with keys 1 (GRASS), 2 (DIRT), 3 (STONE).
     */
    private Block selectedBlock = Block.DIRT;

    /** True when the cursor is captured and camera look is active. */
    private boolean cursorCaptured = true;

    /** Last known framebuffer dimensions — used to detect resizes. */
    private int lastFbWidth  = 1280;
    private int lastFbHeight = 720;

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

        lastFbWidth  = window.getFramebufferWidth();
        lastFbHeight = window.getFramebufferHeight();
        camera = new Camera(lastFbWidth, lastFbHeight);
        camera.getPosition().set(64.0f, 30.0f, 96.0f);

        inputHandler = new InputHandler(window.getWindowHandle());
        inputHandler.init();

        world = new World();
        TerrainGenerator generator = new TerrainGenerator(12345L);

        for (int cx = 0; cx < 8; cx++) {
            for (int cz = 0; cz < 8; cz++) {
                for (int cy = 0; cy < 5; cy++) {
                    ChunkPos pos = new ChunkPos(cx, cy, cz);
                    world.addChunk(pos, generator.generateChunk(pos));
                }
            }
        }

        shaderProgram  = new ShaderProgram("/shaders/default.vert", "/shaders/default.frag");
        blockHighlight = new BlockHighlightRenderer();
        hudRenderer    = new HudRenderer();

        System.out.println("Engine initialized. OpenGL context active.");
        System.out.println("Controls: WASD move, Space/Shift up/down, mouse look");
        System.out.println("  Left click  — break block");
        System.out.println("  Right click — place block");
        System.out.println("  1/2/3       — select GRASS / DIRT / STONE");
        System.out.println("  Escape      — release cursor (click to re-capture)");
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
            window.pollEvents();

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
                System.out.printf("FPS: %d | UPS: %d | Selected: %s%n",
                    frames, updates, selectedBlock);
                frames          = 0;
                updates         = 0;
                diagnosticTimer = System.currentTimeMillis();
            }

            window.swapBuffers();
        }
    }

    /**
 * Game logic update — called TARGET_UPS times per second.
 * Handles input, camera movement, raycasting, and block interaction.
 */
private void update() {
    inputHandler.update();

    // --- Window resize: sync camera aspect ratio ---
    // Checked every tick — cheap comparison, only rebuilds projection when needed.
    int fbw = window.getFramebufferWidth();
    int fbh = window.getFramebufferHeight();
    if (fbw != lastFbWidth || fbh != lastFbHeight) {
        camera.setAspectRatio(fbw, fbh);
        lastFbWidth  = fbw;
        lastFbHeight = fbh;
    }

    // --- Escape: toggle cursor capture ---
    // First press releases the cursor so the player can interact with the OS.
    // Clicking back in the window re-captures it.
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_ESCAPE) && cursorCaptured) {
        cursorCaptured = false;
        GLFW.glfwSetInputMode(window.getWindowHandle(),
            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
    }
    if (inputHandler.wasMouseLeftClicked() && !cursorCaptured) {
        cursorCaptured = true;
        GLFW.glfwSetInputMode(window.getWindowHandle(),
            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        return; // skip this click — don't break a block on re-capture
    }

    // Only rotate the camera while the cursor is captured
    if (cursorCaptured) {
        float yaw   = camera.getYaw()   + inputHandler.getMouseDeltaX() * MOUSE_SENSITIVITY;
        float pitch = camera.getPitch() - inputHandler.getMouseDeltaY() * MOUSE_SENSITIVITY;
        camera.setYaw(yaw);
        camera.setPitch(pitch);
    }

    // --- Keyboard movement ---
    float yawRad = (float) Math.toRadians(camera.getYaw());
    Vector3f forward = new Vector3f(
        (float)  Math.cos(yawRad), 0.0f,
        (float)  Math.sin(yawRad)
    ).normalize();
    Vector3f right = new Vector3f(
        (float) -Math.sin(yawRad), 0.0f,
        (float)  Math.cos(yawRad)
    ).normalize();

    Vector3f position = camera.getPosition();
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_W)) position.add(new Vector3f(forward).mul(MOVEMENT_SPEED));
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_S)) position.sub(new Vector3f(forward).mul(MOVEMENT_SPEED));
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_A)) position.sub(new Vector3f(right).mul(MOVEMENT_SPEED));
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_D)) position.add(new Vector3f(right).mul(MOVEMENT_SPEED));
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_SPACE))      position.y += MOVEMENT_SPEED;
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) position.y -= MOVEMENT_SPEED;

    // --- Block type selection ---
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_1)) selectedBlock = Block.GRASS;
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_2)) selectedBlock = Block.DIRT;
    if (inputHandler.isKeyDown(GLFW.GLFW_KEY_3)) selectedBlock = Block.STONE;

    // --- Raycast: find the block the camera is looking at ---
    float pitchRad = (float) Math.toRadians(camera.getPitch());
    Vector3f lookDir = new Vector3f(
        (float) (Math.cos(yawRad) * Math.cos(pitchRad)),
        (float)  Math.sin(pitchRad),
        (float) (Math.sin(yawRad) * Math.cos(pitchRad))
    );
    lastRaycast = RayCaster.cast(camera.getPosition(), lookDir, world, REACH);

    // --- Block interaction (only while cursor is captured) ---
    if (cursorCaptured && lastRaycast.hit()) {
        if (inputHandler.wasMouseLeftClicked()) {
            world.setBlock(lastRaycast.blockX(), lastRaycast.blockY(),
                           lastRaycast.blockZ(), Block.AIR);
        }
        if (inputHandler.wasMouseRightClicked()) {
            world.setBlock(lastRaycast.placeX(), lastRaycast.placeY(),
                           lastRaycast.placeZ(), selectedBlock);
        }
    }
}

    /**
     * Renders the current frame: 3D world, block highlight, then HUD.
     */
    private void render() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        // --- 3D world pass ---
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", camera.getProjectionMatrix());
        shaderProgram.setUniform("viewMatrix", camera.getViewMatrix());
        world.render(shaderProgram);

        // --- Block highlight (reuses the main shader, still bound) ---
        if (lastRaycast.hit()) {
            blockHighlight.render(shaderProgram,
                lastRaycast.blockX(), lastRaycast.blockY(), lastRaycast.blockZ());
        }

        shaderProgram.unbind();

        // --- 2D HUD pass (uses its own shader internally) ---
        hudRenderer.renderCrosshair();
    }

    /**
     * Shuts down all engine subsystems in reverse initialization order.
     */
    private void cleanup() {
        hudRenderer.cleanup();
        blockHighlight.cleanup();
        world.cleanup();
        shaderProgram.cleanup();
        window.cleanup();
        System.out.println("Engine shut down cleanly.");
    }
}