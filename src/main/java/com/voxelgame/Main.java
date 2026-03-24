package com.voxelgame;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.engine.GameLoop;
import com.voxelgame.game.GameSettings;

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

        GameSettings settings = new GameSettings(java.nio.file.Path.of("settings.properties"));
        settings.load();

        ClientWorld clientWorld = new ClientWorld();
        new GameLoop(clientWorld, settings).run();
    }
}