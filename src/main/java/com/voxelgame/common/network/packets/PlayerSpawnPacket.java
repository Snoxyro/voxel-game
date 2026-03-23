package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent to all online players when a new player joins.
 * Also sent to a newly connected player for each player already online.
 *
 * @param playerId unique server-assigned player identifier
 * @param username player's display name
 * @param x player's x position
 * @param y player's y position
 * @param z player's z position
 */
public record PlayerSpawnPacket(int playerId, String username, float x, float y, float z) implements Packet {
}
