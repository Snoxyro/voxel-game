package com.voxelgame.engine;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.client.network.ClientNetworkManager;
import com.voxelgame.common.world.BlockType;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.common.world.RayCaster;
import com.voxelgame.common.world.RaycastResult;
import com.voxelgame.engine.ui.DarkTheme;
import com.voxelgame.engine.ui.LightTheme;
import com.voxelgame.game.Action;
import com.voxelgame.game.ChunkMesher;
import com.voxelgame.game.GameSettings;
import com.voxelgame.game.KeyBindings;
import com.voxelgame.game.Player;
import com.voxelgame.game.screen.MainMenuScreen;
import com.voxelgame.game.screen.MultiplayerConnectScreen;
import com.voxelgame.game.screen.PauseMenuScreen;
import com.voxelgame.game.screen.ScreenManager;
import com.voxelgame.game.screen.SettingsScreen;
import com.voxelgame.game.screen.WorldSelectScreen;
import com.voxelgame.server.GameServer;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    /** Persistent user settings — owns keybindings, render distance, FOV, etc. */
    private final GameSettings settings;

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
    /** Timestamp of the previous frame start — used to compute per-frame delta time. */
    private long          lastFrameTime = System.nanoTime();
    /** Last computed frame delta — stored so the window refresh callback can use it. */
    private float         lastDeltaTime = 0.016f; // sensible default before first frame

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
     * @param settings    the game settings, including key bindings and video options
        * @thread GL-main
        * @gl-state n/a
     */
    public GameLoop(ClientWorld clientWorld, GameSettings settings) {
        this.window      = new Window("Voxel Game", 1280, 720);
        this.clientWorld = clientWorld;
        this.settings    = settings;
    }

    /**
     * Creates a configured {@link MainMenuScreen}.
     * Extracted as a factory so the Back button in WorldSelectScreen can
     * return here without duplicating the callback wiring.
        *
        * @thread GL-main
        * @gl-state n/a
     */
    private MainMenuScreen createMainMenu() {
        Runnable onSingleplayer = () -> {
            // Need the screen reference before constructing it to wire the error callback.
            // Use a single-element array as a mutable capture (standard Java lambda trick).
            WorldSelectScreen[] ref = new WorldSelectScreen[1];
            ref[0] = new WorldSelectScreen(
                screenManager,
                (name, seed) -> launchWorld(name, seed, msg -> {
                    if (ref[0] != null) ref[0].setStatusMessage(msg);
                }),
                () -> screenManager.setScreen(createMainMenu())
            );
            screenManager.setScreen(ref[0]);
        };

        Runnable onMultiplayer = () -> screenManager.setScreen(new MultiplayerConnectScreen(
            screenManager,
            this::connectToMultiplayer,
            () -> screenManager.setScreen(createMainMenu())
        ));

        Runnable onQuit = () -> GLFW.glfwSetWindowShouldClose(window.getWindowHandle(), true);

        Runnable onSettings = () -> openSettings(() -> screenManager.setScreen(createMainMenu()));

        return new MainMenuScreen(screenManager, onSingleplayer, onMultiplayer, onSettings, onQuit);
    }

    /**
     * Creates a configured {@link PauseMenuScreen}.
     * Extracted as a factory so Settings can return here without duplicating
     * the callback wiring in handleEscapeKey.
     *
     * @param win the GLFW window handle — needed for cursor mode changes
        * @thread GL-main
        * @gl-state n/a
     */
    private PauseMenuScreen createPauseMenu(long win) {
        return new PauseMenuScreen(
            screenManager,
            // Resume — recapture cursor and resume gameplay
            () -> {
                cursorCaptured = true;
                GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                inputHandler.resetMouseDelta();
                inputHandler.consumeMouseClick(); // prevent the resume click from breaking a block
            },
            // Settings — open settings screen; returning goes back to pause menu
            () -> openSettings(() -> screenManager.setScreen(createPauseMenu(win))),
            // Main Menu — disconnect and return to menu
            this::returnToMainMenu,
            // Quit
            () -> GLFW.glfwSetWindowShouldClose(win, true)
        );
    }

    /**
     * Opens the settings screen. When the player saves or cancels, {@code onDone}
     * is called to return to the appropriate previous screen.
     *
     * @param onDone called after save or cancel; should restore the previous screen
        * @thread GL-main
        * @gl-state n/a
        * @see #applySettings()
     */
    private void openSettings(Runnable onDone) {
        screenManager.setScreen(new SettingsScreen(
            settings,
            isSessionActive(),
            () -> { applySettings(); onDone.run(); },  // Save: apply then return
            onDone                                      // Cancel: just return
        ));
    }

    /**
     * Applies all settings that take effect immediately after saving.
     * Called on the GL thread immediately after the player hits Save.
     *
     * <p>Not all settings are live — username takes effect on the next connection.
     * Only settings marked "Live? Yes" in the settings design table are applied here.
        *
        * @thread GL-main
        * @gl-state shader=bound (temporarily for u_brightnessFloor write), then shader=unbound
        * @see #applyWindowMode(GameSettings.WindowMode)
     */
    public void applySettings() {
        // VSync — changes the swap interval immediately
        GLFW.glfwSwapInterval(settings.isVsync() ? 1 : 0);

        // FOV — updates the projection matrix on the next render
        camera.setFov(settings.getFov());

        // Window mode — repositions or fullscreens the OS window
        applyWindowMode(settings.getWindowMode());

        // Theme — hot-swap the active theme; zero GL work, takes effect next frame
        String themeName = settings.getTheme();
        com.voxelgame.engine.ui.UiRenderer renderer = screenManager.getTheme().renderer();
        screenManager.setTheme("light".equalsIgnoreCase(themeName)
            ? new LightTheme(renderer)
            : new DarkTheme(renderer));

        // Render distance — push to the server world if a session is active
        if (activeServer != null) {
            activeServer.setRenderDistance(settings.getRenderDistance());
        }

        // Brightness floor — bind the world shader to write the uniform
        if (shaderProgram != null) {
            shaderProgram.bind();
            shaderProgram.setUniform("u_brightnessFloor", settings.getBrightnessFloor());
            shaderProgram.unbind();
        }

        // AO toggle — update the mesher flag and trigger a full remesh if the
        // value changed. The remesh is async — chunks rebuild over the next few
        // frames at the normal worker rate, so there is no hitch.
        if (ChunkMesher.aoEnabled != settings.isAoEnabled()) {
            ChunkMesher.aoEnabled = settings.isAoEnabled();
            if (clientWorld != null) {
                clientWorld.invalidateAllMeshes();
            }
        }
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
     * @param onFailure callback to invoke if the launch fails
    * @thread GL-main (caller); spawns worker thread for blocking startup
    * @gl-state n/a
    * @see #loop()
     */
    private void launchWorld(String worldName, Long seed, Consumer<String> onFailure) {
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

                // Check if the port bind failed (e.g. port already in use)
                if (server.getBindError() != null) {
                    throw new Exception("Failed to bind port " + GameServer.PORT + ": " + server.getBindError().getMessage());
                }

                ClientNetworkManager network = new ClientNetworkManager(
                    "localhost", GameServer.PORT, settings.getUsername(), clientWorld
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
                String msg = e.getMessage() != null ? e.getMessage() : "Failed to start server.";
                pendingMainThreadAction.set(() -> onFailure.accept(msg));
            }
        }, "world-launch");
        launchThread.setDaemon(true);
        launchThread.start();
    }

    /**
     * Begins a multiplayer connection attempt on a daemon background thread.
     * No embedded server is started — connects directly to an external server.
     *
     * <p>On success: stores the network reference and posts a main-thread action
     * that captures the cursor and dismisses the connect screen to enter gameplay.
     *
     * <p>On failure: posts {@code onFailure} to the GL thread via
     * {@link #pendingMainThreadAction} so the connect screen can display the error.
     *
     * <p>Cancellation: if {@code cancelledFlag} is set (player clicked Back),
     * the thread discards its result and cleans up the partial connection silently.
     *
     * @param host          server hostname or IP address
     * @param port          server TCP port
     * @param cancelledFlag written by {@link MultiplayerConnectScreen#onHide()} to signal abort
     * @param onFailure     called on the GL thread with a human-readable error message
    * @thread GL-main (caller); spawns worker thread for blocking connect
    * @gl-state n/a
    * @see #friendlyErrorMessage(Exception)
     */
    private void connectToMultiplayer(String host, int port, AtomicBoolean cancelledFlag, Consumer<String> onFailure) {
        Thread connectThread = new Thread(() -> {
            ClientNetworkManager network = new ClientNetworkManager(host, port, settings.getUsername(), clientWorld);
            try {
                network.connect(); // blocks until TCP handshake completes or throws

                if (cancelledFlag.get()) {
                    // Player navigated away while this thread was connecting — discard
                    network.disconnect();
                    return;
                }

                // Volatile writes — visible to the GL thread on the next loop iteration
                this.activeNetwork = network;
                this.serverChannel = network.getChannel();
                // activeServer stays null — there is no embedded server for multiplayer

                pendingMainThreadAction.set(() -> {
                    if (cancelledFlag.get()) {
                        // Extremely narrow race: cancelled just after the check above.
                        // returnToMainMenu() will clean up if the player navigates away later.
                        return;
                    }
                    // Enter gameplay: capture cursor and dismiss the connect screen
                    cursorCaptured = true;
                    GLFW.glfwSetInputMode(window.getWindowHandle(),
                            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                    inputHandler.resetMouseDelta();
                    screenManager.setScreen(null);
                });

            } catch (Exception e) {
                if (cancelledFlag.get()) {
                    // Player left before we could report the failure — clean up silently
                    try { network.disconnect(); } catch (Exception ignored) {}
                    return;
                }
                String msg = friendlyErrorMessage(e);
                pendingMainThreadAction.set(() -> onFailure.accept(msg));
            }
        }, "multiplayer-connect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /**
     * Converts a raw Netty connection exception into a short, user-readable string.
     * Netty messages contain addresses and stack details that are unhelpful in a UI context.
     *
     * @param e the exception thrown by {@link ClientNetworkManager#connect()}
     * @return a message short enough to fit the connect screen status line
        * @thread any
        * @gl-state n/a
     */
    private static String friendlyErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg == null)                                          return "Connection failed.";
        if (msg.contains("Connection refused"))                   return "Connection refused.";
        if (msg.contains("timed out")
            || msg.contains("ETIMEDOUT")
            || msg.contains("ConnectTimeoutException"))           return "Connection timed out.";
        if (msg.contains("No route to host"))                     return "Host unreachable.";
        if (msg.contains("nodename nor servname")
            || msg.contains("Name or service not known"))         return "Unknown host.";
        // Fallback: truncate raw message to fit the status line
        return msg.length() > 55 ? msg.substring(0, 52) + "..." : msg;
    }

    /**
     * Entry point for the engine. Initializes all systems, runs the loop,
     * then cleans up on exit.
        *
        * @thread GL-main
        * @gl-state n/a
        * @see #init()
        * @see #loop()
        * @see #cleanup()
     */
    public void run() {
        init();
        loop();
        cleanup();
    }

    /**
     * Initializes all engine subsystems. Window must come first — the OpenGL context
     * must exist before any GL calls.
        *
        * @thread GL-main
        * @gl-state cull=enabled, depth-test=enabled
     */
    private void init() {
        window.init();

        // Apply VSync from settings — this is why FPS was locked before:
        // glfwSwapInterval(1) was hardcoded with no way to change it.
        GLFW.glfwSwapInterval(settings.isVsync() ? 1 : 0);
        applyWindowMode(settings.getWindowMode());

        clientWorld.initRenderResources();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_CULL_FACE);

        lastFbWidth  = window.getFramebufferWidth();
        lastFbHeight = window.getFramebufferHeight();
        camera = new Camera(lastFbWidth, lastFbHeight);
        camera.setFov(settings.getFov());  // apply saved FOV
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
                inputHandler.onKeyPressed(key);
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
                screenManager.renderActiveScreen(lastDeltaTime, window.getFramebufferWidth(), window.getFramebufferHeight());
            } else {
                // Normal world render (runs for both in-game and overlay-menu-over-world)
                render();
                if (screenManager.hasActiveScreen()) {
                    // Overlay screen draws on top of the just-rendered world
                    screenManager.renderActiveScreen(lastDeltaTime, window.getFramebufferWidth(), window.getFramebufferHeight());
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
        *
        * @thread GL-main
        * @gl-state context-current
        * @see #update()
        * @see #render()
     */
    private void loop() {
        double previousTime    = System.currentTimeMillis();
        double updateInterval  = 1000.0 / TARGET_UPS;
        double accumulator     = 0.0;
        int    frames          = 0;
        int    updates         = 0;
        double diagnosticTimer = System.currentTimeMillis();

        while (!window.shouldClose()) {
            // Clamp to 100ms max — prevents a massive spike on the first frame or after
            // a lag hitch from trickling into timers and animations downstream.
            long now = System.nanoTime();
            lastDeltaTime = Math.min((now - lastFrameTime) / 1_000_000_000f, 0.1f);
            lastFrameTime = now;

            window.pollEvents();

            // Drain any action posted from a background thread (e.g. world launch done)
            Runnable pendingAction = pendingMainThreadAction.getAndSet(null);
            if (pendingAction != null) pendingAction.run();

            double currentTime = System.currentTimeMillis();
            double elapsed     = currentTime - previousTime;
            previousTime       = currentTime;
            accumulator       += elapsed;

            while (accumulator >= updateInterval) {
                if (screenManager.isActiveScreenOverlay()) {
                    // World state must keep draining during overlay screens (pause menu) —
                    // block changes and remote player moves queue up on Netty threads and
                    // must be applied every frame regardless of menu state.
                    // Player input and movement are intentionally skipped while paused.
                    clientWorld.update(camera.getPosition().y);
                } else if (!screenManager.hasActiveScreen()) {
                    update();
                }
                updates++;
                accumulator -= updateInterval;
            }

            if (screenManager.hasActiveScreen() && !screenManager.isActiveScreenOverlay()) {
                // Full-screen menu — clear and render only the menu
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                screenManager.renderActiveScreen(lastDeltaTime, window.getFramebufferWidth(), window.getFramebufferHeight());
            } else {
                // Normal world render (runs for both in-game and overlay-menu-over-world)
                render();
                if (screenManager.hasActiveScreen()) {
                    // Overlay screen draws on top of the just-rendered world
                    screenManager.renderActiveScreen(lastDeltaTime, window.getFramebufferWidth(), window.getFramebufferHeight());
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
     * Returns true while the key or mouse button bound to {@code action} is held.
     * Returns false if the action is unbound.
        *
        * @thread GL-main
        * @gl-state n/a
     */
    private boolean isActionDown(Action action) {
        int code = settings.getKeyBindings().get(action);
        if (code == KeyBindings.UNBOUND) return false;
        if (KeyBindings.isMouse(code)) {
            return inputHandler.isMouseButtonDown(KeyBindings.rawMouseButton(code));
        }
        return inputHandler.isKeyDown(code);
    }

    /**
     * Returns true on the single frame where the key or mouse button bound to
     * {@code action} just transitioned from up to down.
     * Returns false if the action is unbound.
        *
        * @thread GL-main
        * @gl-state n/a
     */
    private boolean wasActionJustPressed(Action action) {
        int code = settings.getKeyBindings().get(action);
        if (code == KeyBindings.UNBOUND) return false;
        if (KeyBindings.isMouse(code)) {
            return inputHandler.wasMouseButtonJustPressed(KeyBindings.rawMouseButton(code));
        }
        return inputHandler.wasKeyJustPressed(code);  // was: return false
    }

    /**
     * Game logic update — called TARGET_UPS times per second.
        *
        * @thread GL-main
        * @gl-state n/a
        * @see ClientWorld#update()
     */
    private void update() {
        // Drain pending mesh builds from the server's chunk stream.
        // This is the only ClientWorld call in update — the server drives chunk
        // loading, not the client. clientWorld.update() processes incoming chunks
        // and uploads finalized meshes to the GPU.
        clientWorld.update(camera.getPosition().y);

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
        if (inputHandler.wasMouseButtonJustPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT) && !cursorCaptured) {
            cursorCaptured = true;
            GLFW.glfwSetInputMode(window.getWindowHandle(),
                GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            return; // consume the click — don't also attempt a block break
        }

        // --- Mouse look ---
        if (cursorCaptured) {
            float sens  = settings.getMouseSensitivity() * 0.1f; // scale down to a sensible range (So default 1.0f becomes 0.1f)
            float yaw   = camera.getYaw()   + inputHandler.getMouseDeltaX() * sens;
            float pitch = camera.getPitch() - inputHandler.getMouseDeltaY() * sens;
            camera.setYaw(yaw);
            camera.setPitch(pitch);
        }

        // --- Freecam toggle ---
        int freecamCode  = settings.getKeyBindings().get(Action.TOGGLE_FREECAM);
        boolean fKeyDown = freecamCode != KeyBindings.UNBOUND && inputHandler.isKeyDown(freecamCode);
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
            if (isActionDown(Action.MOVE_FORWARD))  { pos.x += cosYaw * FREECAM_SPEED; pos.z += sinYaw * FREECAM_SPEED; }
            if (isActionDown(Action.MOVE_BACKWARD)) { pos.x -= cosYaw * FREECAM_SPEED; pos.z -= sinYaw * FREECAM_SPEED; }
            if (isActionDown(Action.MOVE_LEFT))     { pos.x += sinYaw * FREECAM_SPEED; pos.z -= cosYaw * FREECAM_SPEED; }
            if (isActionDown(Action.MOVE_RIGHT))    { pos.x -= sinYaw * FREECAM_SPEED; pos.z += cosYaw * FREECAM_SPEED; }
            if (isActionDown(Action.JUMP))          pos.y += FREECAM_SPEED;
            // Left shift for fly-down stays hardcoded — it has no Action yet, it's not a bound action
            if (inputHandler.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)) pos.y -= FREECAM_SPEED;
        } else {
            float moveX = 0, moveZ = 0;
            if (isActionDown(Action.MOVE_FORWARD))  { moveX += cosYaw; moveZ += sinYaw; }
            if (isActionDown(Action.MOVE_BACKWARD)) { moveX -= cosYaw; moveZ -= sinYaw; }
            if (isActionDown(Action.MOVE_LEFT))     { moveX += sinYaw; moveZ -= cosYaw; }
            if (isActionDown(Action.MOVE_RIGHT))    { moveX -= sinYaw; moveZ += cosYaw; }

            boolean wantsJump = isActionDown(Action.JUMP);
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
            if (wasActionJustPressed(Action.BREAK_BLOCK)) {
                int bx = lastRaycast.blockX();
                int by = lastRaycast.blockY();
                int bz = lastRaycast.blockZ();

                // 1. Client-Side Prediction: Apply block change and light update locally instantly
                clientWorld.setBlock(bx, by, bz, Blocks.AIR);

                // 2. Transmit action to the server for authoritative validation
                if (serverChannel != null) {
                    serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.BlockBreakPacket(bx, by, bz));
                }
            }
            if (wasActionJustPressed(Action.PLACE_BLOCK)) {
                int px = lastRaycast.placeX();
                int py = lastRaycast.placeY();
                int pz = lastRaycast.placeZ();

                if (freecam || !player.getBody().overlapsBlock(px, py, pz)) {
                    // 1. Client-Side Prediction: Apply block change and light update locally instantly
                    clientWorld.setBlock(px, py, pz, selectedBlock);

                    // 2. Transmit action to the server for authoritative validation
                    if (serverChannel != null) {
                        serverChannel.writeAndFlush(new com.voxelgame.common.network.packets.BlockPlacePacket(px, py, pz, selectedBlock.getId()));
                    }
                }
            }
        }

        inputHandler.clearJustPressed();
    }

    /**
     * Renders the current frame: 3D world, block highlight, then 2D HUD.
        *
        * @thread GL-main
        * @gl-state shader=bound during world draw, then shader=unbound
     */
    private void render() {
        // Sky colour tracks the day/night cycle. Falls back to a default blue if no
        // world is loaded (e.g. while the main menu is open).
        if (clientWorld != null) {
            float[] sky = clientWorld.getSkyColor();
            GL11.glClearColor(sky[0], sky[1], sky[2], 1.0f);
        } else {
            GL11.glClearColor(0.53f, 0.81f, 0.98f, 1.0f);
        }
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", camera.getProjectionMatrix());
        shaderProgram.setUniform("viewMatrix", camera.getViewMatrix());
        // Brightness floor: lifts pitch-black unlit areas. Driven by the settings slider.
        // 0.0 = full darkness in caves; 0.3 = dark but shapes visible.
        shaderProgram.setUniform("u_brightnessFloor", settings.getBrightnessFloor());
        shaderProgram.setUniform("u_ambientFactor", clientWorld != null ? clientWorld.getAmbientFactor() : 1.0f);
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
    *
    * @thread GL-main
    * @gl-state n/a
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
            screenManager.setScreen(createPauseMenu(win));
        } else {
            // Cursor is free (shouldn't normally happen in-game, but recapture it)
            cursorCaptured = true;
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
    }

    /**
     * Disconnects from the current server, clears the world, and returns to the main menu.
     * Must be called on the GL thread — mesh cleanup (GL resource deletion) happens here.
        *
        * @thread GL-main
        * @gl-state n/a
        * @see ClientWorld#reset()
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
     * Applies the window display mode from settings.
     * Must be called on the main (GL) thread.
     *
     * @param mode the desired window mode
        * @thread GL-main
        * @gl-state n/a
     */
    private void applyWindowMode(GameSettings.WindowMode mode) {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        long win     = window.getWindowHandle();

        switch (mode) {
            case FULLSCREEN -> {
                // True fullscreen — switches the monitor's video mode.
                // Uses the monitor's current (native) resolution.
                org.lwjgl.glfw.GLFWVidMode vid = GLFW.glfwGetVideoMode(monitor);
                if (vid != null) {
                    GLFW.glfwSetWindowMonitor(win, monitor, 0, 0,
                        vid.width(), vid.height(), vid.refreshRate());
                }
            }
            case BORDERLESS -> {
                // Borderless windowed — remove decoration and maximize to cover the screen.
                org.lwjgl.glfw.GLFWVidMode vid = GLFW.glfwGetVideoMode(monitor);
                if (vid != null) {
                    GLFW.glfwSetWindowAttrib(win, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                    GLFW.glfwSetWindowPos(win, 0, 0);
                    GLFW.glfwSetWindowSize(win, vid.width(), vid.height());
                }
            }
            case WINDOWED -> {
                // Restore decorated window. Use a sensible default size.
                GLFW.glfwSetWindowAttrib(win, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
                GLFW.glfwSetWindowMonitor(win, 0, 100, 100, 1280, 720, GLFW.GLFW_DONT_CARE);
            }
        }
    }

    /**
     * Returns true when the player is in an active game session (singleplayer or
     * multiplayer). Settings that cannot change mid-session (e.g. username) use
     * this to lock their widgets.
     *
     * @return true if a server channel is open
        * @thread any
        * @gl-state n/a
     */
    public boolean isSessionActive() {
        return serverChannel != null;
    }

    /**
     * Shuts down all engine subsystems in reverse initialization order.
        *
        * @thread GL-main
        * @gl-state resources-destroyed
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