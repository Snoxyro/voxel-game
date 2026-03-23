package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Broadcast by the server at 20 TPS with another player's current position.
 * Never sent to the moving player themselves.
 *
 * @param playerId unique server-assigned player identifier
 * @param x player's x position
 * @param y player's y position
 * @param z player's z position
 */
public record PlayerMoveCBPacket(int playerId, float x, float y, float z) implements Packet {
}
