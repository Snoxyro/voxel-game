package com.voxelgame.engine;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.common.world.Block;
import com.voxelgame.common.world.RayCaster;
import com.voxelgame.common.world.RaycastResult;
import com.voxelgame.game.Player;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;

/**
 * Drives the main engine loop: initialize, loop (update + render), shutdown.
 * Owns the Window and is the top-level coordinator for all engine systems.
 *
 * <h3>Phase 5B change</h3>
 * Replaced the local {@link com.voxelgame.game.World} with {@link ClientWorld}.
 * The server now generates terrain and streams chunks to the client.
 * {@code clientWorld.update()} drains pending mesh builds; {@code clientWorld.render()}
 * draws the received chunks. Block interaction is temporarily disabled (Phase 5C).
 */
public class GameLoop {

    private static final int   TARGET_UPS        = 60;
    private static final float DELTA_TIME        = 1.0f / TARGET_UPS;
    private static final float FREECAM_SPEED     = 1.50f;
    private static final float MOUSE_SENSITIVITY = 0.1f;

    /** Maximum block interaction range in blocks. */
    private static final float REACH = 5.0f;

    /**
     * Spawn coordinates — must match {@link com.voxelgame.server.GameServer#SPAWN_X/Y/Z}.
     * Phase 5F: read from {@link ClientWorld#getSpawnX/Y/Z()} after LoginSuccess arrives.
     */
    private static final float SPAWN_X = 64.0f;
    private static final float SPAWN_Y = 70.0f;
    private static final float SPAWN_Z = 64.0f;

    private final Window      window;
    private final ClientWorld clientWorld;
    private final io.netty.channel.Channel serverChannel;

    private Camera                 camera;
    private InputHandler           inputHandler;
    private ShaderProgram          shaderProgram;
    private TextureManager         textureManager;
    private Player                 player;
    private BlockHighlightRenderer blockHighlight;
    private HudRenderer            hudRenderer;

    private RaycastResult lastRaycast  = RaycastResult.miss();
    private Block         selectedBlock = Block.DIRT;
    private boolean       cursorCaptured = true;
    private boolean       freecam        = false;
    private int           lastFbWidth;
    private int           lastFbHeight;
    private boolean       lastFKeyDown   = false;
    private int[]         lastRenderStats = { 0, 0 };

    /**
     * Creates a GameLoop.
     *
     * @param clientWorld the client-side world — receives chunks from the server,
     *                    provides block queries for physics and raycasting
     * @param serverChannel the Netty channel connected to the server — used to send
     *                      block interaction packets in Phase 5C
     */
    public GameLoop(ClientWorld clientWorld, io.netty.channel.Channel serverChannel) {
        this.window       = new Window("Voxel Game", 1280, 720);
        this.clientWorld  = clientWorld;
        this.serverChannel = serverChannel;
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
     * Initializes all engine subsystems. Window must come first — the OpenGL context
     * must exist before any GL calls.
     */
    private void init() {
        window.init();
        clientWorld.initRenderResources();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);

        lastFbWidth  = window.getFramebufferWidth();
        lastFbHeight = window.getFramebufferHeight();
        camera = new Camera(lastFbWidth, lastFbHeight);
        camera.getPosition().set(SPAWN_X, SPAWN_Y + Player.EYE_HEIGHT, SPAWN_Z);

        inputHandler = new InputHandler(window.getWindowHandle());
        inputHandler.init();

        player = new Player(SPAWN_X, SPAWN_Y, SPAWN_Z);

        shaderProgram  = new ShaderProgram("/shaders/default.vert", "/shaders/default.frag");
        textureManager = new TextureManager();
        textureManager.init();
        shaderProgram.bind();
        shaderProgram.setUniform("texArray", 0);
        shaderProgram.unbind();

        blockHighlight = new BlockHighlightRenderer();
        hudRenderer    = new HudRenderer();

        window.setRefreshCallback(() -> {
            render();
            window.swapBuffers();
        });

        System.out.println("Engine initialized.");
        System.out.println("Controls: WASD move | mouse look");
        System.out.println("  Space       — jump (physics) / fly up (freecam)");
        System.out.println("  Left Shift  — fly down (freecam only)");
        System.out.println("  F           — toggle freecam / physics");
        System.out.println("  Left click  — break block [Phase 5C]");
        System.out.println("  Right click — place block [Phase 5C]");
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
        // Drain pending mesh builds from the server's chunk stream.
        // This is the only ClientWorld call in update — the server drives chunk
        // loading, not the client. clientWorld.update() processes incoming chunks
        // and uploads finalized meshes to the GPU.
        clientWorld.update();

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
            return; // consume the click — don't also attempt a block break
        }

