package com.voxelgame.game;

/**
 * Identifies a chunk by its position in the world grid.
 * Coordinates are in chunk-space, not block-space — multiply by
 * {@link Chunk#SIZE} to get the world-space origin of the chunk.
 */
public record ChunkPos(int x, int z) {

    /**
     * Returns the world-space X coordinate of this chunk's origin block.
     */
    public int worldX() {
        return x * Chunk.SIZE;
    }

    /**
     * Returns the world-space Z coordinate of this chunk's origin block.
     */
    public int worldZ() {
        return z * Chunk.SIZE;
    }
}