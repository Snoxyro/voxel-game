package com.voxelgame;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.client.network.ClientNetworkManager;
import com.voxelgame.engine.GameLoop;
import com.voxelgame.server.GameServer;

/**
 * Singleplayer entry point. Launches an embedded {@link GameServer} on a daemon
 * background thread, waits for it to be ready, then connects a {@link ClientNetworkManager}
 * and starts the game window.
 *
 * <p>This is the same architecture as Minecraft's integrated server. Singleplayer is
 * multiplayer with one player on localhost — no separate code paths exist. For a
 * dedicated headless server, use {@link com.voxelgame.server.ServerMain}.
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // --- Shared ClientWorld ---
        // Created first so it can be passed to both the network layer (which writes
        // chunk data into it from the Netty I/O thread) and GameLoop (which reads
        // from it on the main GL thread).
        ClientWorld clientWorld = new ClientWorld();

        // --- Embedded server on daemon thread ---
        // Daemon: JVM exits when the client window closes, killing this thread automatically.
        GameServer server = new GameServer();
        Thread serverThread = new Thread(server::start, "embedded-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // --- Wait for port to bind before connecting ---
        server.awaitReady();

        // --- Connect client to embedded server ---
        ClientNetworkManager network = new ClientNetworkManager(
            "localhost", GameServer.PORT, "Player", clientWorld
        );
        network.connect();

        // --- Run the game ---
        GameLoop gameLoop = new GameLoop(clientWorld, network.getChannel());
        gameLoop.run(); // blocks until window is closed

        // --- Shutdown ---
        network.disconnect();
        server.stop();
    }
}