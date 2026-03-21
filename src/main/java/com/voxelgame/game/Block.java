package com.voxelgame.game;

/**
 * Represents a block type. AIR means empty space — no geometry is emitted for it.
 * Each solid block type declares its base RGB color used during mesh generation.
 */
public enum Block {
    AIR,
    GRASS,
    DIRT,
    STONE;

    /**
     * Returns the base RGB color for this block as a float[3] (values 0.0–1.0).
     * AIR has no color — it is never rendered.
     *
     * @return float array [r, g, b]
     */
    public float[] color() {
        return switch (this) {
            case GRASS -> new float[]{ 0.3f, 0.7f, 0.2f };
            case DIRT  -> new float[]{ 0.5f, 0.35f, 0.1f };
            case STONE -> new float[]{ 0.5f, 0.5f, 0.5f };
            case AIR   -> new float[]{ 0.0f, 0.0f, 0.0f };
        };
    }
}