        // --- Mouse look ---
        if (cursorCaptured) {
            float yaw   = camera.getYaw()   + inputHandler.getMouseDeltaX() * MOUSE_SENSITIVITY;
            float pitch = camera.getPitch() - inputHandler.getMouseDeltaY() * MOUSE_SENSITIVITY;
            camera.setYaw(yaw);
            camera.setPitch(pitch);
        }

        // --- Freecam toggle ---
        boolean fKeyDown = inputHandler.isKeyDown(GLFW.GLFW_KEY_F);
        if (fKeyDown && !lastFKeyDown) {
            freecam = !freecam;
            if (freecam) {
                camera.getPosition().set(player.getEyePosition());
            } else {
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
            Vector3f pos = camera.getPosition();
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_W)) { pos.x += cosYaw * FREECAM_SPEED; pos.z += sinYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_S)) { pos.x -= cosYaw * FREECAM_SPEED; pos.z -= sinYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_A)) { pos.x += sinYaw * FREECAM_SPEED; pos.z -= cosYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_D)) { pos.x -= sinYaw * FREECAM_SPEED; pos.z += cosYaw * FREECAM_SPEED; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_SPACE))      pos.y += FREECAM_SPEED;
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) pos.y -= FREECAM_SPEED;
        } else {
            float moveX = 0, moveZ = 0;
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_W)) { moveX += cosYaw; moveZ += sinYaw; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_S)) { moveX -= cosYaw; moveZ -= sinYaw; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_A)) { moveX += sinYaw; moveZ -= cosYaw; }
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_D)) { moveX -= sinYaw; moveZ += cosYaw; }

            boolean wantsJump = inputHandler.isKeyDown(GLFW.GLFW_KEY_SPACE);
            player.update(moveX, moveZ, wantsJump, clientWorld, DELTA_TIME);
            camera.getPosition().set(player.getEyePosition());
        }

        // --- Send position to server (drives chunk streaming center) ---
        // Sent every tick regardless of whether position changed — simple and stateless.
        // Phase 5D: throttle to 20/s to match server TPS if bandwidth becomes a concern.
        Vector3f pos = camera.getPosition();
        serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.PlayerMoveSBPacket(
            pos.x, freecam ? pos.y : player.getBody().getPosition().y,
            pos.z, camera.getYaw(), camera.getPitch()
        ));

        // --- Block type selection ---
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_1)) selectedBlock = Block.GRASS;
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_2)) selectedBlock = Block.DIRT;
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_3)) selectedBlock = Block.STONE;

        // --- Raycast (for block highlight only — interaction wired in Phase 5C) ---
        float pitchRad = (float) Math.toRadians(camera.getPitch());
        yawRad = (float) Math.toRadians(camera.getYaw());
        cosYaw = (float) Math.cos(yawRad);
        sinYaw = (float) Math.sin(yawRad);
        Vector3f lookDir = new Vector3f(
            (float) (cosYaw * Math.cos(pitchRad)),
            (float)  Math.sin(pitchRad),
            (float) (sinYaw * Math.cos(pitchRad))
        );
        lastRaycast = RayCaster.cast(camera.getPosition(), lookDir, clientWorld, REACH);

        // --- Block interaction ---
        if (cursorCaptured && lastRaycast.hit()) {
            if (inputHandler.wasMouseLeftClicked()) {
                serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.BlockBreakPacket(
                    lastRaycast.blockX(), lastRaycast.blockY(), lastRaycast.blockZ()));
            }
            if (inputHandler.wasMouseRightClicked()) {
                // Guard: don't place a block inside the player (physics mode only)
                int px = lastRaycast.placeX(), py = lastRaycast.placeY(), pz = lastRaycast.placeZ();
                if (freecam || !player.getBody().overlapsBlock(px, py, pz)) {
                    serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.BlockPlacePacket(
                        px, py, pz, selectedBlock.ordinal()));
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
        lastRenderStats = clientWorld.render(shaderProgram, camera.getProjectionMatrix(), camera.getViewMatrix());
        shaderProgram.setUniform("useTexture", false);

        if (lastRaycast.hit()) {
            blockHighlight.render(shaderProgram,
                lastRaycast.blockX(), lastRaycast.blockY(), lastRaycast.blockZ());
        }

        clientWorld.renderRemotePlayers(shaderProgram);

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
        clientWorld.cleanup();
        shaderProgram.cleanup();
        window.cleanup();
        System.out.println("Engine shut down cleanly.");
    }
}