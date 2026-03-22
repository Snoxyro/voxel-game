package com.voxelgame.engine;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

import com.voxelgame.game.Block;
import com.voxelgame.game.Player;
import com.voxelgame.game.RayCaster;
import com.voxelgame.game.RaycastResult;
import com.voxelgame.game.World;

/**
 * Drives the main engine loop: initialize, loop (update + render), shutdown.
 * Owns the Window and is the top-level coordinator for all engine systems.
 */
public class GameLoop {

    private static final int   TARGET_UPS        = 60;

    /**
     * Fixed time step per update tick in seconds.
     * Physics and movement scale by this value so behaviour is frame-rate independent.
     */
    private static final float DELTA_TIME        = 1.0f / TARGET_UPS;

    private static final float FREECAM_SPEED     = 1.50f;
    private static final float MOUSE_SENSITIVITY = 0.1f;

    /** Maximum block interaction range in blocks. */
    private static final float REACH = 5.0f;

    /**
     * Spawn position — Y=70 places the player above the tallest possible terrain
     * (max surface height is BASE_HEIGHT + HEIGHT_VARIATION = 16 + 48 = 64),
     * so the player always falls cleanly onto the surface on first load.
     */
    private static final float SPAWN_X = 64.0f;
    private static final float SPAWN_Y = 70.0f;
    private static final float SPAWN_Z = 64.0f;

    private final Window window;
    private Camera                 camera;
    private InputHandler           inputHandler;
    private ShaderProgram          shaderProgram;
    private TextureManager         textureManager;
    private World                  world;
    private Player                 player;
    private BlockHighlightRenderer blockHighlight;
    private HudRenderer            hudRenderer;

    /** Result of the raycast from the last update() tick. Read during render(). */
    private RaycastResult lastRaycast = RaycastResult.miss();

    /** Block type placed on right-click. Toggle with keys 1/2/3. */
    private Block selectedBlock = Block.DIRT;

    /** True when the cursor is captured and camera look is active. */
    private boolean cursorCaptured = true;

    /**
     * When true: WASD flies freely, Space/Shift move up/down, no gravity.
     * When false: normal player physics — gravity, collision, jumping.
     * Toggle with F key.
     */
    private boolean freecam = false;

    /** Last known framebuffer size — used to detect resizes and sync the camera. */
    private int lastFbWidth;
    private int lastFbHeight;

    /** Whether the F key was down last tick — used for edge detection. */
    private boolean lastFKeyDown = false;

    /** Chunk render stats from the last frame — [visible, total]. Updated each render(). */
    private int[] lastRenderStats = { 0, 0 };

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

        // Camera starts at eye position above spawn — will be overridden by
        // physics mode each tick, but needs a sane initial value.
        camera.getPosition().set(SPAWN_X, SPAWN_Y + Player.EYE_HEIGHT, SPAWN_Z);

        inputHandler = new InputHandler(window.getWindowHandle());
        inputHandler.init();

        world = new World(12345L);

        player = new Player(SPAWN_X, SPAWN_Y, SPAWN_Z);

        shaderProgram  = new ShaderProgram("/shaders/default.vert", "/shaders/default.frag");
        textureManager = new TextureManager();
        textureManager.init();
        // Tell the shader which texture unit the array is bound to (unit 0, always).
        shaderProgram.bind();
        shaderProgram.setUniform("texArray", 0);
        shaderProgram.unbind();
        blockHighlight = new BlockHighlightRenderer();
        hudRenderer    = new HudRenderer();

        // Keep the screen alive during window drag — the OS modal loop blocks our
        // main loop on Windows, so we hook the OS repaint request and render there.
        window.setRefreshCallback(() -> {
            render();
            window.swapBuffers();
        });

