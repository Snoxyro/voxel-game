package com.voxelgame.client;

/**
 * Represents another connected player as seen from this client.
 *
 * <p>The server broadcasts positions at 20 TPS. The client renders at 60 FPS.
 * Without interpolation, remote players would visibly teleport every 3 frames.
 * Linear interpolation between the previous and target positions — driven by
 * elapsed time since the last server update — produces smooth movement at any
 * frame rate.
 *
 * <h3>Interpolation approach</h3>
 * On each server update, {@code prev} snaps to the current <em>rendered</em>
 * position (not the old target). This prevents popping when an update arrives
 * slightly early or late — the interpolation always starts from wherever the
 * entity visually was.
 */
public class RemotePlayer {

    /** Expected milliseconds between server position broadcasts (20 TPS = 50 ms). */
    private static final float SERVER_TICK_MS = 50.0f;

    private final int    playerId;
    private final String username;

    // Interpolation endpoints — updated on each server packet.
    private float prevX, prevY, prevZ;
    private float targetX, targetY, targetZ;

    // Current rendered position — advanced each frame by interpolate().
    private float renderX, renderY, renderZ;

    /** Timestamp of the last received position update. Used to compute interpolation t. */
    private long lastUpdateTime;

    /**
     * Creates a RemotePlayer at the given spawn position.
     * All interpolation state is initialised to the spawn position so the entity
     * does not lerp in from the world origin.
     *
     * @param playerId server-assigned player ID
     * @param username the player's display name
     * @param x        spawn X (feet)
     * @param y        spawn Y (feet)
     * @param z        spawn Z (feet)
     */
    public RemotePlayer(int playerId, String username, float x, float y, float z) {
        this.playerId = playerId;
        this.username = username;
        prevX = targetX = renderX = x;
        prevY = targetY = renderY = y;
        prevZ = targetZ = renderZ = z;
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Records a new position received from the server.
     * Snaps {@code prev} to the current render position so interpolation
     * continues smoothly from wherever the entity visually is right now.
     *
     * @param x new target X (feet)
     * @param y new target Y (feet)
     * @param z new target Z (feet)
     */
    public void updatePosition(float x, float y, float z) {
        prevX = renderX; prevY = renderY; prevZ = renderZ;
        targetX = x; targetY = y; targetZ = z;
        lastUpdateTime = System.currentTimeMillis();
    }

    /**
     * Advances the rendered position toward the server target.
     * Must be called once per render frame. Uses elapsed time since the last
     * server update to compute {@code t}, clamped to [0, 1] so the entity
     * never overshoots the target when updates are delayed.
     */
    public void interpolate() {
        float t = Math.min(1.0f, (System.currentTimeMillis() - lastUpdateTime) / SERVER_TICK_MS);
        renderX = prevX + (targetX - prevX) * t;
        renderY = prevY + (targetY - prevY) * t;
        renderZ = prevZ + (targetZ - prevZ) * t;
    }

    /** @return server-assigned player ID */
    public int getPlayerId() { return playerId; }

    /** @return the player's display name */
    public String getUsername() { return username; }

    /** @return current interpolated X position (feet) */
    public float getRenderX() { return renderX; }

    /** @return current interpolated Y position (feet) */
    public float getRenderY() { return renderY; }

    /** @return current interpolated Z position (feet) */
    public float getRenderZ() { return renderZ; }
}