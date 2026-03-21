package com.voxelgame.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Represents the player's camera. Maintains position and orientation,
 * and produces the view and projection matrices used by the renderer.
 *
 * Orientation is described with Euler angles (yaw and pitch).
 * Yaw rotates left/right around the Y axis.
 * Pitch tilts up/down around the X axis.
 */
public class Camera {

    /** Vertical field of view in radians — 70° is a natural, non-fisheye FOV. */
    private static final float FOV        = (float) Math.toRadians(70.0);
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE  = 1000.0f;

    private final Vector3f position;

    /** Horizontal rotation in degrees. -90 faces down the -Z axis (into the scene). */
    private float yaw;

    /** Vertical rotation in degrees. 0 is level, positive tilts upward. */
    private float pitch;

    private final float aspectRatio;

    /**
     * Creates a camera positioned slightly behind the origin, facing forward.
     *
     * @param windowWidth  the window width in pixels, used for aspect ratio
     * @param windowHeight the window height in pixels, used for aspect ratio
     */
    public Camera(int windowWidth, int windowHeight) {
        this.aspectRatio = (float) windowWidth / windowHeight;
        this.position    = new Vector3f(0.0f, 0.0f, 3.0f); // 3 units back from origin
        this.yaw         = -90.0f; // face -Z so we're looking at the scene
        this.pitch       = 0.0f;
    }

    /**
     * Builds and returns the view matrix — positions the world relative to the camera.
     * Recalculated every frame because position or orientation may have changed.
     *
     * @return the view matrix
     */
    public Matrix4f getViewMatrix() {
        // Convert yaw/pitch to a direction vector the camera is facing.
        // This is the standard spherical-to-cartesian conversion for a look direction.
        float yawRad   = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        Vector3f direction = new Vector3f(
            (float) (Math.cos(yawRad) * Math.cos(pitchRad)),
            (float) (Math.sin(pitchRad)),
            (float) (Math.sin(yawRad) * Math.cos(pitchRad))
        ).normalize();

        // lookAt: camera position, the point it's aimed at, world up direction
        Vector3f target = new Vector3f(position).add(direction);
        return new Matrix4f().lookAt(position, target, new Vector3f(0.0f, 1.0f, 0.0f));
    }

    /**
     * Builds and returns the projection matrix — creates perspective and fixes aspect ratio.
     * Only changes if the window is resized (not handled yet).
     *
     * @return the projection matrix
     */
    public Matrix4f getProjectionMatrix() {
        return new Matrix4f().perspective(FOV, aspectRatio, NEAR_PLANE, FAR_PLANE);
    }

    /** @return the camera's current world-space position */
    public Vector3f getPosition() { return position; }

    /** @return yaw angle in degrees */
    public float getYaw() { return yaw; }

    /** @return pitch angle in degrees */
    public float getPitch() { return pitch; }

    /**
     * Sets the camera's yaw (left/right rotation).
     * @param yaw degrees
     */
    public void setYaw(float yaw) { this.yaw = yaw; }

    /**
     * Sets the camera's pitch (up/down tilt).
     * Clamped to ±89° to prevent gimbal flip at straight up/down.
     *
     * @param pitch degrees
     */
    public void setPitch(float pitch) {
        this.pitch = Math.max(-89.0f, Math.min(89.0f, pitch));
    }
}