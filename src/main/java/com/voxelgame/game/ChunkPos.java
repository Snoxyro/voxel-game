package com.voxelgame.game;

/**
 * Identifies a chunk by its 3D position in the world grid.
 * Coordinates are in chunk-space — multiply by {@link Chunk#SIZE}
 * to get the world-space origin of the chunk.
 */
public record ChunkPos(int x, int y, int z) {

    /** Returns the world-space X coordinate of this chunk's origin block. */
    public int worldX() { return x * Chunk.SIZE; }

    /** Returns the world-space Y coordinate of this chunk's origin block. */
    public int worldY() { return y * Chunk.SIZE; }

    /** Returns the world-space Z coordinate of this chunk's origin block. */
    public int worldZ() { return z * Chunk.SIZE; }
}