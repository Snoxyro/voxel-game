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
     * Package-private — only {@link BlockRegistry} constructs block types.
     *
     * @param id                unique registry ID, assigned sequentially from 0
     * @param name              unique string identifier (e.g. {@code "grass"})
     * @param solid             {@code true} if this block is physically solid and renders geometry
     * @param topTextureLayer   texture array layer for the top face
     * @param sideTextureLayer  texture array layer for the N/S/E/W side faces
     * @param bottomTextureLayer texture array layer for the bottom face
     */
    BlockType(int id, String name, boolean solid,
              int topTextureLayer, int sideTextureLayer, int bottomTextureLayer) {
        this.id                 = id;
        this.name               = name;
        this.solid              = solid;
        this.topTextureLayer    = topTextureLayer;
        this.sideTextureLayer   = sideTextureLayer;
        this.bottomTextureLayer = bottomTextureLayer;
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

    @Override
    public String toString() {
        return "BlockType{id=" + id + ", name='" + name + "'}";
    }
}