package com.voxelgame.game;

import com.voxelgame.util.OpenSimplex2S;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates terrain for a chunk using fractional Brownian motion (fBm) —
 * multiple octaves of OpenSimplex2 noise layered at increasing frequencies
 * and decreasing amplitudes.
 *
 * <h3>Heightmap column cache</h3>
 * For a given chunk column (cx, cz), every Y slice shares the same 256 surface
 * height values. Without a cache, each Y chunk re-evaluates all 256 fBm columns
 * independently — up to 12× redundant work per column at full render distance.
 * The cache stores one {@code int[SIZE*SIZE]} heightmap per (cx, cz) pair so
 * fBm is evaluated at most once per column regardless of how many Y chunks are
 * generated in it.
 *
 * <p>The cache is a {@link ConcurrentHashMap} because multiple worker threads
 * may generate different Y slices of the same column concurrently.
 * {@link ConcurrentHashMap#computeIfAbsent} is atomic — only one thread computes
 * the heightmap even if several arrive simultaneously. Call
 * {@link #evictColumn(int, int)} when a chunk column is fully unloaded to prevent
 * unbounded memory growth.
 *
 * <h3>Chunk-level early exits</h3>
 * Before touching the cache or sampling noise, the chunk's world-space Y range is
 * compared against [MIN_SURFACE_Y, MAX_SURFACE_Y]:
 * <ul>
 *   <li>Chunks entirely above the maximum possible surface → returned as all-air.</li>
 *   <li>Chunks entirely below the minimum possible surface → returned as all-stone.</li>
 * </ul>
 * Only chunks intersecting the surface band reach the cache or noise evaluation.
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

    /** Minimum possible surface Y — chunks below this are guaranteed all-stone. */
    private static final int MIN_SURFACE_Y = BASE_HEIGHT;

    /** Maximum possible surface Y — chunks above this are guaranteed all-air. */
    private static final int MAX_SURFACE_Y = BASE_HEIGHT + HEIGHT_VARIATION;

    /**
     * Heightmap cache — maps packed column key → int[SIZE*SIZE] of surface heights.
     * One entry per (cx, cz) column. Shared across all worker threads; access is
     * safe via ConcurrentHashMap's atomic computeIfAbsent.
     */
    private final ConcurrentHashMap<Long, int[]> heightmapCache = new ConcurrentHashMap<>();

    // --- Pre-computed per-octave constants ---
    // Computed once at construction instead of recalculated inside every fbm() call.
    // fbm() is invoked for every XZ column of every surface-band chunk — these arrays
    // eliminate the repeated arithmetic across potentially millions of calls per session.

    private final long[]   octaveSeeds;
    private final double[] octaveAmplitudes;
    private final double[] octaveFrequencies;

    /**
     * Sum of all octave amplitudes — the normalisation divisor for fBm output.
     * Pre-computed so fbm() does not recalculate it on every call.
     */
    private final double maxAmplitude;

    /**
     * Creates a TerrainGenerator with the given world seed.
     *
     * @param seed the world seed — same seed always produces identical terrain
     */
    public TerrainGenerator(long seed) {
        octaveSeeds       = new long[OCTAVES];
        octaveAmplitudes  = new double[OCTAVES];
        octaveFrequencies = new double[OCTAVES];

        double amplitude = 1.0;
        double frequency = BASE_FREQUENCY;
        double ampSum    = 0.0;

        for (int i = 0; i < OCTAVES; i++) {
            octaveSeeds[i]       = seed + i * 31337L;
            octaveAmplitudes[i]  = amplitude;
            octaveFrequencies[i] = frequency;
            ampSum    += amplitude;
            amplitude *= PERSISTENCE;
            frequency *= LACUNARITY;
        }

        this.maxAmplitude = ampSum;
    }

    /**
     * Generates and returns a chunk at the given 3D grid position.
     *
     * <p>Applies chunk-level early exits before any cache or noise work.
     * Surface-band chunks retrieve (or compute) a cached heightmap for their
     * column, then fill only the Y slice that falls within this chunk's range.
     *
     * @param pos the chunk's 3D grid coordinate
     * @return a new Chunk with terrain blocks filled in
     */
    public Chunk generateChunk(ChunkPos pos) {
        int chunkWorldY    = pos.worldY();
        int chunkWorldYTop = chunkWorldY + Chunk.SIZE - 1;

        // Early exit: chunk is entirely above the highest possible surface
        if (chunkWorldY > MAX_SURFACE_Y) {
            return new Chunk(); // all air by default
        }

        // Early exit: chunk is entirely below the lowest possible surface
        if (chunkWorldYTop < MIN_SURFACE_Y) {
            Chunk chunk = new Chunk();
            for (int x = 0; x < Chunk.SIZE; x++)
                for (int y = 0; y < Chunk.SIZE; y++)
                    for (int z = 0; z < Chunk.SIZE; z++)
                        chunk.setBlock(x, y, z, Block.STONE);
            return chunk;
        }

        // Retrieve or compute the heightmap for this column.
        // computeIfAbsent is atomic — safe for concurrent workers on the same column.
        long columnKey = packColumnKey(pos.x(), pos.z());
        int[] heightmap = heightmapCache.computeIfAbsent(columnKey, k -> buildHeightmap(pos.x(), pos.z()));

        // Fill only the Y slice belonging to this chunk
        Chunk chunk = new Chunk();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                int surfaceHeight = heightmap[x * Chunk.SIZE + z];

                // Entire column in this chunk is above surface — nothing to fill
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
     * Removes the cached heightmap for the given chunk column.
     * Call this when all Y chunks in the column have been unloaded to prevent
     * the cache from growing unbounded as the player moves through the world.
     *
     * @param cx chunk-space X of the column
     * @param cz chunk-space Z of the column
     */
    public void evictColumn(int cx, int cz) {
        heightmapCache.remove(packColumnKey(cx, cz));
    }

    /**
     * Computes the full 256-value heightmap for a chunk column.
     * Called at most once per (cx, cz) column — subsequent Y chunks in the
     * same column retrieve the cached result.
     *
     * @param cx chunk-space X
     * @param cz chunk-space Z
     * @return int[SIZE*SIZE] of surface heights in world-space Y, indexed [x*SIZE+z]
     */
    private int[] buildHeightmap(int cx, int cz) {
        int[] heightmap = new int[Chunk.SIZE * Chunk.SIZE];
        int worldOriginX = cx * Chunk.SIZE;
        int worldOriginZ = cz * Chunk.SIZE;

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                double noiseVal = fbm(worldOriginX + x, worldOriginZ + z);
                heightmap[x * Chunk.SIZE + z] =
                    BASE_HEIGHT + (int) ((noiseVal + 1.0) / 2.0 * HEIGHT_VARIATION);
            }
        }
        return heightmap;
    }

    /**
     * Packs a (cx, cz) column coordinate pair into a single long for use as a
     * map key. Avoids allocating a key object per cache lookup.
     *
     * @param cx chunk-space X
     * @param cz chunk-space Z
     * @return packed long key
     */
    private static long packColumnKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * Samples fractional Brownian motion at the given world-space XZ coordinate.
     * Reads pre-computed per-octave arrays instead of recalculating seeds,
     * amplitudes, and frequencies on each call.
     *
     * @param worldX world-space X
     * @param worldZ world-space Z
     * @return normalised noise value in approximately [-1, 1]
     */
    private double fbm(int worldX, int worldZ) {
        double value = 0.0;
        for (int i = 0; i < OCTAVES; i++) {
            value += OpenSimplex2S.noise2(octaveSeeds[i],
                         worldX * octaveFrequencies[i],
                         worldZ * octaveFrequencies[i])
                     * octaveAmplitudes[i];
        }
        return value / maxAmplitude;
    }
}