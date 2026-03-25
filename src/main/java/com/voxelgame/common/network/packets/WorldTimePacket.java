package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the server to synchronise world time on all connected clients.
 * Broadcast every 20 server ticks (once per real second).
 *
 * <p>The client applies the tick value directly to its local {@link
 * com.voxelgame.common.world.WorldTime} instance. At a 20-minute day length,
 * a 1-second sync interval produces imperceptible drift — no interpolation needed.
 *
 * @param worldTick the server's current world tick
 */
public record WorldTimePacket(long worldTick) implements Packet {}