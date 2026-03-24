package com.voxelgame.engine;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Represents the player's camera. Maintains position and orientation,
 * and produces the view and projection matrices used by the renderer.
 *
 * <p>Orientation is described with Euler angles (yaw and pitch).
 * Yaw rotates left/right around the Y axis.
 * Pitch tilts up/down around the X axis.
 */
public class Camera {

    /** Vertical field of view in radians. Defaults to 70°. Mutable via {@link #setFov(int)}. */
    private float fov = (float) Math.toRadians(70.0);
    private static final float NEAR_PLANE = 0.1f;
    private static final float FAR_PLANE  = 1000.0f;

    private final Vector3f position;

    /** Horizontal rotation in degrees. -90 faces down the -Z axis (into the scene). */
    private float yaw;

    /** Vertical rotation in degrees. 0 is level, positive tilts upward. */
    private float pitch;

    /**
     * Aspect ratio (width / height). Mutable so the projection matrix updates
     * correctly when the window is resized.
     */
    private float aspectRatio;

    /**
     * Creates a camera positioned slightly behind the origin, facing forward.
     *
     * @param windowWidth  the initial window width in pixels
     * @param windowHeight the initial window height in pixels
     */
    public Camera(int windowWidth, int windowHeight) {
        this.aspectRatio = (float) windowWidth / windowHeight;
        this.position    = new Vector3f(0.0f, 0.0f, 3.0f);
        this.yaw         = -90.0f;
        this.pitch        = 0.0f;
    }

    /**
     * Updates the aspect ratio used by the projection matrix.
     * Call this whenever the window is resized.
     *
     * @param width  new framebuffer width in pixels
     * @param height new framebuffer height in pixels
     */
    public void setAspectRatio(int width, int height) {
        if (height > 0) {
            this.aspectRatio = (float) width / height;
        }
    }

    /**
     * Builds and returns the view matrix — positions the world relative to the camera.
     * Recalculated every frame because position or orientation may have changed.
     *
     * @return the view matrix
     */
    public Matrix4f getViewMatrix() {
        float yawRad   = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        Vector3f direction = new Vector3f(
            (float) (Math.cos(yawRad) * Math.cos(pitchRad)),
            (float)  Math.sin(pitchRad),
            (float) (Math.sin(yawRad) * Math.cos(pitchRad))
        ).normalize();

        Vector3f target = new Vector3f(position).add(direction);
        return new Matrix4f().lookAt(position, target, new Vector3f(0.0f, 1.0f, 0.0f));
    }

    /**
     * Builds and returns the projection matrix — creates perspective and fixes
     * aspect ratio. Uses the most recently set aspect ratio, so it responds
     * correctly to window resizes.
     *
     * @return the projection matrix
     */
    public Matrix4f getProjectionMatrix() {
        return new Matrix4f().perspective(fov, aspectRatio, NEAR_PLANE, FAR_PLANE);
    }

    /** @return the camera's current world-space position */
    public Vector3f getPosition() { return position; }

    /** @return yaw angle in degrees */
    public float getYaw() { return yaw; }

    /** @return pitch angle in degrees */
    public float getPitch() { return pitch; }

    /** @param yaw degrees */
    public void setYaw(float yaw) { this.yaw = yaw; }

    /**
     * Sets the camera's pitch. Clamped to ±89° to prevent gimbal flip.
     *
     * @param pitch degrees
     */
    public void setPitch(float pitch) {
        this.pitch = Math.max(-89.0f, Math.min(89.0f, pitch));
    }

    /**
     * Updates the vertical field of view.
     * Takes effect on the next call to {@link #getProjectionMatrix()}.
     *
     * @param fovDegrees FOV in degrees, typically 50–110
     */
    public void setFov(int fovDegrees) {
        this.fov = (float) Math.toRadians(fovDegrees);
    }
}