package com.voxelgame.engine;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.client.network.ClientNetworkManager;
import com.voxelgame.common.world.BlockType;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.common.world.RayCaster;
import com.voxelgame.common.world.RaycastResult;
import com.voxelgame.game.Player;
import com.voxelgame.game.screen.MainMenuScreen;
import com.voxelgame.game.screen.PauseMenuScreen;
import com.voxelgame.game.screen.ScreenManager;
import com.voxelgame.game.screen.WorldSelectScreen;
import com.voxelgame.server.GameServer;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
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
    private final String username;

    // Volatile: written by the world-launch background thread, read by the GL thread.
    // Null until the player selects a world and the server is ready.
    private volatile io.netty.channel.Channel  serverChannel = null;
    private volatile GameServer                activeServer   = null;
    private volatile ClientNetworkManager      activeNetwork  = null;

    /**
     * Actions posted by background threads (e.g. world launch) to be executed on
     * the GL thread at the start of the next loop iteration.
     * AtomicReference gives us a single-slot queue — one pending action at a time
     * is all we need here.
     */
    private final AtomicReference<Runnable> pendingMainThreadAction = new AtomicReference<>(null);

    private Camera                 camera;
    private InputHandler           inputHandler;
    private ShaderProgram          shaderProgram;
    private TextureManager         textureManager;
    private Player                 player;
    private BlockHighlightRenderer blockHighlight;
    private HudRenderer            hudRenderer;

    private RaycastResult lastRaycast   = RaycastResult.miss();
    private BlockType     selectedBlock = Blocks.DIRT;
    private boolean       cursorCaptured = true;
    private boolean       freecam        = false;
    private int           lastFbWidth;
    private int           lastFbHeight;
    private boolean       lastFKeyDown   = false;
    private int[]         lastRenderStats = { 0, 0 };
    private ScreenManager screenManager;

    // GLFW event callbacks — must be stored as fields to prevent garbage collection.
    // LWJGL registers these with native GLFW; if the JVM GC collects them, the
    // next native callback fires into freed memory and the JVM crashes.
    private GLFWKeyCallback         keyCallback;
    private GLFWMouseButtonCallback mouseButtonCallback;
    private GLFWCharCallback        charCallback;

    /**
     * Creates a GameLoop.
     *
     * @param clientWorld the client-side world — receives chunks from the server,
     *                    provides block queries for physics and raycasting
     * @param username    the local player's username — used when connecting to the server
     */
    public GameLoop(ClientWorld clientWorld, String username) {
        this.window      = new Window("Voxel Game", 1280, 720);
        this.clientWorld = clientWorld;
        this.username    = username;
    }

    /**
     * Creates a configured {@link MainMenuScreen}.
     * Extracted as a factory so the Back button in WorldSelectScreen can
     * return here without duplicating the callback wiring.
     */
    private MainMenuScreen createMainMenu() {
        return new MainMenuScreen(
            screenManager,
            // Singleplayer → push world selection screen
            () -> screenManager.setScreen(new WorldSelectScreen(
                screenManager,
                this::launchWorld, // BiConsumer<String, Long> — matches the new signature
                // Back button returns to main menu
                () -> screenManager.setScreen(createMainMenu())
            )),
            // Quit → close window
            () -> GLFW.glfwSetWindowShouldClose(window.getWindowHandle(), true)
        );
    }

    /**
     * Starts the embedded server for {@code worldName} on a daemon background thread.
     * Blocks that thread until the port is bound and the client connects, then posts
     * a main-thread action that dismisses the loading screen and begins gameplay.
     *
     * <p>Called from the GL thread (via WorldSelectScreen callback), but all blocking
     * work happens off the GL thread so the window does not freeze.</p>
     *
     * @param worldName subdirectory of {@code worlds/} to load or create
     * @param seed      the seed for the new world, or null for a random seed
     */
    private void launchWorld(String worldName, Long seed) {
        Thread launchThread = new Thread(() -> {
            try {
                Path worldDir = Path.of("worlds", worldName);
                GameServer server = (seed != null)
                    ? new GameServer(worldDir, GameServer.PORT, seed)
                    : new GameServer(worldDir, GameServer.PORT);

                Thread serverThread = new Thread(server::start, "embedded-server");
                serverThread.setDaemon(true);
                serverThread.start();

                // Blocks until the Netty port is bound (~<100 ms on localhost)
                server.awaitReady();

                ClientNetworkManager network = new ClientNetworkManager(
                    "localhost", GameServer.PORT, username, clientWorld
                );
                network.connect();

                // Store refs so cleanup() can shut them down cleanly
                this.activeServer  = server;
                this.activeNetwork = network;

                // Volatile write — visible to GL thread on next read
                this.serverChannel = network.getChannel();

                // Post main-thread action: dismiss the loading screen and start playing.
                // The GL thread picks this up at the top of the next loop iteration.
                pendingMainThreadAction.set(() -> {
                    screenManager.setScreen(null);
                    cursorCaptured = true;
                    GLFW.glfwSetInputMode(window.getWindowHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                    inputHandler.resetMouseDelta();
                });

            } catch (Exception e) {
                System.err.println("[GameLoop] Failed to launch world '" + worldName + "': " + e.getMessage());
            }
        }, "world-launch");
        launchThread.setDaemon(true);
        launchThread.start();
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

        // Release cursor immediately — main menu needs a free mouse.
        cursorCaptured = false;
        GLFW.glfwSetInputMode(window.getWindowHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);

        player = new Player(SPAWN_X, SPAWN_Y, SPAWN_Z);

        shaderProgram  = new ShaderProgram("/shaders/default.vert", "/shaders/default.frag");
        textureManager = new TextureManager();
        textureManager.init();
        shaderProgram.bind();
        shaderProgram.setUniform("texArray", 0);
        shaderProgram.unbind();

        blockHighlight = new BlockHighlightRenderer();
        hudRenderer    = new HudRenderer();

        screenManager = new ScreenManager();

        // Forward key press events to the active screen.
        // Note: this callback does NOT fire when no screen is active — game input
        // still uses InputHandler polling (isKeyDown, wasMouseLeftClicked, etc.)
        keyCallback = GLFWKeyCallback.create((win, key, scancode, action, mods) -> {
            if (action == GLFW.GLFW_PRESS) {
                // Escape handled separately — only on press, never on repeat
                handleEscapeKey(win, key);
            }
            // Forward both PRESS and REPEAT to screens.
            // REPEAT is the OS key-held signal — gives backspace/delete fast-delete behavior.
            // Screens that don't care (main menu) simply ignore keys they don't handle.
            // Escape is excluded from forwarding — handleEscapeKey owns it.
            if ((action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT)
                    && key != GLFW.GLFW_KEY_ESCAPE) {
                screenManager.onKeyPress(key, mods);
            }
        });
        GLFW.glfwSetKeyCallback(window.getWindowHandle(), keyCallback);

        // Forward mouse clicks to the active screen, including cursor position.
        mouseButtonCallback = GLFWMouseButtonCallback.create((win, button, action, mods) -> {
            if (action == GLFW.GLFW_PRESS && screenManager.hasActiveScreen()) {
                double[] x = {0}, y = {0};
                GLFW.glfwGetCursorPos(win, x, y);
                screenManager.onMouseClick((int) x[0], (int) y[0], button);
            }
        });
        GLFW.glfwSetMouseButtonCallback(window.getWindowHandle(), mouseButtonCallback);

        // Forward printable character events to the active screen.
        // glfwSetCharCallback fires only for printable characters with keyboard layout
        // and modifier keys already applied — correct for text input fields.
        charCallback = GLFWCharCallback.create((win, codepoint) -> {
            screenManager.onCharTyped((char) codepoint);
        });
        GLFW.glfwSetCharCallback(window.getWindowHandle(), charCallback);

        window.setRefreshCallback(() -> {
            if (screenManager.hasActiveScreen() && !screenManager.isActiveScreenOverlay()) {
                // Full-screen menu — clear and render only the menu
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                screenManager.renderActiveScreen(window.getFramebufferWidth(), window.getFramebufferHeight());
            } else {
                // Normal world render (runs for both in-game and overlay-menu-over-world)
                render();
                if (screenManager.hasActiveScreen()) {
                    // Overlay screen draws on top of the just-rendered world
                    screenManager.renderActiveScreen(window.getFramebufferWidth(), window.getFramebufferHeight());
                }
            }
            window.swapBuffers();
        });

        screenManager.setScreen(createMainMenu());

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

            // Drain any action posted from a background thread (e.g. world launch done)
            Runnable pendingAction = pendingMainThreadAction.getAndSet(null);
            if (pendingAction != null) pendingAction.run();

            double currentTime = System.currentTimeMillis();
            double elapsed     = currentTime - previousTime;
            previousTime       = currentTime;
            accumulator       += elapsed;

            while (accumulator >= updateInterval) {
                if (!screenManager.hasActiveScreen()) {
                    update();
                }
                updates++;
                accumulator -= updateInterval;
            }

            if (screenManager.hasActiveScreen() && !screenManager.isActiveScreenOverlay()) {
                // Full-screen menu — clear and render only the menu
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                screenManager.renderActiveScreen(window.getFramebufferWidth(), window.getFramebufferHeight());
            } else {
                // Normal world render (runs for both in-game and overlay-menu-over-world)
                render();
                if (screenManager.hasActiveScreen()) {
                    // Overlay screen draws on top of the just-rendered world
                    screenManager.renderActiveScreen(window.getFramebufferWidth(), window.getFramebufferHeight());
                }
            }
            frames++;

            if (System.currentTimeMillis() - diagnosticTimer >= 1000.0) {
                // Only print diagnostics while actually in-game — not on menus
                if (serverChannel != null && !screenManager.hasActiveScreen()) {
                    System.out.printf("FPS: %d | UPS: %d | Chunks: %d/%d | Mode: %s | Selected: %s%n",
                        frames, updates, lastRenderStats[0], lastRenderStats[1],
                        freecam ? "FREECAM" : "PHYSICS", selectedBlock);
                }
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
        //if (inputHandler.isKeyDown(GLFW.GLFW_KEY_ESCAPE) && cursorCaptured) {
        //    cursorCaptured = false;
        //    GLFW.glfwSetInputMode(window.getWindowHandle(),
        //        GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        //}
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
        if (serverChannel != null) serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.PlayerMoveSBPacket(
            pos.x, freecam ? pos.y : player.getBody().getPosition().y,
            pos.z, camera.getYaw(), camera.getPitch()
        ));

        // --- Block type selection ---
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_1)) selectedBlock = Blocks.GRASS;
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_2)) selectedBlock = Blocks.DIRT;
        if (inputHandler.isKeyDown(GLFW.GLFW_KEY_3)) selectedBlock = Blocks.STONE;

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
                if (serverChannel != null) serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.BlockBreakPacket(
                    lastRaycast.blockX(), lastRaycast.blockY(), lastRaycast.blockZ()));
            }
            if (inputHandler.wasMouseRightClicked()) {
                // Guard: don't place a block inside the player (physics mode only)
                int px = lastRaycast.placeX(), py = lastRaycast.placeY(), pz = lastRaycast.placeZ();
                if (freecam || !player.getBody().overlapsBlock(px, py, pz)) {
                    if (serverChannel != null) serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.BlockPlacePacket(
                        px, py, pz, selectedBlock.getId()));
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
     * Handles the Escape key globally.
     * <ul>
     *   <li>Full-screen menu active → do nothing (each screen handles its own Escape)</li>
     *   <li>Overlay (pause) menu active → forward to screen (Resume button or Escape closes it)</li>
     *   <li>In-game, cursor captured → open pause menu</li>
     *   <li>In-game, cursor released → recapture cursor</li>
     * </ul>
     */
    private void handleEscapeKey(long win, int key) {
        if (key != GLFW.GLFW_KEY_ESCAPE) return;

        if (screenManager.hasActiveScreen() && !screenManager.isActiveScreenOverlay()) {
            // Full-screen menu owns its own Escape handling via onKeyPress
            screenManager.onKeyPress(GLFW.GLFW_KEY_ESCAPE, 0);
            return;
        }
        if (screenManager.isActiveScreenOverlay()) {
            // Forward to the overlay screen (pause menu's Escape = Resume)
            screenManager.onKeyPress(GLFW.GLFW_KEY_ESCAPE, 0);
            return;
        }
        if (serverChannel == null) return; // not in-game, nothing to do

        if (cursorCaptured) {
            // Open pause menu and release cursor
            cursorCaptured = false;
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            screenManager.setScreen(new PauseMenuScreen(
                screenManager,
                // Resume
                () -> {
                    cursorCaptured = true;
                    GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                    inputHandler.resetMouseDelta();
                },
                // Main Menu
                this::returnToMainMenu,
                // Quit
                () -> GLFW.glfwSetWindowShouldClose(win, true)
            ));
        } else {
            // Cursor is free (shouldn't normally happen in-game, but recapture it)
            cursorCaptured = true;
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }

    /**
     * Disconnects from the current server, clears the world, and returns to the main menu.
     * Must be called on the GL thread — mesh cleanup (GL resource deletion) happens here.
     */
    private void returnToMainMenu() {
        // Disconnect network first so the server logs a clean player logout
        if (activeNetwork != null) {
            try { activeNetwork.disconnect(); }
            catch (Exception e) {
                System.err.println("[GameLoop] Disconnect error: " + e.getMessage());
            }
            activeNetwork = null;
        }

        // Stop the embedded server (non-blocking — server thread is daemon)
        if (activeServer != null) {
            activeServer.stop();
            activeServer = null;
        }

        // Null the channel so update() guards treat this as "not in-game"
        serverChannel = null;

        // Clear all chunk data and release GL mesh resources
        clientWorld.reset();

        // Reset player and camera to neutral state
        player = new Player(SPAWN_X, SPAWN_Y, SPAWN_Z);
        camera.getPosition().set(SPAWN_X, SPAWN_Y + Player.EYE_HEIGHT, SPAWN_Z);
        camera.setYaw(0);
        camera.setPitch(0);
        lastRaycast = RaycastResult.miss();

        // Release cursor and show main menu
        cursorCaptured = false;
        GLFW.glfwSetInputMode(window.getWindowHandle(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        screenManager.setScreen(createMainMenu());
    }

    /**
     * Shuts down all engine subsystems in reverse initialization order.
     */
    private void cleanup() {
        // Disconnect client before stopping server — lets the server log a clean logout
        if (activeNetwork != null) {
            try { activeNetwork.disconnect(); }
            catch (Exception e) { System.err.println("[GameLoop] Network disconnect error: " + e.getMessage()); }
        }
        if (activeServer != null) {
            activeServer.stop();
        }
        hudRenderer.cleanup();
        blockHighlight.cleanup();
        textureManager.cleanup();
        clientWorld.cleanup();
        shaderProgram.cleanup();
        screenManager.cleanup();
        keyCallback.free();
        mouseButtonCallback.free();
        charCallback.free();
        window.cleanup();
        System.out.println("Engine shut down cleanly.");
    }
}