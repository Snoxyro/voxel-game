package com.voxelgame.common.world;

/**
 * Static constants for all vanilla block types.
 *
 * <p>All vanilla blocks are registered in this class's {@code static} initializer,
 * which runs the first time any field in this class is accessed. Call
 * {@link #bootstrap()} explicitly at the very start of {@code Main.main()} and
 * {@code ServerMain.main()} to ensure deterministic registration before any other
 * code runs.
 *
 * <h3>Registration order = ID assignment</h3>
 * The order of registration here is permanent. AIR must always be first (ID 0)
 * because {@link Chunk} zero-initialises its block array and relies on 0 meaning AIR.
 * Never insert a new block before an existing one — append only. This is the same
 * contract as a database migration: you can add rows, never reorder them.
 *
 * <h3>Adding a new vanilla block</h3>
 * <ol>
 *   <li>Add a constant field below (append at the end, before the static block).</li>
 *   <li>Add a corresponding {@link TextureLayers} constant if a new texture is needed.</li>
 *   <li>Add a {@link BlockRegistry#register} call at the bottom of the static block.</li>
 * </ol>
 */
public final class Blocks {

    /** Empty space. ID = 0. Non-solid, never rendered. */
    public static final BlockType AIR;
    /** Grass block — green top, grass-side sides, dirt bottom. */
    public static final BlockType GRASS;
    /** Dirt — uniform brown on all faces. */
    public static final BlockType DIRT;
    /** Stone — grey cobblestone-style. */
    public static final BlockType STONE;

    static {
        // ---------------------------------------------------------------
        // REGISTRATION ORDER IS PERMANENT. APPEND ONLY. NEVER REORDER.
        // AIR must be first — ID 0 matches Chunk's zero-initialised array.
        // ---------------------------------------------------------------
        AIR   = BlockRegistry.register("air",   false, 0, 0, 0);
        GRASS = BlockRegistry.register("grass", true,
                TextureLayers.LAYER_GRASS_TOP, TextureLayers.LAYER_GRASS_SIDE, TextureLayers.LAYER_DIRT);
        DIRT  = BlockRegistry.register("dirt",  true,
                TextureLayers.LAYER_DIRT, TextureLayers.LAYER_DIRT, TextureLayers.LAYER_DIRT);
        STONE = BlockRegistry.register("stone", true,
                TextureLayers.LAYER_STONE, TextureLayers.LAYER_STONE, TextureLayers.LAYER_STONE);
    }

    /**
     * Triggers class loading, which runs the static initializer and registers all
     * vanilla blocks. Call this at the very start of {@code main()} in both
     * {@code Main} and {@code ServerMain} before any other code runs.
     *
     * <p>Calling this multiple times is harmless — class loading only happens once.
     */
    public static void bootstrap() {
        // Intentionally empty — the act of calling this method ensures the class
        // is loaded, which triggers the static block above.
    }

    private Blocks() {} // static-only
}