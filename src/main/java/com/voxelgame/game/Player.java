package com.voxelgame.game;

import com.voxelgame.common.world.BlockView;
import com.voxelgame.common.world.PhysicsBody;
import org.joml.Vector3f;

/**
 * The player entity. Owns a {@link PhysicsBody} and translates movement intent
 * into velocity each tick before delegating to the body for physics simulation.
 *
 * <p>This class is deliberately input-agnostic — it receives pre-processed
 * direction values from {@code GameLoop} rather than reading GLFW directly.
 * This keeps input handling centralised and makes the class reusable as a
 * template for future AI-driven entities that share the same physics.
 */
public class Player {

    /**
     * Vertical offset from feet to eyes, in blocks.
     * Camera should be placed at {@code feetY + EYE_HEIGHT}.
     */
    public static final float EYE_HEIGHT = 1.62f;

    /** Horizontal movement speed in blocks/s. */
    private static final float WALK_SPEED = 5.0f;

    private final PhysicsBody body;

    /**
     * Creates a player with feet at the given world position.
     *
     * @param x initial X
     * @param y initial Y (feet)
     * @param z initial Z
     */
    public Player(float x, float y, float z) {
        this.body = new PhysicsBody(x, y, z);
    }

    /**
     * Advances the player by one physics tick.
     *
     * <p>Sets horizontal velocity from the given movement direction (caller is
     * responsible for computing world-space direction from camera yaw), then
     * delegates to the physics body for gravity, integration, and collision.
     *
     * @param moveX     desired X movement direction this tick (world space, un-normalised)
     * @param moveZ     desired Z movement direction this tick (world space, un-normalised)
     * @param wantsJump true if the jump button is held
     * @param world     the world for collision queries
     * @param deltaTime seconds per tick
     */
    public void update(float moveX, float moveZ, boolean wantsJump, BlockView world, float deltaTime){
        float len = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (len > 0.001f) {
            moveX = (moveX / len) * WALK_SPEED;
            moveZ = (moveZ / len) * WALK_SPEED;
        }

        if (body.isOnGround()) {
            // Full control on the ground
            body.getVelocity().x = moveX;
            body.getVelocity().z = moveZ;
        } else {
            // Reduced air control — nudge toward desired direction but don't
            // override momentum fully. 0.1 feels responsive without being instant.
            body.getVelocity().x += (moveX - body.getVelocity().x) * 0.1f;
            body.getVelocity().z += (moveZ - body.getVelocity().z) * 0.1f;
        }

        if (wantsJump) body.jump();

        body.update(world, deltaTime);
    }

    /**
     * Returns the world-space position of the player's eyes.
     * This is the position the camera should occupy each frame.
     *
     * @return a new Vector3f — safe to use without copying
     */
    public Vector3f getEyePosition() {
        Vector3f p = body.getPosition();
        return new Vector3f(p.x, p.y + EYE_HEIGHT, p.z);
    }

    /** @return the underlying physics body for direct position/velocity access */
    public PhysicsBody getBody() { return body; }
}