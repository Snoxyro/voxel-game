package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the client every tick with the player's current world-space position and look direction.
 * The server uses this to advance the chunk streaming center.
 *
 * @param x player world x coordinate
 * @param y player world y coordinate
 * @param z player world z coordinate
 * @param yaw player yaw in degrees
 * @param pitch player pitch in degrees
 */
public record PlayerMoveSBPacket(float x, float y, float z, float yaw, float pitch)
        implements Packet {
}
