package com.voxelgame.common.world;

import com.voxelgame.engine.TextureManager;

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
     * Returns the texture array layer index for this block's top face.
     *
     * @return texture array layer index for the top face
     */
    public int topTextureLayer() {
        return switch (this) {
            case GRASS -> TextureManager.LAYER_GRASS_TOP;
            case DIRT  -> TextureManager.LAYER_DIRT;
            case STONE -> TextureManager.LAYER_STONE;
            case AIR   -> 0;
        };
    }

    /**
     * Returns the texture array layer index for this block's side faces (N/S/E/W).
     *
     * @return texture array layer index for the side faces
     */
    public int sideTextureLayer() {
        return switch (this) {
            case GRASS -> TextureManager.LAYER_GRASS_SIDE;
            case DIRT  -> TextureManager.LAYER_DIRT;
            case STONE -> TextureManager.LAYER_STONE;
            case AIR   -> 0;
        };
    }

    /**
     * Returns the texture array layer index for this block's bottom face.
     *
     * @return texture array layer index for the bottom face
     */
    public int bottomTextureLayer() {
        return switch (this) {
            case GRASS -> TextureManager.LAYER_DIRT;
            case DIRT  -> TextureManager.LAYER_DIRT;
            case STONE -> TextureManager.LAYER_STONE;
            case AIR   -> 0;
        };
    }
}