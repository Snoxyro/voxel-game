package com.voxelgame.game;

/**
 * Represents a fixed-size 3D chunk of blocks.
 * <p>
 * A chunk stores {@value #SIZE} x {@value #SIZE} x {@value #SIZE} blocks and
 * initializes all positions to {@link Block#AIR}.
 */
public class Chunk {

    /**
     * The edge length of this chunk in blocks.
     */
    public static final int SIZE = 16;

    private final Block[][][] blocks;

    /**
     * Creates a new chunk with all block positions initialized to
     * {@link Block#AIR}.
     */
    public Chunk() {
        this.blocks = new Block[SIZE][SIZE][SIZE];

        for (int x = 0; x < SIZE; x++) {
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    blocks[x][y][z] = Block.AIR;
                }
            }
        }
    }

    /**
     * Gets the block at the provided local chunk coordinates.
     *
     * @param x the local x coordinate
     * @param y the local y coordinate
     * @param z the local z coordinate
     * @return {@link Block#AIR} if coordinates are out of bounds; otherwise the
     * block stored at the coordinates
     */
    public Block getBlock(int x, int y, int z) {
        if (!isInBounds(x, y, z)) {
            return Block.AIR;
        }

        return blocks[x][y][z];
    }

    /**
     * Sets the block at the provided local chunk coordinates.
     *
     * @param x the local x coordinate
     * @param y the local y coordinate
     * @param z the local z coordinate
     * @param block the block value to store
     */
    public void setBlock(int x, int y, int z, Block block) {
        if (!isInBounds(x, y, z)) {
            return;
        }

        blocks[x][y][z] = block;
    }

    /**
     * Checks whether the provided local chunk coordinates are air.
     *
     * @param x the local x coordinate
     * @param y the local y coordinate
     * @param z the local z coordinate
     * @return {@code true} if coordinates are out of bounds or the block at that
     * position is {@link Block#AIR}; otherwise {@code false}
     */
    public boolean isAir(int x, int y, int z) {
        return getBlock(x, y, z) == Block.AIR;
    }

    private boolean isInBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE
            && y >= 0 && y < SIZE
            && z >= 0 && z < SIZE;
    }
}