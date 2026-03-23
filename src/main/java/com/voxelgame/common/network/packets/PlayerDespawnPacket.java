package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent to all remaining players when a player disconnects.
 *
 * @param playerId unique server-assigned player identifier
 */
public record PlayerDespawnPacket(int playerId) implements Packet {
}
