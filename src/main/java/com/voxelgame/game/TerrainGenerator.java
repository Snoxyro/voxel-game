package com.voxelgame.game;

import com.voxelgame.util.OpenSimplex2S;

/**
 * Generates terrain for a chunk using fractional Brownian motion (fBm) —
 * multiple octaves of OpenSimplex2 noise layered at increasing frequencies
 * and decreasing amplitudes.
 *
 * <p>Chunks are generated in 3D space. For a given chunk, only blocks whose
 * world-space Y coordinate falls within the chunk's Y range are filled.
 * Chunks fully above the surface are left as air; chunks fully below are
 * filled with STONE.
 */
public class TerrainGenerator {

    /** The lowest possible surface height in blocks. */
    private static final int BASE_HEIGHT = 8;

    /**
     * The maximum total height variation across all octaves combined.
     * The actual range is [BASE_HEIGHT, BASE_HEIGHT + HEIGHT_VARIATION].
     */
    private static final int HEIGHT_VARIATION = 36;

    /** How many DIRT layers sit directly below the GRASS surface. */
    private static final int DIRT_DEPTH = 3;

    // --- fBm parameters ---

    /** Number of noise layers stacked. More = more detail, more cost. */
    private static final int OCTAVES = 4;

    /**
     * Base frequency of the first (largest) octave.
     * Smaller = broader hills. Larger = tighter, more frequent hills.
     */
    private static final double BASE_FREQUENCY = 0.008;

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
     * Generates and returns a chunk at the given 3D grid position.
     * Only blocks within this chunk's world-space Y range are filled.
     *
     * @param pos the chunk's 3D grid coordinate
     * @return a new Chunk with terrain blocks filled in for this Y slice
     */
    public Chunk generateChunk(ChunkPos pos) {
        Chunk chunk = new Chunk();

        // The world-space Y range this chunk is responsible for
        int chunkWorldY      = pos.worldY();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {

                int worldX = pos.worldX() + x;
                int worldZ = pos.worldZ() + z;

                double noiseVal     = fbm(worldX, worldZ);
                int surfaceHeight   = BASE_HEIGHT + (int) ((noiseVal + 1.0) / 2.0 * HEIGHT_VARIATION);

                // Early exit: chunk is entirely above the surface — leave as air
                if (chunkWorldY > surfaceHeight) continue;

                for (int y = 0; y < Chunk.SIZE; y++) {
                    int worldY = chunkWorldY + y;

                    // Above surface — air (default, skip)
                    if (worldY > surfaceHeight) continue;

                    Block block;
                    if (worldY == surfaceHeight) {
                        block = Block.GRASS;
                    } else if (worldY >= surfaceHeight - DIRT_DEPTH) {
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
     * Returns a value in approximately [-1, 1].
     *
     * @param worldX world-space X coordinate
     * @param worldZ world-space Z coordinate
     * @return summed normalized noise value
     */
    private double fbm(int worldX, int worldZ) {
        double value     = 0.0;
        double amplitude = 1.0;
        double frequency = BASE_FREQUENCY;
        double maxValue  = 0.0;

        for (int i = 0; i < OCTAVES; i++) {
            long octaveSeed = seed + i * 31337L;
            value    += OpenSimplex2S.noise2(octaveSeed, worldX * frequency, worldZ * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= PERSISTENCE;
            frequency *= LACUNARITY;
        }

        return value / maxValue;
    }
}