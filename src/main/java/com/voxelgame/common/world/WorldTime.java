package com.voxelgame.common.world;

/**
 * Tracks world time and derives lighting values from it.
 *
 * <p>Time is measured in server ticks. One full day-night cycle is
 * {@link #DAY_LENGTH_TICKS} ticks long. At 20 TPS this equals 20 real minutes.
 *
 * <h3>Time landmarks</h3>
 * <ul>
 *   <li>Tick 0     — sunrise</li>
 *   <li>Tick 6000  — noon (maximum brightness)</li>
 *   <li>Tick 12000 — sunset</li>
 *   <li>Tick 18000 — midnight (minimum brightness)</li>
 * </ul>
 *
 * <p>Used server-side to advance time and compute the sync value sent to clients.
 * Used client-side (read-only after {@link #setWorldTick}) to drive shader uniforms
 * and sky colour. Pure math — no GL, no network dependencies.
 */
public final class WorldTime {

    /** Ticks in one full day-night cycle (20 min at 20 TPS). */
    public static final long DAY_LENGTH_TICKS = 1200L;

    /**
     * Minimum ambient factor at midnight — dark but not pitch black.
     * A value of 0.15 preserves silhouettes and prevents the world from becoming
     * completely invisible at night. Increase toward 1.0 to reduce the night effect.
     */
    private static final float MIN_AMBIENT = 0.15f;

    /** Day sky colour — a clear midday blue. */
    private static final float[] SKY_DAY   = { 0.53f, 0.81f, 0.98f };

    /** Night sky colour — deep dark blue, not pure black. */
    private static final float[] SKY_NIGHT = { 0.02f, 0.02f, 0.10f };

    /** Current world tick. Wraps naturally via modulo in all derivation methods. */
    private volatile long worldTick = 0L;

    /**
     * Advances time by one server tick.
     * Called once per tick on the server game loop thread.
     */
    public void tick() {
        worldTick++;
    }

    /**
     * Returns the current world tick.
     *
     * @return current tick count — does not wrap; use modulo {@link #DAY_LENGTH_TICKS}
     *         to obtain position within today's cycle
     */
    public long getWorldTick() {
        return worldTick;
    }

    /**
     * Sets the current world tick. Used client-side to apply values received
     * from the server via {@code WorldTimePacket}.
     *
     * @param tick the server-authoritative tick value
     */
    public void setWorldTick(long tick) {
        this.worldTick = tick;
    }

    /**
     * Computes the ambient light multiplier for the current time of day.
     *
     * <p>Uses a sine curve centred on noon (tick 6000). The raw sine output
     * {@code [−1, 1]} is remapped to {@code [MIN_AMBIENT, 1.0]} so nights are
     * dark but never completely black.
     *
     * @return ambient factor in [{@link #MIN_AMBIENT}, 1.0]
     */
    public float getAmbientFactor() {
        // Fraction of the day elapsed: 0.0 at tick 0, 1.0 at DAY_LENGTH_TICKS.
        double dayFraction = (double)(worldTick % DAY_LENGTH_TICKS) / DAY_LENGTH_TICKS;

        // Shift so that dayFraction=0.25 (tick 6000) maps to the sine peak (PI/2).
        // sin(2π * fraction - π/2) peaks at fraction=0.25 (noon) and troughs at 0.75 (midnight).
        double sine = Math.sin(2.0 * Math.PI * dayFraction - Math.PI / 2.0);

        // Remap [-1, 1] → [MIN_AMBIENT, 1.0].
        return MIN_AMBIENT + (float)((sine + 1.0) / 2.0) * (1.0f - MIN_AMBIENT);
    }

    /**
     * Computes the sky clear-colour for the current time of day.
     *
     * <p>Linearly interpolates between {@code SKY_DAY} and {@code SKY_NIGHT}
     * using the same curve as {@link #getAmbientFactor}. Returns a new
     * {@code float[3]} — RGB, each in [0.0, 1.0]. Pass directly to
     * {@code glClearColor}.
     *
     * @return float[3] RGB sky colour
     */
    public float[] getSkyColor() {
        float t = getAmbientFactor();
        // t=1.0 → full day colour, t=MIN_AMBIENT → full night colour.
        // Normalise t into [0,1] across the full ambient range for a clean lerp.
        float lerp = (t - MIN_AMBIENT) / (1.0f - MIN_AMBIENT);
        return new float[] {
            SKY_NIGHT[0] + lerp * (SKY_DAY[0] - SKY_NIGHT[0]),
            SKY_NIGHT[1] + lerp * (SKY_DAY[1] - SKY_NIGHT[1]),
            SKY_NIGHT[2] + lerp * (SKY_DAY[2] - SKY_NIGHT[2])
        };
    }
}