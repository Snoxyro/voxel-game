package com.voxelgame.server.storage;

import java.io.*;
import java.nio.file.*;

/**
 * Reads and writes the {@code world.dat} metadata file for a world directory.
 *
 * <h3>Format</h3>
 * Currently just 8 bytes — the world seed as a big-endian {@code long}.
 * Future fields (spawn point, world name, game rules) can be appended without
 * breaking existing files — reads are length-guarded.
 *
 * <h3>Usage</h3>
 * Call {@link #loadOrCreate(Path)} once in {@link com.voxelgame.server.GameServer}.
 * It handles all four cases: new world, existing world, missing file, and corrupt file.
 */
public class WorldMeta {

    /** Filename within the world directory. */
    private static final String FILENAME = "world.dat";

    /** The world seed read from or written to disk. */
    private final long seed;

    private WorldMeta(long seed) {
        this.seed = seed;
    }

    /**
     * Returns the world seed.
     *
     * @return the seed used for terrain generation
     */
    public long getSeed() {
        return seed;
    }

    // -------------------------------------------------------------------------
    // Factory — the only way to construct a WorldMeta
    // -------------------------------------------------------------------------

    /**
     * Loads world metadata from {@code <worldDir>/world.dat} if it exists, or
     * creates a new file with a random seed if it does not.
     *
     * <p>If the file exists but is unreadable or corrupt, a new random seed is
     * generated, the bad file is overwritten, and a warning is printed. This
     * means an already-generated world survives — saved chunks load from disk
     * regardless of seed, so only newly generated areas use the fresh seed.
     *
     * @param worldDir path to the world root directory — created if absent
     * @return a {@code WorldMeta} containing the seed for this world
     */
    public static WorldMeta loadOrCreate(Path worldDir) {
        try {
            Files.createDirectories(worldDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create world directory: " + worldDir, e);
        }

        Path file = worldDir.resolve(FILENAME);

        if (Files.exists(file)) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(file)))) {
                long seed = in.readLong();
                System.out.printf("[World] Loaded seed %d from %s%n", seed, file);
                return new WorldMeta(seed);
            } catch (IOException e) {
                // File is corrupt or truncated — overwrite with a fresh seed.
                System.err.printf("[World] world.dat corrupt (%s) — generating new seed%n",
                    e.getMessage());
            }
        }

        // No file, or corrupt file — generate a random seed and write it.
        long seed = new java.util.Random().nextLong();
        write(file, seed);
        System.out.printf("[World] New world — seed %d written to %s%n", seed, file);
        return new WorldMeta(seed);
    }

    /**
     * Loads the seed from {@code world.dat} if the file exists, otherwise creates
     * a new {@code world.dat} using {@code forcedSeed} instead of a random value.
     *
     * <p>Use this when the player has explicitly entered a seed at world-creation
     * time. Has no effect on worlds that already have a {@code world.dat}.</p>
     *
     * @param worldDir   path to the world folder
     * @param forcedSeed seed to write for new worlds; ignored for existing worlds
     */
    public static WorldMeta loadOrCreate(Path worldDir, long forcedSeed) {
        // If world.dat already exists, ignore forcedSeed — existing world owns its seed
        Path metaFile = worldDir.resolve("world.dat");
        if (Files.exists(metaFile)) {
            return loadOrCreate(worldDir); // delegate to existing logic
        }
        // New world — use the provided seed instead of random
        try {
            Files.createDirectories(worldDir);
            try (DataOutputStream out = new DataOutputStream(
                    Files.newOutputStream(metaFile))) {
                out.writeLong(forcedSeed);
            }
            System.out.printf("[WorldMeta] Created world.dat with seed %d in %s%n",
                forcedSeed, worldDir);
            return new WorldMeta(forcedSeed);
        } catch (IOException e) {
            System.err.println("[WorldMeta] Failed to create world.dat: " + e.getMessage());
            return new WorldMeta(forcedSeed); // still usable even if file write failed
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Writes a seed to the given path, overwriting any existing file.
     */
    private static void write(Path file, long seed) {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeLong(seed);
        } catch (IOException e) {
            // Not fatal — the world still runs, just without a persisted seed.
            System.err.printf("[World] Failed to write world.dat: %s%n", e.getMessage());
        }
    }
}