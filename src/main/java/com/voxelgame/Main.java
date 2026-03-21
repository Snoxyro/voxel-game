package com.voxelgame;

import com.voxelgame.engine.GameLoop;

/**
 * Application entry point. Constructs the GameLoop and starts it.
 * Stays intentionally thin — no engine logic lives here.
 */
public class Main {

    public static void main(String[] args) {
        GameLoop gameLoop = new GameLoop();
        gameLoop.run();
    }
}