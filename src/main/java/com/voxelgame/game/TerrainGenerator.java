package com.voxelgame.game;

import com.voxelgame.util.OpenSimplex2S;

/**
 * Generates terrain for a chunk using 2D OpenSimplex noise.
 *
 * <p>Each XZ column is assigned a height by sampling the noise function.
 * Blocks are then filled from y=0 up to that height with layered block types:
 * STONE at the base, DIRT in the middle, GRASS on top.
 */
public class TerrainGenerator {

    /** The lowest possible surface height in blocks. */
    private static final int BASE_HEIGHT = 4;

    /** Maximum additional height added by the noise function. */
    private static final int HEIGHT_VARIATION = 10;

    /**
     * How many DIRT layers sit below the GRASS surface.
     * Everything below DIRT is STONE.
     */
    private static final int DIRT_DEPTH = 3;

    /**
     * Controls how zoomed-in the noise is. Smaller = broader, smoother hills.
     * Larger = tighter, noisier terrain.
     */
    private static final double NOISE_SCALE = 0.04;

    private final long seed;

    /**
     * Creates a TerrainGenerator with the given world seed.
     * The same seed always produces the same terrain.
     *
     * @param seed the world seed
     */
    public TerrainGenerator(long seed) {
        this.seed = seed;
    }

    /**
     * Generates and returns a fully populated chunk at the given grid position.
     *
     * @param pos the chunk's grid coordinate (chunk-space, not block-space)
     * @return a new Chunk with terrain blocks filled in
     */
    public Chunk generateChunk(ChunkPos pos) {
        Chunk chunk = new Chunk();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {

                // Convert local chunk coordinates to world-space block coordinates
                // so the noise samples are consistent across chunk boundaries
                int worldX = pos.worldX() + x;
                int worldZ = pos.worldZ() + z;

                // noise2 returns -1.0 to 1.0 — remap to 0.0 to 1.0 first,
                // then scale to our height range
                double noiseVal = OpenSimplex2S.noise2(seed, worldX * NOISE_SCALE, worldZ * NOISE_SCALE);
                int surfaceHeight = BASE_HEIGHT + (int) ((noiseVal + 1.0) / 2.0 * HEIGHT_VARIATION);

                // Fill blocks from bedrock (y=0) up to the surface
                for (int y = 0; y <= surfaceHeight; y++) {
                    Block block;
                    if (y == surfaceHeight) {
                        block = Block.GRASS;
                    } else if (y >= surfaceHeight - DIRT_DEPTH) {
                        block = Block.DIRT;
                    } else {
                        block = Block.STONE;
                    }
                    chunk.setBlock(x, y, z, block);
                }
            }
        }

        return chunk;
    }
}