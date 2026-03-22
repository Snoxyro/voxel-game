package com.voxelgame.game;

import com.voxelgame.util.OpenSimplex2S;

/**
 * Generates terrain for a chunk using fractional Brownian motion (fBm) —
 * multiple octaves of OpenSimplex2 noise layered at increasing frequencies
 * and decreasing amplitudes.
 *
 * <h3>Chunk-level early exits</h3>
 * Before sampling any noise, the chunk's world-space Y range is compared against
 * the theoretical surface height bounds [BASE_HEIGHT, BASE_HEIGHT + HEIGHT_VARIATION].
 * <ul>
 *   <li>Chunks entirely above the maximum possible surface → returned as all-air
 *       immediately with no noise sampling.</li>
 *   <li>Chunks entirely below the minimum possible surface → returned as all-stone
 *       immediately with no noise sampling.</li>
 * </ul>
 * At render distance 16 with 12 vertical chunk levels, the majority of chunks fall
 * into one of these two cases. Only the thin band of chunks that intersect the
 * actual surface require per-column noise evaluation.
 */
public class TerrainGenerator {

    /** The lowest possible surface height in blocks. */
    private static final int BASE_HEIGHT = 8;

    /**
     * The maximum total height variation across all octaves combined.
     * Actual surface range: [BASE_HEIGHT, BASE_HEIGHT + HEIGHT_VARIATION].
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

    /**
     * Minimum possible surface Y. Any chunk whose top face is below this
     * is guaranteed to be entirely stone — no noise needed.
     */
    private static final int MIN_SURFACE_Y = BASE_HEIGHT;

    /**
     * Maximum possible surface Y. Any chunk whose bottom face is above this
     * is guaranteed to be entirely air — no noise needed.
     */
    private static final int MAX_SURFACE_Y = BASE_HEIGHT + HEIGHT_VARIATION;

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
     *
     * <p>Applies chunk-level early exits before any noise sampling:
     * chunks entirely above the max surface are returned as air,
     * chunks entirely below the min surface are returned as stone.
     * Only chunks that intersect the surface band require per-column noise.
     *
     * @param pos the chunk's 3D grid coordinate
     * @return a new Chunk with terrain blocks filled in for this Y slice
     */
    public Chunk generateChunk(ChunkPos pos) {
        int chunkWorldY    = pos.worldY();
        int chunkWorldYTop = chunkWorldY + Chunk.SIZE - 1;

        // Early exit: chunk is entirely above the highest possible surface
        if (chunkWorldY > MAX_SURFACE_Y) {
            return new Chunk(); // all air by default
        }

        // Early exit: chunk is entirely below the lowest possible surface.
        // Fill completely with stone — no noise needed.
        if (chunkWorldYTop < MIN_SURFACE_Y) {
            Chunk chunk = new Chunk();
            for (int x = 0; x < Chunk.SIZE; x++)
                for (int y = 0; y < Chunk.SIZE; y++)
                    for (int z = 0; z < Chunk.SIZE; z++)
                        chunk.setBlock(x, y, z, Block.STONE);
            return chunk;
        }

        // This chunk intersects the surface band — evaluate noise per column
        Chunk chunk = new Chunk();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {

                int worldX = pos.worldX() + x;
                int worldZ = pos.worldZ() + z;

                double noiseVal   = fbm(worldX, worldZ);
                int surfaceHeight = BASE_HEIGHT + (int) ((noiseVal + 1.0) / 2.0 * HEIGHT_VARIATION);

                // Early exit: entire column in this chunk is above surface
                if (chunkWorldY > surfaceHeight) continue;

                for (int y = 0; y < Chunk.SIZE; y++) {
                    int worldY = chunkWorldY + y;

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