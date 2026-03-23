package com.voxelgame.server;

import com.voxelgame.common.world.Blocks;

import java.nio.file.Path;

/**
 * Entry point for a dedicated (headless) server.
 *
 * <h3>Usage</h3>
 * <pre>
 *   ./gradlew runServer                                    # defaults
 *   ./gradlew runServer --args="--world survival"          # named world
 *   ./gradlew runServer --args="--world survival --port 25565"
 * </pre>
 *
 * <h3>Arguments</h3>
 * <ul>
 *   <li>{@code --world <name>} — world folder name under {@code worlds/},
 *       defaults to {@code default}</li>
 *   <li>{@code --port <n>} — TCP port, defaults to {@link GameServer#PORT}</li>
 * </ul>
 */
public class ServerMain {

    public static void main(String[] args) {
        Blocks.bootstrap();

        // --- Parse CLI args ---
        String worldName = "default";
        int    port      = GameServer.PORT;

        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--world" -> worldName = args[i + 1];
                case "--port"  -> {
                    try {
                        port = Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException e) {
                        System.err.println("[Server] Invalid port '" + args[i + 1]
                            + "' — using default " + GameServer.PORT);
                    }
                }
            }
        }

        Path worldDir = Path.of("worlds", worldName);
        System.out.printf("[Server] World: %s | Port: %d%n", worldDir, port);

        GameServer server = new GameServer(worldDir, port);

        // Clean shutdown on Ctrl+C / SIGTERM
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Server] Shutdown signal received.");
            server.stop();
        }, "server-shutdown"));

        System.out.println("[Server] Starting dedicated server...");
        server.start(); // blocks until stopped
        System.out.println("[Server] Server stopped.");
    }
}