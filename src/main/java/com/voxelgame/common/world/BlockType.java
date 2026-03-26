package com.voxelgame.common.world;

/**
 * An immutable registered block type — the replacement for the old {@code Block} enum.
 *
 * <p>Instances are created only by {@link BlockRegistry#register} and are singletons:
 * there is exactly one {@code BlockType} object per registered block, shared across all
 * threads. Identity comparison ({@code ==}) is valid and efficient.
 *
 * <h3>Why not an enum?</h3>
 * Enums have fixed ordinals determined by declaration order. Adding a block between two
 * existing ones shifts every subsequent ordinal, silently corrupting every saved chunk
 * and every in-flight network packet that used the old ordinals. A registry assigns IDs
 * sequentially at registration time and never changes them — the order of {@link Blocks}
 * static field initialization is the permanent ID order.
 *
 * <h3>Texture layers</h3>
 * Each block stores the texture array layer index for its top, side, and bottom faces.
 * These reference {@link TextureLayers} constants. The server never calls these methods
 * (it has no renderer), but storing them here is safe — they are plain ints with no
 * engine import.
 */
public final class BlockType {

    private final int    id;
    private final String name;
    private final boolean solid;
    private final int    topTextureLayer;
    private final int    sideTextureLayer;
    private final int    bottomTextureLayer;

    /**
     * Light level emitted by this block (0–15). 0 = no light.
     * Non-zero values are used as the seed level for block-light BFS propagation.
     * All current vanilla blocks emit 0; reserved for torches, lava, glowstone, etc.
     */
    private final int    lightEmission;

    BlockType(int id, String name, boolean solid, int topTextureLayer, int sideTextureLayer,
        int bottomTextureLayer, int lightEmission) {
        this.id                 = id;
        this.name               = name;
        this.solid              = solid;
        this.topTextureLayer    = topTextureLayer;
        this.sideTextureLayer   = sideTextureLayer;
        this.bottomTextureLayer = bottomTextureLayer;
        this.lightEmission      = lightEmission;
    }


    /**
     * Returns the unique numeric ID assigned by the registry.
     * ID 0 is always {@link Blocks#AIR}.
     *
     * @return registry ID ≥ 0
     */
    public int getId() { return id; }

    /**
     * Returns the unique string name (e.g. {@code "air"}, {@code "grass"}).
     *
     * @return name string, never null
     */
    public String getName() { return name; }

    /**
     * Returns {@code true} if this block occupies space physically — collides with
     * entities and contributes geometry to the mesh. AIR is the only non-solid type
     * registered in vanilla; future transparent blocks (glass, water) will also
     * return {@code false}.
     *
     * @return {@code true} for solid blocks
     */
    public boolean isSolid() { return solid; }

    /**
     * Returns {@code true} if this block is fully opaque — light cannot pass through it.
     *
     * <p>Currently equivalent to {@link #isSolid()} because every registered block is
     * either fully opaque (stone, grass, dirt) or fully transparent (air). When
     * transparent blocks arrive (glass, water, leaves), they will be solid for physics
     * but non-opaque for light. At that point this method will diverge from
     * {@code isSolid()} and will be backed by a separate {@code opaque} field in the
     * constructor.
     *
     * <p>The BFS light engine uses this as its propagation stop condition: an opaque block
     * absorbs all incoming light and cannot receive or re-emit propagated light.
     *
     * @return {@code true} for light-blocking blocks
     */
    public boolean isOpaque() { return solid; }

    /**
     * Returns the {@link TextureLayers} layer index for the top face.
     * Only meaningful on the client; the server never calls this.
     *
     * @return texture array layer index ≥ 0
     */
    public int topTextureLayer()    { return topTextureLayer; }

    /**
     * Returns the {@link TextureLayers} layer index for the four side faces (N/S/E/W).
     *
     * @return texture array layer index ≥ 0
     */
    public int sideTextureLayer()   { return sideTextureLayer; }

    /**
     * Returns the {@link TextureLayers} layer index for the bottom face.
     *
     * @return texture array layer index ≥ 0
     */
    public int bottomTextureLayer() { return bottomTextureLayer; }

    /**
     * Returns the light level emitted by this block type (0–15).
     * 0 means this block produces no light. Non-zero values are used as the
     * initial propagation level when BFS light spreading is computed.
     *
     * @return emission level in [0, 15]
     */
    public int getLightEmission() { return lightEmission; }

    @Override
    public String toString() {
        return "BlockType{id=" + id + ", name='" + name + "'}";
    }
}