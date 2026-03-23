package com.voxelgame.common.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a fixed-size 3D chunk of blocks.
 *
 * <p>Blocks are stored as a flat {@code short[]} of registry IDs — a single
 * contiguous 8 KB allocation. Registry ID 0 = {@link Blocks#AIR}, which matches
 * Java's default zero-initialisation of short arrays — no explicit fill needed.
 * This is an architectural contract: {@link Blocks} guarantees AIR is always
 * registered first and always receives ID 0.
 *
 * <p>Index formula: {@code x * SIZE * SIZE + y * SIZE + z}.
 * Y is the innermost stride so that filling or reading a full column in Y order
 * (as TerrainGenerator and ChunkMesher both do) accesses sequential memory.
 *
 * <p>A solid block count and a Y occupancy range are maintained on every
 * {@link #setBlock} call. The mesher uses these to skip empty chunks entirely
 * and to clamp its layer loops to the occupied Y band.
 *
 * <p>A chunk stores {@value #SIZE} × {@value #SIZE} × {@value #SIZE} blocks and
 * initialises all positions to {@link Blocks#AIR} (ID 0).
 *
 * <h3>Serialization format</h3>
 * {@link #toBytes()} / {@link #fromBytes(byte[])} produce/consume {@link #SERIALIZED_SIZE}
 * bytes: each block is stored as a big-endian unsigned short (2 bytes) containing its
 * registry ID. This format is used for both network transmission and disk persistence.
 * Save files written by an earlier format (4096 bytes) are incompatible and must be
 * deleted before upgrading.
 */
public class Chunk {

    /** The edge length of this chunk in blocks. */
    public static final int SIZE = 16;

    /** Total number of blocks in a chunk (SIZE³). */
    private static final int VOLUME = SIZE * SIZE * SIZE;

    /**
     * Byte length of the serialised chunk. Each block is 2 bytes (big-endian short
     * registry ID), so this is {@link #VOLUME} × 2 = 8192.
     * Used by {@link #toBytes()}, {@link #fromBytes(byte[])}, and network packets.
     */
    public static final int SERIALIZED_SIZE = VOLUME * Short.BYTES; // 8192

    /**
     * Flat block storage. Each short is the {@link BlockRegistry} ID of the block
     * at that position. {@link Blocks#AIR} has ID 0, which matches Java's default
     * zero-initialisation of short arrays — no explicit fill needed.
     */
    private final short[] blocks;

    /**
     * Number of non-air blocks currently in this chunk.
     * Used by {@link #isAllAir()} to give the mesher an O(1) early-exit check.
     */
    private int solidCount = 0;

    /**
     * Y range of occupied (non-air) blocks. The mesher clamps its layer loops
     * to [{@code minOccupiedY}, {@code maxOccupiedY}] to skip guaranteed-empty
     * layers above and below the actual content.
     *
     * <p>These bounds only expand on block placement, never shrink on removal.
     * Sentinels when empty: {@code minOccupiedY = SIZE}, {@code maxOccupiedY = -1}.
     * Only read these when {@link #isAllAir()} is false.
     */
    private int minOccupiedY = SIZE;
    private int maxOccupiedY = -1;

    /**
     * Creates a new chunk with all block positions initialised to {@link Blocks#AIR} (ID 0).
     */
    public Chunk() {
        this.blocks = new short[VOLUME];
        // Java zero-initialises arrays — all 0s = all AIR IDs. No fill needed.
    }

    /**
     * Gets the block at the given local chunk coordinates.
     *
     * @param x local X [0, SIZE)
     * @param y local Y [0, SIZE)
     * @param z local Z [0, SIZE)
     * @return the registered block type at that position, never null
     */
    public BlockType getBlock(int x, int y, int z) {
        // & 0xFFFF converts signed short to unsigned int for the registry lookup.
        return BlockRegistry.getById(blocks[x * SIZE * SIZE + y * SIZE + z] & 0xFFFF);
    }

    /**
     * Sets the block at the given local chunk coordinates.
     * Maintains {@code solidCount} and Y occupancy bounds.
     *
     * @param x    local X [0, SIZE)
     * @param y    local Y [0, SIZE)
     * @param z    local Z [0, SIZE)
     * @param type the block type to place
     */
    public void setBlock(int x, int y, int z, BlockType type) {
        int idx = x * SIZE * SIZE + y * SIZE + z;
        boolean wasAir = (blocks[idx] == 0); // ID 0 = AIR
        boolean isAir  = (type.getId() == 0);

        if (wasAir && !isAir) solidCount++;
        if (!wasAir && isAir) solidCount--;

        blocks[idx] = (short) type.getId();

        if (!isAir) {
            if (y < minOccupiedY) minOccupiedY = y;
            if (y > maxOccupiedY) maxOccupiedY = y;
        }
    }

    /**
     * Returns {@code true} if every block in this chunk is air.
     * O(1) — uses the maintained {@code solidCount}.
     *
     * @return {@code true} if all air
     */
    public boolean isAllAir() { return solidCount == 0; }

    /** Returns the lowest Y layer that contains a non-air block. Sentinel = SIZE when empty. */
    public int getMinOccupiedY() { return minOccupiedY; }

    /** Returns the highest Y layer that contains a non-air block. Sentinel = -1 when empty. */
    public int getMaxOccupiedY() { return maxOccupiedY; }

    /**
     * Serialises this chunk's block array to a {@code byte[]} of exactly
     * {@link #SERIALIZED_SIZE} bytes.
     *
     * <p>Each block is stored as a big-endian unsigned short (2 bytes) containing
     * its registry ID. Format: {@code [id0_high][id0_low][id1_high][id1_low]...}
     *
     * <p>Used for both network transmission ({@code ChunkDataPacket}) and disk
     * persistence ({@code FlatFileChunkStorage}). The two share the same format
     * intentionally — received chunk data can be written to disk with no conversion.
     *
     * @return 8192-byte serialised block array
     */
    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(SERIALIZED_SIZE).order(ByteOrder.BIG_ENDIAN);
        for (short id : blocks) {
            buf.putShort(id);
        }
        return buf.array();
    }

    /**
     * Deserialises a chunk from a {@code byte[]} produced by {@link #toBytes()}.
     * Rebuilds {@code solidCount} and Y occupancy bounds from scratch.
     *
     * @param data byte array of exactly {@link #SERIALIZED_SIZE} bytes
     * @return the reconstructed chunk
     * @throws IllegalArgumentException if {@code data.length != SERIALIZED_SIZE}
     */
    public static Chunk fromBytes(byte[] data) {
        if (data.length != SERIALIZED_SIZE) {
            throw new IllegalArgumentException(
                "Chunk.fromBytes: expected " + SERIALIZED_SIZE + " bytes, got " + data.length);
        }
        Chunk chunk = new Chunk();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < VOLUME; i++) {
            // readShort() reads signed; & 0xFFFF gives unsigned ID value
            int id = buf.getShort() & 0xFFFF;
            if (id != 0) { // skip AIR (id 0) — array is already all zeros
                // Reconstruct x, y, z from flat index i = x*SIZE*SIZE + y*SIZE + z
                int x =  i / (SIZE * SIZE);
                int y = (i / SIZE) % SIZE;
                int z =  i % SIZE;
                chunk.setBlock(x, y, z, BlockRegistry.getById(id));
            }
        }
        return chunk;
    }
}