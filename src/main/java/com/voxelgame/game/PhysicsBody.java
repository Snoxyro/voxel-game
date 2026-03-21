package com.voxelgame.game;

import org.joml.Vector3f;

/**
 * A reusable physics component that can be owned by any world entity.
 *
 * <p>Owns position, velocity, and AABB dimensions. Each tick it applies gravity,
 * integrates velocity into position, and resolves AABB collisions against solid
 * blocks in the world — one axis at a time to allow smooth wall-sliding.
 *
 * <p>The owning entity (player, mob, etc.) sets horizontal velocity each tick
 * before calling {@link #update}. This class handles everything else.
 */
public class PhysicsBody {

    /** Gravitational acceleration in blocks/s². Applied every tick regardless of state. */
    private static final float GRAVITY           = -28.0f;

    /** Velocity floor — prevents infinite acceleration in long falls. */
    private static final float TERMINAL_VELOCITY = -50.0f;

    /** Upward velocity impulse on jump. Gives ~1.4 block max jump height. */
    private static final float JUMP_VELOCITY     =  9.0f;

    /** AABB width and depth — same on X and Z. */
    public static final float WIDTH      = 0.6f;

    /** AABB height. */
    public static final float HEIGHT     = 1.8f;

    /** Half-width, precomputed for collision math. */
    private static final float HALF_WIDTH = WIDTH / 2.0f;

    /**
     * Tiny gap used when computing block overlap ranges.
     * Prevents an AABB exactly flush with a block face from registering as
     * overlapping the next block — avoids stuttering at block boundaries.
     */
    private static final float SKIN = 1e-4f;

    /** World-space position of the feet (bottom-centre of the AABB). */
    private final Vector3f position;

    /** Current velocity in blocks/s. Horizontal components set externally each tick. */
    private final Vector3f velocity;

    /** True when the body is resting on a solid surface. */
    private boolean onGround;

    /**
     * Creates a PhysicsBody with feet at the given world-space position.
     *
     * @param x initial X
     * @param y initial Y (feet, not centre)
     * @param z initial Z
     */
    public PhysicsBody(float x, float y, float z) {
        this.position = new Vector3f(x, y, z);
        this.velocity = new Vector3f(0, 0, 0);
        this.onGround = false;
    }

    /**
     * Advances physics by one fixed tick.
     *
     * <p>Order of operations:
     * <ol>
     *   <li>Apply gravity to vertical velocity.</li>
     *   <li>Move on X, resolve X collisions.</li>
     *   <li>Move on Y, resolve Y collisions — sets {@code onGround} if landing.</li>
     *   <li>Move on Z, resolve Z collisions.</li>
     * </ol>
     *
     * @param world     world to query for solid blocks
     * @param deltaTime seconds this tick represents (typically 1/60)
     */
    public void update(World world, float deltaTime) {
        // Gravity is applied unconditionally. When standing on the ground,
        // the small downward displacement is immediately cancelled by the Y
        // collision resolver each tick — position stays stable.
        velocity.y += GRAVITY * deltaTime;
        if (velocity.y < TERMINAL_VELOCITY) {
            velocity.y = TERMINAL_VELOCITY;
        }

        onGround = false;

        // --- X axis ---
        if (velocity.x != 0) {
            position.x += velocity.x * deltaTime;
            if (resolveAxis(world, 0)) velocity.x = 0;
        }

        // --- Y axis (always — gravity is always active) ---
        position.y += velocity.y * deltaTime;
        boolean yHit = resolveAxis(world, 1);
        if (yHit) {
            if (velocity.y <= 0) onGround = true; // landed, not head-bumped
            velocity.y = 0;
        }

        // --- Z axis ---
        if (velocity.z != 0) {
            position.z += velocity.z * deltaTime;
            if (resolveAxis(world, 2)) velocity.z = 0;
        }
    }

    /**
     * Applies an upward velocity impulse.
     * Does nothing when airborne — prevents double-jumping.
     */
    public void jump() {
        if (onGround) {
            velocity.y = JUMP_VELOCITY;
            onGround   = false;
        }
    }

