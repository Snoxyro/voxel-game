package com.voxelgame.game;

import com.voxelgame.common.world.ChunkPos;

/**
 * Abstraction over chunk persistence. The only two operations a chunk store needs
 * to support are saving a chunk's raw bytes and loading them back.
 *
 * <h3>Why bytes, not Chunk?</h3>
 * Working with {@code byte[]} keeps this interface free of game-type dependencies,
 * makes implementations trivially testable, and matches {@link com.voxelgame.common.world.Chunk#toBytes()}
 * / {@link com.voxelgame.common.world.Chunk#fromBytes(byte[])} which already exist
 * for network serialization.
 *
 * <h3>Implementations</h3>
 * <ul>
 *   <li>{@link com.voxelgame.server.storage.FlatFileChunkStorage} — one GZIP file
 *       per chunk. Simple, correct, and sufficient for Phase 5E.</li>
 *   <li>Future: a region-file implementation that packs many chunks into a single
 *       file using a header offset table. Drop-in replacement — nothing outside this
 *       interface changes.</li>
 * </ul>
 */
public interface ChunkStorage {

    /**
     * Persists a chunk's block data.
     *
     * @param pos  the chunk grid position — used to derive the storage key/path
     * @param data exactly {@link com.voxelgame.common.world.Chunk#VOLUME} bytes
     *             as returned by {@link com.voxelgame.common.world.Chunk#toBytes()}
     */
    void save(ChunkPos pos, byte[] data);

    /**
     * Retrieves a previously saved chunk's block data.
     *
     * @param pos the chunk grid position
     * @return the raw bytes if found, or {@code null} if the chunk was never saved
     */
    byte[] load(ChunkPos pos);
}