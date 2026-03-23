package com.voxelgame.server.storage;

import com.voxelgame.common.world.ChunkPos;
import com.voxelgame.game.ChunkStorage;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stores one chunk per file inside {@code <worldDir>/chunks/}.
 *
 * <h3>File naming</h3>
 * Each chunk is stored as {@code <cx>_<cy>_<cz>.dat}. Negative coordinates produce
 * names like {@code -3_4_-1.dat}, which are valid filenames on all major OSes.
 *
 * <h3>Format</h3>
 * Each {@code .dat} file is the raw 4096-byte block array (from
 * {@link com.voxelgame.common.world.Chunk#toBytes()}) wrapped in GZIP compression.
 * GZIP typically shrinks an all-air chunk to ~30 bytes and a dense surface chunk
 * to ~500 bytes.
 *
 * <h3>Thread safety</h3>
 * {@link #save} and {@link #load} are both stateless beyond the {@code chunksDir}
 * path, which is set once at construction and never changes. Both methods are safe
 * to call from any thread — they only use local variables and standard I/O.
 *
 * <h3>Migration path</h3>
 * When region files become desirable (thousands of chunks, filesystem inode pressure),
 * implement a {@code RegionFileChunkStorage} that also implements {@link ChunkStorage}.
 * Swap it in {@link com.voxelgame.server.GameServer}. Nothing else changes.
 */
public class FlatFileChunkStorage implements ChunkStorage {

    /** Subdirectory within the world folder where chunk files are written. */
    private static final String CHUNKS_SUBDIR = "chunks";

    /** File extension for all chunk data files. */
    private static final String EXTENSION = ".dat";

    /** Resolved path to the chunks subdirectory. All reads/writes go here. */
    private final Path chunksDir;

    /**
     * Constructs a storage backed by the given world directory.
     * Creates {@code <worldDir>/chunks/} on disk if it does not already exist.
     *
     * @param worldDir path to the root world folder (e.g. {@code Path.of("worlds/default")})
     * @throws UncheckedIOException if the directory cannot be created
     */
    public FlatFileChunkStorage(Path worldDir) {
        this.chunksDir = worldDir.resolve(CHUNKS_SUBDIR);
        try {
            Files.createDirectories(chunksDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create chunk directory: " + chunksDir, e);
        }
    }

    // -------------------------------------------------------------------------
    // ChunkStorage implementation
    // -------------------------------------------------------------------------

    /**
     * Writes the chunk's block bytes to {@code <cx>_<cy>_<cz>.dat}, GZIP-compressed.
     * Overwrites any existing file for this position.
     *
     * <p>Called from a background save executor in {@link com.voxelgame.game.World}.
     * The byte array is a defensive copy ({@code Chunk.toBytes()}) so this method
     * never races with the tick thread mutating the chunk.
     */
    @Override
    public void save(ChunkPos pos, byte[] data) {
        Path file = fileFor(pos);
        try (OutputStream raw = Files.newOutputStream(file);
             GZIPOutputStream gzip = new GZIPOutputStream(raw)) {
            gzip.write(data);
        } catch (IOException e) {
            // Log and continue — a failed save is not fatal. The chunk will be
            // re-marked dirty on the next mutation and retried, or regenerated on
            // the next server start if the save never succeeds.
            System.err.printf("[Storage] Failed to save chunk %s: %s%n", pos, e.getMessage());
        }
    }

    /**
     * Reads and decompresses a chunk file. Returns {@code null} if the chunk has
     * never been saved (file does not exist).
     *
     * <p>Called on the generation executor thread in {@link com.voxelgame.game.World},
     * so disk I/O never blocks the server tick thread.
     */
    @Override
    public byte[] load(ChunkPos pos) {
        Path file = fileFor(pos);
        if (!Files.exists(file)) return null;

        try (InputStream raw = Files.newInputStream(file);
             GZIPInputStream gzip = new GZIPInputStream(raw)) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            System.err.printf("[Storage] Failed to load chunk %s: %s%n", pos, e.getMessage());
            return null; // Fall back to terrain generation
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Derives the file path for a given chunk position.
     * Example: ChunkPos(3, 4, -1) → {@code chunks/3_4_-1.dat}
     */
    private Path fileFor(ChunkPos pos) {
        return chunksDir.resolve(pos.x() + "_" + pos.y() + "_" + pos.z() + EXTENSION);
    }
}