package com.voxelgame.common.world;

/**
 * A read-only view of a block world — returns the block type at any world-space coordinate.
 *
 * <p>Both the server-side {@link com.voxelgame.game.World} and the client-side
 * {@code ClientWorld} implement this interface. Physics ({@link PhysicsBody}) and
 * raycasting ({@link RayCaster}) depend only on this interface, so they run
 * identically on client and server without duplication.
 *
 * <p>Implementations must return {@link Blocks#AIR} for unloaded chunks — never throw.
 */
public interface BlockView {

    /**
     * Returns the block at the given world-space coordinate.
     * Returns {@link Blocks#AIR} for positions in unloaded or missing chunks.
     *
     * @param worldX world-space X coordinate
     * @param worldY world-space Y coordinate
     * @param worldZ world-space Z coordinate
     * @return the block type at that position, never {@code null}
     */
    BlockType getBlock(int worldX, int worldY, int worldZ);
}