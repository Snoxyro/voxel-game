package com.voxelgame.common.world;

import java.util.Arrays;

/**
 * Represents a fixed-size 3D chunk of blocks.
 *
 * <p>Blocks are stored as a flat {@code byte[]} of ordinal values — a single
 * contiguous 4 KB allocation that is significantly more cache-friendly than a
 * {@code Block[][][]} of scattered enum references.
 *
 * <p>Index formula: {@code x * SIZE * SIZE + y * SIZE + z}.
 * Y is the innermost stride so that filling or reading a full column in Y order
 * (as TerrainGenerator and ChunkMesher both do) accesses sequential memory.
 *
 * <p>A solid block count and a Y occupancy range are maintained on every
 * {@link #setBlock} call. The mesher uses these to skip empty chunks entirely
 * and to clamp its layer loops to the occupied Y band, avoiding iteration over
 * guaranteed-empty layers.
 *
 * <p>A chunk stores {@value #SIZE} × {@value #SIZE} × {@value #SIZE} blocks and
 * initialises all positions to {@link Block#AIR} (ordinal 0).
 */
public class Chunk {

    /** The edge length of this chunk in blocks. */
    public static final int SIZE = 16;

    /** Total number of blocks in a chunk. */
    private static final int VOLUME = SIZE * SIZE * SIZE;

    /**
     * Flat block storage. Each byte is the {@link Block#ordinal()} of the block
     * at that position. {@link Block#AIR} has ordinal 0, which matches Java's
     * default zero-initialisation of byte arrays — no explicit fill needed.
     */
    private final byte[] blocks;

    /**
     * Cached reference to {@code Block.values()} to avoid repeated calls to
     * the synthetic {@code values()} method during block lookups.
     */
    private static final Block[] BLOCK_VALUES = Block.values();

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
     * After a boundary block is broken the range may be slightly wider than the
     * true occupied range — the mesher scans a few extra empty layers at worst,
     * which is always correct.
     *
     * <p>Sentinels when empty: {@code minOccupiedY = SIZE}, {@code maxOccupiedY = -1}.
     * Only read these when {@link #isAllAir()} is false.
     */
    private int minOccupiedY = SIZE;
    private int maxOccupiedY = -1;

    /**
     * Creates a new chunk with all block positions initialised to {@link Block#AIR}.
     */
    public Chunk() {
        this.blocks = new byte[VOLUME];
    }

    /**
     * Gets the block at the given local chunk coordinates.
     *
     * @param x local X coordinate [0, SIZE)
     * @param y local Y coordinate [0, SIZE)
     * @param z local Z coordinate [0, SIZE)
     * @return {@link Block#AIR} if coordinates are out of bounds; otherwise the
     *         block stored at that position
     */
    public Block getBlock(int x, int y, int z) {
        if (!isInBounds(x, y, z)) return Block.AIR;
        return BLOCK_VALUES[blocks[index(x, y, z)] & 0xFF];
    }

    /**
     * Sets the block at the given local chunk coordinates and updates the solid
     * block count and Y occupancy range accordingly.
     *
     * @param x     local X coordinate [0, SIZE)
     * @param y     local Y coordinate [0, SIZE)
     * @param z     local Z coordinate [0, SIZE)
     * @param block the block type to store
     */
    public void setBlock(int x, int y, int z, Block block) {
        if (!isInBounds(x, y, z)) return;
        int  idx  = index(x, y, z);
        byte prev = blocks[idx];
        byte next = (byte) block.ordinal();
        if (prev == next) return; // no state change — skip bookkeeping

        blocks[idx] = next;

        boolean wasAir = (prev == 0);
        boolean nowAir = (next == 0);

        if (wasAir && !nowAir) {
            // Placed a solid block — expand occupancy range and increment count
            solidCount++;
            if (y < minOccupiedY) minOccupiedY = y;
            if (y > maxOccupiedY) maxOccupiedY = y;
        } else if (!wasAir && nowAir) {
            // Removed a solid block — shrinking the range would require a full
            // scan, so we leave it wide. The mesher will scan a few extra empty
            // layers at worst, which is always correct.
            solidCount--;
        }
        // Non-air to non-air replacement: count unchanged, range already covers y
    }

    /**
     * Returns true if the block at the given local coordinates is air.
     * Skips the BLOCK_VALUES lookup — faster in the mesher's hot path.
     *
     * @param x local X [0, SIZE)
     * @param y local Y [0, SIZE)
     * @param z local Z [0, SIZE)
     * @return {@code true} if out of bounds or the block is {@link Block#AIR}
     */
    public boolean isAir(int x, int y, int z) {
        if (!isInBounds(x, y, z)) return true;
        return blocks[index(x, y, z)] == 0;
    }

    /**
     * Returns true if this chunk contains no solid blocks.
     * The mesher uses this for an O(1) fast-path that skips all six face passes.
     *
     * @return {@code true} if solidCount is zero
     */
    public boolean isAllAir() {
        return solidCount == 0;
    }

    /**
     * Returns the lowest local Y coordinate that contains a non-air block.
     * Only meaningful when {@link #isAllAir()} returns false.
     *
     * @return minimum occupied Y in [0, SIZE)
     */
    public int getMinOccupiedY() { return minOccupiedY; }

    /**
     * Returns the highest local Y coordinate that contains a non-air block.
     * Only meaningful when {@link #isAllAir()} returns false.
     *
     * @return maximum occupied Y in [0, SIZE)
     */
    public int getMaxOccupiedY() { return maxOccupiedY; }

    /**
     * Returns the flat array index for the given local coordinates.
     * Y is the innermost stride — sequential Y increments are sequential memory.
     */
    private static int index(int x, int y, int z) {
        return x * SIZE * SIZE + y * SIZE + z;
    }

    private static boolean isInBounds(int x, int y, int z) {
        return x >= 0 && x < SIZE
            && y >= 0 && y < SIZE
            && z >= 0 && z < SIZE;
    }

    /**
     * Returns a copy of the raw block ordinal array for network transmission and persistence.
     * The format matches {@link #fromBytes}: index = x*SIZE*SIZE + y*SIZE + z, value = ordinal.
     *
     * @return a 4096-byte defensive copy of the internal block array
     */
    public byte[] toBytes() {
        return Arrays.copyOf(blocks, blocks.length);
    }

    /**
     * Reconstructs a Chunk from raw bytes produced by {@link #toBytes()}.
     * AIR blocks (ordinal 0) are skipped — they are the default.
     * Correctly maintains solidCount and Y occupancy range via setBlock.
     *
     * @param data 4096 bytes — must be exactly SIZE³ in length
     * @return a new Chunk with all blocks set
     */
    public static Chunk fromBytes(byte[] data) {
        if (data.length != SIZE * SIZE * SIZE)
            throw new IllegalArgumentException("Invalid chunk data length: " + data.length);
        Chunk chunk = new Chunk();
        for (int i = 0; i < data.length; i++) {
            int ordinal = data[i] & 0xFF;
            if (ordinal == 0) continue; // AIR is the default — skip for speed
            int x = i / (SIZE * SIZE);
            int y = (i % (SIZE * SIZE)) / SIZE;
            int z = i % SIZE;
            chunk.setBlock(x, y, z, BLOCK_VALUES[ordinal]);
        }
        return chunk;
    }
}