        System.out.println("Engine initialized.");
        System.out.println("Controls: WASD move | mouse look");
        System.out.println("  Space       — jump (physics) / fly up (freecam)");
        System.out.println("  Left Shift  — fly down (freecam only)");
        System.out.println("  F           — toggle freecam / physics");
        System.out.println("  Left click  — break block (or re-capture cursor)");
        System.out.println("  Right click — place block");
        System.out.println("  1/2/3       — select GRASS / DIRT / STONE");
        System.out.println("  Escape      — release cursor");
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
                System.out.printf("FPS: %d | UPS: %d | Chunks: %d/%d | Mode: %s | Selected: %s%n",
                    frames, updates, lastRenderStats[0], lastRenderStats[1],
                    freecam ? "FREECAM" : "PHYSICS", selectedBlock);
                frames          = 0;
                updates         = 0;
                diagnosticTimer = System.currentTimeMillis();
            }

            window.swapBuffers();
        }
    }

    /**
     * Game logic update — called TARGET_UPS times per second.
     */
    private void update() {
        // Stream chunks around the viewer — works in both physics and freecam mode
        float yawRad1   = (float) Math.toRadians(camera.getYaw());
        float pitchRad1 = (float) Math.toRadians(camera.getPitch());
        float lookX    = (float) (Math.cos(yawRad1) * Math.cos(pitchRad1));
        float lookY    = (float)  Math.sin(pitchRad1);
        float lookZ    = (float) (Math.sin(yawRad1) * Math.cos(pitchRad1));
        world.update(camera.getPosition().x, camera.getPosition().y, camera.getPosition().z,
                    lookX, lookY, lookZ);

        inputHandler.update();

        // --- Window resize ---
        int fbw = window.getFramebufferWidth();
        int fbh = window.getFramebufferHeight();
        if (fbw != lastFbWidth || fbh != lastFbHeight) {
            camera.setAspectRatio(fbw, fbh);
            lastFbWidth  = fbw;
            lastFbHeight = fbh;
        }

        // --- Cursor toggle ---
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_ESCAPE) && cursorCaptured) {
            cursorCaptured = false;
            GLFW.glfwSetInputMode(window.getWindowHandle(),
                GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        if (inputHandler.wasMouseLeftClicked() && !cursorCaptured) {
            cursorCaptured = true;
            GLFW.glfwSetInputMode(window.getWindowHandle(),
                GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            return; // consume the click — don't also break a block
        }

        // --- Mouse look ---
        if (cursorCaptured) {
            float yaw   = camera.getYaw()   + inputHandler.getMouseDeltaX() * MOUSE_SENSITIVITY;
            float pitch = camera.getPitch() - inputHandler.getMouseDeltaY() * MOUSE_SENSITIVITY;
            camera.setYaw(yaw);
            camera.setPitch(pitch);
        }

        // --- Freecam toggle (edge-triggered on F key) ---
        boolean fKeyDown = inputHandler.isKeyDown(GLFW.GLFW_KEY_F);
        if (fKeyDown && !lastFKeyDown) {
            freecam = !freecam;
            if (freecam) {
                // When entering freecam, snap its position to current eye position
                // so the view doesn't jump.
                camera.getPosition().set(player.getEyePosition());
            } else {
                // When returning to physics, teleport the player feet under the
                // current freecam position so there's no discontinuity.
                Vector3f cam = camera.getPosition();
                player.getBody().getPosition().set(cam.x, cam.y - Player.EYE_HEIGHT, cam.z);
                player.getBody().getVelocity().set(0, 0, 0);
            }
        }
        lastFKeyDown = fKeyDown;

        // --- Movement ---
        float yawRad = (float) Math.toRadians(camera.getYaw());
        float sinYaw = (float) Math.sin(yawRad);
        float cosYaw = (float) Math.cos(yawRad);

        if (freecam) {
            // Freecam: direct camera position control, no gravity, Space/Shift for vertical.
            Vector3f pos = camera.getPosition();
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_W)) { pos.x += cosYaw * FREECAM_SPEED; pos.z += sinYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_S)) { pos.x -= cosYaw * FREECAM_SPEED; pos.z -= sinYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_A)) { pos.x += sinYaw * FREECAM_SPEED; pos.z -= cosYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_D)) { pos.x -= sinYaw * FREECAM_SPEED; pos.z += cosYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_SPACE))      pos.y += FREECAM_SPEED;
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) pos.y -= FREECAM_SPEED;
        } else {
            // Physics mode: build a horizontal move direction from WASD, let
            // Player.update() normalise it and apply walk speed.
            float moveX = 0, moveZ = 0;
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_W)) { moveX += cosYaw; moveZ += sinYaw; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_S)) { moveX -= cosYaw; moveZ -= sinYaw; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_A)) { moveX += sinYaw; moveZ -= cosYaw; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_D)) { moveX -= sinYaw; moveZ += cosYaw; }

            boolean wantsJump = inputHandler.isKeyDown(GLFW.GLFW_KEY_SPACE);
            player.update(moveX, moveZ, wantsJump, world, DELTA_TIME);

            // Snap camera to player eyes every tick.
            camera.getPosition().set(player.getEyePosition());
        }

        // --- Block type selection ---
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_1)) selectedBlock = Block.GRASS;
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_2)) selectedBlock = Block.DIRT;
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_3)) selectedBlock = Block.STONE;

        // --- Raycast ---
        float pitchRad = (float) Math.toRadians(camera.getPitch());
        Vector3f lookDir = new Vector3f(
            (float) (cosYaw * Math.cos(pitchRad)),
            (float)  Math.sin(pitchRad),
            (float) (sinYaw * Math.cos(pitchRad))
        );
        lastRaycast = RayCaster.cast(camera.getPosition(), lookDir, world, REACH);

        // --- Block interaction (cursor must be captured) ---
        if (cursorCaptured && lastRaycast.hit()) {
            if (inputHandler.wasMouseLeftClicked()) {
                world.setBlock(lastRaycast.blockX(), lastRaycast.blockY(),
                               lastRaycast.blockZ(), Block.AIR);
            }
            if (inputHandler.wasMouseRightClicked()) {
                int px = lastRaycast.placeX();
                int py = lastRaycast.placeY();
                int pz = lastRaycast.placeZ();
                boolean insidePlayer = !freecam && player.getBody().overlapsBlock(px, py, pz);
                if (!insidePlayer) {
                    world.setBlock(px, py, pz, selectedBlock);
                }
            }
        }
    }

    /**
     * Renders the current frame: 3D world, block highlight, then 2D HUD.
     */
    private void render() {
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", camera.getProjectionMatrix());
        shaderProgram.setUniform("viewMatrix", camera.getViewMatrix());
        textureManager.bind();
        shaderProgram.setUniform("useTexture", true);
        lastRenderStats = world.render(shaderProgram, camera.getProjectionMatrix(), camera.getViewMatrix());
        shaderProgram.setUniform("useTexture", false);

        if (lastRaycast.hit()) {
            blockHighlight.render(shaderProgram,
                lastRaycast.blockX(), lastRaycast.blockY(), lastRaycast.blockZ());
        }

        shaderProgram.unbind();
        hudRenderer.renderCrosshair();
    }

    /**
     * Shuts down all engine subsystems in reverse initialization order.
     */
    private void cleanup() {
        hudRenderer.cleanup();
        blockHighlight.cleanup();
        textureManager.cleanup();
        world.cleanup();
        shaderProgram.cleanup();
        window.cleanup();
        System.out.println("Engine shut down cleanly.");
    }
}