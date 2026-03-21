package com.voxelgame.game;

import com.voxelgame.util.OpenSimplex2S;

/**
 * Generates terrain for a chunk using fractional Brownian motion (fBm) —
 * multiple octaves of OpenSimplex2 noise layered at increasing frequencies
 * and decreasing amplitudes.
 *
 * <p>Each XZ column is assigned a surface height by summing the octaves.
 * Blocks are filled from y=0 up to that height with layered block types:
 * STONE at the base, DIRT in the middle, GRASS on top.
 */
public class TerrainGenerator {

    /** The lowest possible surface height in blocks. */
    private static final int BASE_HEIGHT = 8;

    /**
     * The maximum total height variation across all octaves combined.
     * The actual range is [BASE_HEIGHT, BASE_HEIGHT + HEIGHT_VARIATION].
     */
    private static final int HEIGHT_VARIATION = 24;

    /** How many DIRT layers sit directly below the GRASS surface. */
    private static final int DIRT_DEPTH = 3;

    // --- fBm parameters ---

    /** Number of noise layers stacked. More = more detail, more cost. */
    private static final int OCTAVES = 4;

    /**
     * Base frequency of the first (largest) octave.
     * Smaller = broader hills. Larger = tighter, more frequent hills.
     */
    private static final double BASE_FREQUENCY = 0.006;

    /**
     * How much the frequency multiplies each octave.
     * 2.0 = each octave has twice the detail of the previous.
     */
    private static final double LACUNARITY = 2.0;

    /**
     * How much the amplitude shrinks each octave.
     * 0.5 = each octave contributes half as much height as the previous.
     */
    private static final double PERSISTENCE = 0.5;

    private final long seed;

    /**
     * Creates a TerrainGenerator with the given world seed.
     *
     * @param seed the world seed — same seed always produces identical terrain
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

                int worldX = pos.worldX() + x;
                int worldZ = pos.worldZ() + z;

                double noiseVal = fbm(worldX, worldZ);

                // noiseVal is in roughly [-1, 1] — remap to [0, 1] then scale to height range
                int surfaceHeight = BASE_HEIGHT + (int) ((noiseVal + 1.0) / 2.0 * HEIGHT_VARIATION);

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

    /**
     * Samples fractional Brownian motion at the given world-space XZ coordinate.
     *
     * <p>Stacks {@value #OCTAVES} octaves of simplex noise, each at double the
     * frequency and half the amplitude of the previous. The result is a smooth
     * value in approximately [-1, 1].
     *
     * <p>Each octave uses a different seed offset so they don't constructively
     * interfere at the origin and produce the same pattern at different scales.
     *
     * @param worldX world-space X coordinate
     * @param worldZ world-space Z coordinate
     * @return summed noise value, approximately in [-1, 1]
     */
    private double fbm(int worldX, int worldZ) {
        double value     = 0.0;
        double amplitude = 1.0;
        double frequency = BASE_FREQUENCY;
        double maxValue  = 0.0; // used to normalize the result back to [-1, 1]

        for (int i = 0; i < OCTAVES; i++) {
            // Offset the seed per octave — prevents all octaves from aligning at origin
            long octaveSeed = seed + i * 31337L;

            value    += OpenSimplex2S.noise2(octaveSeed, worldX * frequency, worldZ * frequency) * amplitude;
            maxValue += amplitude;

            amplitude *= PERSISTENCE;
            frequency *= LACUNARITY;
        }

        // Normalize: divide by the sum of all amplitudes so result stays in [-1, 1]
        return value / maxValue;
    }
}