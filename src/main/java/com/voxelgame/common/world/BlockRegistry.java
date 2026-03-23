package com.voxelgame.common.world;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central registry for all block types.
 *
 * <p>Blocks are registered at startup via {@link #register} (called from the
 * {@link Blocks} static initializer) and are never removed or reassigned.
 * IDs are assigned sequentially starting at 0 — the first registered block
 * (always {@link Blocks#AIR}) receives ID 0.
 *
 * <h3>Why ID 0 = AIR matters</h3>
 * {@link Chunk} stores blocks in a {@code short[]} that Java zero-initialises.
 * ID 0 must be AIR so that every newly created chunk is correctly all-air with
 * no explicit fill. This is an architectural contract — {@link Blocks} guarantees
 * it by registering AIR first.
 *
 * <h3>Thread safety</h3>
 * Registration ({@link #register}) is synchronised and expected only at startup
 * before any server or client threads start. Lookups ({@link #getById},
 * {@link #getByName}) are unsynchronised reads on an immutable snapshot — safe
 * after registration is complete.
 */
public final class BlockRegistry {

    /**
     * Lookup table indexed directly by ID for O(1) access.
     * Grows as blocks are registered; never shrinks.
     */
    private static BlockType[] byId   = new BlockType[16];

    /** Secondary lookup by string name. */
    private static final Map<String, BlockType> byName = new LinkedHashMap<>();

    /** Next ID to assign. */
    private static int nextId = 0;

    private BlockRegistry() {} // static-only

    /**
     * Registers a new block type and returns the created instance.
     * Must be called before any server or client threads start.
     *
     * @param name               unique string identifier (e.g. {@code "grass"})
     * @param solid              {@code true} if this block occupies space physically
     * @param topTextureLayer    {@link TextureLayers} layer index for the top face
     * @param sideTextureLayer   {@link TextureLayers} layer index for the side faces
     * @param bottomTextureLayer {@link TextureLayers} layer index for the bottom face
     * @return the newly created and registered {@link BlockType}
     * @throws IllegalArgumentException if {@code name} is already registered
     */
    public static synchronized BlockType register(String name, boolean solid,
                                                   int topTextureLayer,
                                                   int sideTextureLayer,
                                                   int bottomTextureLayer) {
        if (byName.containsKey(name)) {
            throw new IllegalArgumentException("BlockRegistry: duplicate block name: " + name);
        }

        // Grow the ID array if needed (doubles each time)
        if (nextId >= byId.length) {
            BlockType[] grown = new BlockType[byId.length * 2];
            System.arraycopy(byId, 0, grown, 0, byId.length);
            byId = grown;
        }

        BlockType type = new BlockType(nextId, name, solid,
                topTextureLayer, sideTextureLayer, bottomTextureLayer);
        byId[nextId] = type;
        byName.put(name, type);
        nextId++;
        return type;
    }

    /**
     * Returns the block type with the given numeric ID.
     *
     * @param id registry ID (0 = AIR)
     * @return the corresponding {@link BlockType}, never null
     * @throws IllegalArgumentException if no block is registered at that ID
     */
    public static BlockType getById(int id) {
        if (id < 0 || id >= nextId || byId[id] == null) {
            throw new IllegalArgumentException("BlockRegistry: unknown block ID: " + id);
        }
        return byId[id];
    }

    /**
     * Returns the block type with the given string name.
     *
     * @param name unique block name (e.g. {@code "grass"})
     * @return the corresponding {@link BlockType}, never null
     * @throws IllegalArgumentException if no block is registered with that name
     */
    public static BlockType getByName(String name) {
        BlockType type = byName.get(name);
        if (type == null) {
            throw new IllegalArgumentException("BlockRegistry: unknown block name: " + name);
        }
        return type;
    }

    /**
     * Returns the number of registered block types.
     * Also the value of the next ID that would be assigned.
     *
     * @return registered block count
     */
    public static int getCount() { return nextId; }

    /**
     * Returns an unmodifiable view of all registered blocks by name, in
     * registration order (insertion-ordered {@link LinkedHashMap}).
     *
     * @return read-only name → block type map
     */
    public static Map<String, BlockType> getAllByName() {
        return Collections.unmodifiableMap(byName);
    }
}