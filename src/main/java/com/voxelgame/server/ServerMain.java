package com.voxelgame.server;

/**
 * Entry point for a dedicated (headless) server.
 *
 * <p>Usage: {@code ./gradlew runServer}
 *
 * <p>Unlike {@link com.voxelgame.Main}, no game window is opened. The server
 * runs until the process is killed or Ctrl+C is pressed. The JVM shutdown hook
 * ensures a clean stop.
 */
public class ServerMain {

    public static void main(String[] args) {
        GameServer server = new GameServer();

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