    /**
     * Resolves AABB overlaps against solid world blocks on a single axis.
     *
     * <p>Iterates every block the AABB currently covers. For each solid block
     * found, snaps the body to the block face it entered (direction determined
     * by the sign of velocity on this axis) and updates the AABB bounds so
     * subsequent blocks in the same loop see the corrected position.
     *
     * @param world the world to query
     * @param axis  0 = X, 1 = Y, 2 = Z
     * @return true if at least one collision was resolved
     */
    private boolean resolveAxis(World world, int axis) {
        // Current AABB bounds
        float minX = position.x - HALF_WIDTH;
        float maxX = position.x + HALF_WIDTH;
        float minY = position.y;
        float maxY = position.y + HEIGHT;
        float minZ = position.z - HALF_WIDTH;
        float maxZ = position.z + HALF_WIDTH;

        // Block grid range the AABB covers.
        // SKIN on the max side prevents treating a flush contact as an overlap.
        int bMinX = (int) Math.floor(minX);       int bMaxX = (int) Math.floor(maxX - SKIN);
        int bMinY = (int) Math.floor(minY);       int bMaxY = (int) Math.floor(maxY - SKIN);
        int bMinZ = (int) Math.floor(minZ);       int bMaxZ = (int) Math.floor(maxZ - SKIN);

        boolean collided = false;

        for (int bx = bMinX; bx <= bMaxX; bx++) {
            for (int by = bMinY; by <= bMaxY; by++) {
                for (int bz = bMinZ; bz <= bMaxZ; bz++) {
                    if (world.getBlock(bx, by, bz) == Block.AIR) continue;

                    // Snap the body to the block face it entered on this axis.
                    // After snapping, recalculate AABB bounds so later iterations
                    // in the same loop see the corrected position.
                    switch (axis) {
                        case 0 -> {
                            position.x = velocity.x > 0
                                ? bx - HALF_WIDTH          // hit right face → snap left
                                : bx + 1.0f + HALF_WIDTH;  // hit left face  → snap right
                            minX = position.x - HALF_WIDTH;
                            maxX = position.x + HALF_WIDTH;
                        }
                        case 1 -> {
                            position.y = velocity.y < 0
                                ? by + 1.0f          // hit top face    → land on it
                                : by - HEIGHT;       // hit bottom face → head bump
                            minY = position.y;
                            maxY = position.y + HEIGHT;
                        }
                        case 2 -> {
                            position.z = velocity.z > 0
                                ? bz - HALF_WIDTH          // hit far face  → snap back
                                : bz + 1.0f + HALF_WIDTH;  // hit near face → snap forward
                            minZ = position.z - HALF_WIDTH;
                            maxZ = position.z + HALF_WIDTH;
                        }
                    }
                    collided = true;
                }
            }
        }
        return collided;
    }

    /**
     * Returns the feet position. The vector is live — modifications affect the body.
     * @return world-space feet position
     */
    public Vector3f getPosition() { return position; }

    /**
     * Returns the velocity vector. The vector is live — the owning entity sets
     * horizontal components here each tick before calling {@link #update}.
     * @return current velocity in blocks/s
     */
    public Vector3f getVelocity() { return velocity; }

    /** @return true if currently resting on a solid surface */
    public boolean isOnGround() { return onGround; }

    /**
     * Returns true if this body's AABB overlaps the given block's unit cube.
     * Used to prevent placing a block inside the player or another entity.
     *
     * @param bx block X in world space
     * @param by block Y in world space
     * @param bz block Z in world space
     * @return true if the AABB intersects the block volume
     */
    public boolean overlapsBlock(int bx, int by, int bz) {
        return position.x - HALF_WIDTH < bx + 1 && position.x + HALF_WIDTH > bx
            && position.y            < by + 1 && position.y + HEIGHT      > by
            && position.z - HALF_WIDTH < bz + 1 && position.z + HALF_WIDTH > bz;
    }
}