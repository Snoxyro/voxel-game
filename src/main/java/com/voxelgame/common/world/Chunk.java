package com.voxelgame.common.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

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
     * Byte length of the serialised chunk. 
     * 8192 bytes for blocks (VOLUME * Short.BYTES) + 4096 bytes for light (VOLUME) = 12288 bytes.
     */
    public static final int SERIALIZED_SIZE = (VOLUME * Short.BYTES) + VOLUME;

    /** Maximum value for either light channel (skylight or block light). */
    public static final int MAX_LIGHT = 15;

    /**
     * Flat block storage. Each short is the {@link BlockRegistry} ID of the block
     * at that position. {@link Blocks#AIR} has ID 0, which matches Java's default
     * zero-initialisation of short arrays — no explicit fill needed.
     */
    private final short[] blocks;

    /**
     * Per-block light storage. Each byte packs two 4-bit channels:
     * <pre>
     *   bits 7–4 (high nibble): sky light  (0–15) — how much sunlight reaches this block
     *   bits 3–0 (low  nibble): block light (0–15) — light emitted by or spreading from blocks
     * </pre>
     * Zero-initialised → all positions start with sky=0, block=0.
     * Same flat index formula as {@code blocks}: {@code x*SIZE*SIZE + y*SIZE + z}.
     *
     * <p>Sky level 15 = direct sunlight. Decreases by 1 per opaque block downward.
     * Block level 15 = a light-emitting block (torch, lava). Decreases by 1 per block
     * outward via BFS. Column-only skylight is used for 6C; full BFS in Phase 8+.
     *
     * <p>Memory cost: 4 096 bytes per chunk. At render distance 16 with ~4 000 chunks
     * loaded: ~16 MB total. Negligible.
     */
    private final byte[] lightData;

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
        this.lightData = new byte[VOLUME];
        // Zero-initialised: all sky=0, block=0.
        // TerrainGenerator fills sky light immediately after block generation.
    }

    // -------------------------------------------------------------------------
    // World-coordinate conversion utilities
    // -------------------------------------------------------------------------

    public static int worldToChunk(int worldCoord) {
        return Math.floorDiv(worldCoord, SIZE);
    }

    public static int worldToLocal(int worldCoord) {
        return Math.floorMod(worldCoord, SIZE);
    }

    public static ChunkPos worldToChunkPos(int wx, int wy, int wz) {
        return new ChunkPos(
            worldToChunk(wx),
            worldToChunk(wy),
            worldToChunk(wz)
        );
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

    // -------------------------------------------------------------------------
    // Light data API
    // -------------------------------------------------------------------------
    //
    // Light levels use two packed 4-bit nibbles per block (one byte total).
    // All get/set methods use the same flat index as the block array.
    //
    // Bit layout of lightData[idx]:
    //   bits 7–4: sky light  (>>> 4 to read, << 4 to write)
    //   bits 3–0: block light (& 0xF to read, | value to write)
    //
    // Why nibbles?  Two channels × 4 bits = 1 byte per block = 4 096 bytes per
    // chunk. At render distance 16 this is ~16 MB across all loaded chunks.
    // 16 levels with a gamma curve (pow(0.8, 15-level)) is perceptually smooth
    // — additional precision buys nothing visible and would double the storage.
    // -------------------------------------------------------------------------

    /**
     * Returns the sky light level at the given local position (0–15).
     * 15 = direct sunlight, 0 = no sunlight (underground or fully occluded).
     *
     * @param x local X [0, SIZE)
     * @param y local Y [0, SIZE)
     * @param z local Z [0, SIZE)
     * @return sky light level in [0, 15]
     */
    public int getSkyLight(int x, int y, int z) {
        // Unsigned right-shift so the sign bit of the byte does not contaminate
        // the result — Java bytes are signed, so a high nibble of 0xF0 would read
        // as -1 without the mask.
        return (lightData[x * SIZE * SIZE + y * SIZE + z] >>> 4) & 0xF;
    }

    /**
     * Returns the block light level at the given local position (0–15).
     * 15 = maximum emission (torch center), 0 = no block light.
     *
     * @param x local X [0, SIZE)
     * @param y local Y [0, SIZE)
     * @param z local Z [0, SIZE)
     * @return block light level in [0, 15]
     */
    public int getBlockLight(int x, int y, int z) {
        return lightData[x * SIZE * SIZE + y * SIZE + z] & 0xF;
    }

    /**
     * Sets the sky light level at the given local position.
     * Preserves the block light nibble in the same byte.
     *
     * @param x     local X [0, SIZE)
     * @param y     local Y [0, SIZE)
     * @param z     local Z [0, SIZE)
     * @param level sky light level in [0, 15]
     */
    public void setSkyLight(int x, int y, int z, int level) {
        int idx = x * SIZE * SIZE + y * SIZE + z;
        // Write the high nibble, preserve the low nibble (block light).
        lightData[idx] = (byte) ((level << 4) | (lightData[idx] & 0x0F));
    }

    /**
     * Sets the block light level at the given local position.
     * Preserves the sky light nibble in the same byte.
     *
     * @param x     local X [0, SIZE)
     * @param y     local Y [0, SIZE)
     * @param z     local Z [0, SIZE)
     * @param level block light level in [0, 15]
     */
    public void setBlockLight(int x, int y, int z, int level) {
        int idx = x * SIZE * SIZE + y * SIZE + z;
        // Write the low nibble, preserve the high nibble (sky light).
        lightData[idx] = (byte) ((lightData[idx] & 0xF0) | (level & 0x0F));
    }

    /**
     * Returns the raw packed light byte at the given local position.
     * High nibble = sky light, low nibble = block light.
     * Used by {@link com.voxelgame.game.ChunkMesher} to read both channels
     * in a single array access without two separate method calls.
     *
     * @param x local X [0, SIZE)
     * @param y local Y [0, SIZE)
     * @param z local Z [0, SIZE)
     * @return packed byte: {@code (sky << 4) | block}
     */
    public byte getLightPacked(int x, int y, int z) {
        return lightData[x * SIZE * SIZE + y * SIZE + z];
    }

    /**
     * Fills the sky light channel for one XZ column based on a known surface height.
     * Air blocks whose world-space Y is above {@code surfaceWorldY} receive sky
     * level 15; all others receive 0.
     *
     * <p>Column-only propagation: light travels straight down, not around corners.
     * Overhangs cast no shadow. Full BFS horizontal spreading is deferred to Phase 8+.
     * The data layout (packed byte per block) is BFS-compatible — only the algorithm
     * changes when that upgrade happens.
     *
     * <p>Called by {@link com.voxelgame.game.TerrainGenerator} immediately after
     * block generation for every XZ column in the chunk.
     *
     * @param lx           local X within this chunk [0, SIZE)
     * @param lz           local Z within this chunk [0, SIZE)
     * @param surfaceWorldY world-space Y of the topmost solid block in this column
     * @param chunkWorldY  world-space Y of this chunk's bottom layer (pos.worldY())
     */
    public void fillColumnSkyLight(int lx, int lz, int surfaceWorldY, int chunkWorldY) {
        for (int ly = 0; ly < SIZE; ly++) {
            int worldY = chunkWorldY + ly;
            // Blocks strictly above the surface (air above terrain) are in full sunlight.
            // The surface block itself and everything below are underground — sky = 0.
            setSkyLight(lx, ly, lz, worldY > surfaceWorldY ? 15 : 0);
        }
    }

    /**
     * Sets every position in this chunk to the given sky light level.
     * Used for all-air chunks above the terrain surface (all 15) where every
     * block position is in direct sunlight, without needing a per-column scan.
     *
     * @param level sky light level to write everywhere (0–15)
     */
    public void fillAllSkyLight(int level) {
        // Pack the level into the high nibble. Block light stays 0 (low nibble).
        // A single pass over the flat array is fast and cache-friendly.
        byte packed = (byte) (level << 4);
        for (int i = 0; i < VOLUME; i++) {
            // Preserve any existing block light in the low nibble.
            lightData[i] = (byte) (packed | (lightData[i] & 0x0F));
        }
    }

    /**
     * Recomputes the sky light channel from block data alone, without needing the
     * world heightmap. Each XZ column is scanned top-to-bottom: positions above the
     * first solid block (or all positions if the column is all-air) receive sky 15.
     *
     * <p>Used on the client side after receiving a {@code ChunkDataPacket}, where
     * the heightmap is unavailable. Less accurate than
     * {@link #fillColumnSkyLight} for chunks whose top layer is exposed sky — this
     * method cannot see solid blocks in the chunk above. Full accuracy requires
     * either network-transmitted sky data (Phase 8+) or per-column exposure flags.
     */
    public void recomputeSkyLightFromBlocks() {
        for (int lx = 0; lx < SIZE; lx++) {
            for (int lz = 0; lz < SIZE; lz++) {
                boolean aboveSurface = true;
                // Scan downward: track when we first hit a solid block.
                for (int ly = SIZE - 1; ly >= 0; ly--) {
                    if (aboveSurface && blocks[lx * SIZE * SIZE + ly * SIZE + lz] == 0) {
                        // Still in the air column above terrain.
                        setSkyLight(lx, ly, lz, 15);
                    } else {
                        // Hit solid block or already below surface — no more sky light.
                        aboveSurface = false;
                        setSkyLight(lx, ly, lz, 0);
                    }
                }
            }
        }
    }

    /**
     * Returns the effective light level at a position — the maximum of skylight
     * and block light (0–15). This is what the chunk mesher reads when computing
     * vertex brightness: a torch-lit cave wall is bright even if sky can't reach it.
     *
     * @param x local X in [0, SIZE)
     * @param y local Y in [0, SIZE)
     * @param z local Z in [0, SIZE)
     * @return maximum of skylight and block light in [0, 15]
     */
    public int getMaxLight(int x, int y, int z) {
        int packed = lightData[x * SIZE * SIZE + y * SIZE + z] & 0xFF;
        return Math.max(packed >> 4, packed & 0x0F);
    }

    public byte[] toBytes() {
        ByteBuffer buf = ByteBuffer.allocate(SERIALIZED_SIZE).order(ByteOrder.BIG_ENDIAN);
        for (short id : blocks) {
            buf.putShort(id);
        }
        // Append the packed light bytes directly after the block data
        buf.put(getLightBytes());
        return buf.array();
    }

    public static Chunk fromBytes(byte[] data) {
        if (data.length != SERIALIZED_SIZE) {
            throw new IllegalArgumentException(
                "Chunk.fromBytes: expected " + SERIALIZED_SIZE + " bytes, got " + data.length);
        }
        Chunk chunk = new Chunk();
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < VOLUME; i++) {
            int id = buf.getShort() & 0xFFFF;
            if (id != 0) { 
                int x =  i / (SIZE * SIZE);
                int y = (i / SIZE) % SIZE;
                int z =  i % SIZE;
                chunk.setBlock(x, y, z, BlockRegistry.getById(id));
            }
        }
        
        // Read the remaining 4096 bytes into the light arrays
        byte[] lightData = new byte[VOLUME];
        buf.get(lightData);
        chunk.setLightBytes(lightData);
        
        return chunk;
    }

    /**
     * Resets all light data to zero (both skylight and block-light nibbles for every
     * block in the chunk). Called by {@link com.voxelgame.common.world.LightEngine}
     * at the start of each light recomputation to ensure stale values from a previous
     * pass are never carried over after a block placement or break.
     */
    public void clearLight() {
        Arrays.fill(lightData, (byte) 0);
    }

    /**
     * Retrieves the packed light array for network transmission.
     */
    public byte[] getLightBytes() {
        return lightData;
    }

    /**
     * Applies a compressed network byte array directly into the chunk's light storage.
     */
    public void setLightBytes(byte[] newLightData) {
        if (newLightData == null || newLightData.length != VOLUME) return;
        System.arraycopy(newLightData, 0, this.lightData, 0, VOLUME);
    }
}