package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the server after successfully processing a {@link LoginRequestPacket}.
 * Provides the assigned player ID and initial spawn coordinates.
 * After this packet, the connection transitions to the Play state.
 */
public record LoginSuccessPacket(int playerId, float spawnX, float spawnY, float spawnZ)
        implements Packet {}