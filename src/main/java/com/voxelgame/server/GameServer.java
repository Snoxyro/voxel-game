package com.voxelgame.server;

import com.voxelgame.server.network.ServerNetworkManager;
import com.voxelgame.server.storage.FlatFileChunkStorage;

import io.netty.channel.Channel;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The authoritative game server. Owns the network layer, the world, and the 20 TPS
 * game loop that drives chunk streaming and will drive gameplay in later phases.
 *
 * <h3>Lifecycle</h3>
 * {@link #start()} blocks until the server is stopped via {@link #stop()}.
 * Internally it starts Netty on a daemon thread, waits for the port to bind,
 * then runs the game loop on the calling thread. Run {@link #start()} on a
 * dedicated background thread (daemon for singleplayer, foreground for dedicated).
 *
 * <h3>Singleplayer integration (Phase 5F)</h3>
 * {@link com.voxelgame.Main} starts an embedded GameServer on a daemon thread.
 * {@link ServerMain} runs it foreground for dedicated server mode.
 * No behavioral difference — the architecture is identical.
 */
public class GameServer {

    /** Default TCP port. Unassigned by IANA, not used by any known game or service. */
    public static final int  PORT    = 24463;

    /** Server tick rate. Client runs at 60 UPS — 3 client frames per server tick. */
    private static final int SERVER_TPS = 20;

    /** Milliseconds per server tick. */
    private static final long TICK_INTERVAL_MS = 1000L / SERVER_TPS;

    /** World seed used for terrain generation. Could become a world.dat value in Phase 5E. */
    private static final long WORLD_SEED = 12345L;

    // Spawn coordinates — above the highest possible terrain surface.
    // MAX surface = BASE_HEIGHT(8) + HEIGHT_VARIATION(36) = 44 — 70 is safe.
    public static final float SPAWN_X = 64.0f;
    public static final float SPAWN_Y = 70.0f;
    public static final float SPAWN_Z = 64.0f;

    private final ServerNetworkManager network;
    private final ServerWorld          serverWorld;

    /** Counts down once the Netty port is bound. Lets the game loop start on time. */
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    /** Set to false by {@link #stop()} to break the game loop. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Monotonically increasing player ID counter. Thread-safe — assigned on Netty thread. */
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);

    /**
     * Creates a GameServer on the default port {@link #PORT}.
     */
    public GameServer() {
        this(PORT);
    }

    /**
     * Creates a GameServer on a custom port.
     *
     * @param port TCP port to listen on
     */
    public GameServer(int port) {
        FlatFileChunkStorage storage = new FlatFileChunkStorage(Path.of("worlds", "default"));
        this.serverWorld = new ServerWorld(WORLD_SEED, storage);
        this.network     = new ServerNetworkManager(port, this);
    }

    /**
     * Starts the server and blocks until stopped. Designed to run on a dedicated thread.
     *
     * <ol>
     *   <li>Starts Netty on a daemon background thread.</li>
     *   <li>Waits for the port to bind ({@link #awaitReady()} unblocks after this).</li>
     *   <li>Runs the 20 TPS game loop until {@link #stop()} is called.</li>
     * </ol>
     */
    public void start() {
        // Start Netty on a daemon thread so it doesn't prevent JVM exit
        Thread netThread = new Thread(() -> {
            try {
                network.start(readyLatch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "server-network");
        netThread.setDaemon(true);
        netThread.start();

        // Wait for the port to bind before starting the game loop
        try {
            readyLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // --- 20 TPS game loop ---
        running.set(true);
        while (running.get()) {
            long tickStart = System.currentTimeMillis();

            tick();

            long elapsed  = System.currentTimeMillis() - tickStart;
            long sleepFor = TICK_INTERVAL_MS - elapsed;
            if (sleepFor > 0) {
                try {
                    Thread.sleep(sleepFor);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        serverWorld.cleanup();
    }

    /**
     * One server tick. Called at 20 TPS from the game loop.
     * Phase 5B: drives chunk streaming via {@link ServerWorld#tick()}.
     * Phase 5D: will also process player move packets and broadcast positions.
     */
    private void tick() {
        serverWorld.tick();
    }

    /**
     * Blocks the calling thread until the server's port is bound and it is accepting
     * connections. Call after starting the server on a background thread before
     * connecting a client.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    public void awaitReady() throws InterruptedException {
        readyLatch.await();
    }

    /**
     * Stops the server. The game loop exits and {@link #start()} returns on the server
     * thread. Safe to call from any thread.
     */
    public void stop() {
        running.set(false);
        network.stop();
    }

    /** @return the server world — used by {@link com.voxelgame.server.network.ClientHandler} */
    public ServerWorld getServerWorld() {
        return serverWorld;
    }

    // -------------------------------------------------------------------------
    // Callbacks from ClientHandler — called on Netty I/O thread
    // -------------------------------------------------------------------------

    /**
     * Called when a client completes login. Creates a {@link PlayerSession}, registers
     * it with the world, and returns the assigned player ID.
     *
     * @param channel  the client's Netty channel
     * @param username the requested username (unverified in offline mode)
     * @return the newly assigned player ID
     */
    public int onPlayerLogin(Channel channel, String username) {
        int id = nextPlayerId.getAndIncrement();
        System.out.printf("[Server] Player '%s' logged in (id=%d, addr=%s)%n",
            username, id, channel.remoteAddress());

        PlayerSession session = new PlayerSession(id, username, channel, SPAWN_X, SPAWN_Y, SPAWN_Z);
        serverWorld.addPlayer(session);
        return id;
    }

    /**
     * Called when a client disconnects after reaching the Play state.
     * Removes the player from the world — their chunks are no longer tracked.
     * Phase 5D: will also despawn them for other clients.
     *
     * @param playerId the disconnected player's ID
     * @param username the disconnected player's username (for logging)
     */
    public void onPlayerDisconnect(int playerId, String username) {
        System.out.printf("[Server] Player '%s' (id=%d) disconnected%n", username, playerId);
        serverWorld.removePlayer(playerId);
    }
}