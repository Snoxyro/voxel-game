package com.voxelgame;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.engine.GameLoop;

/**
 * Singleplayer entry point.
 *
 * <p>Server lifecycle (start, connect, stop) is now managed by {@link GameLoop}
 * after the player selects a world from the menu. This class is intentionally
 * minimal — it only bootstraps the registries and hands control to the engine.
 */
public class Main {

    public static void main(String[] args) {
        Blocks.bootstrap();

        String username = "Player";
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--username")) username = args[i + 1];
        }

        ClientWorld clientWorld = new ClientWorld();
        new GameLoop(clientWorld, username).run();
    }
}