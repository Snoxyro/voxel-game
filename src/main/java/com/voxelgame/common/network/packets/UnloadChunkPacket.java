package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the server when a chunk leaves a player's range.
 * Client should remove the chunk and free its mesh.
 *
 * @param cx chunk x coordinate
 * @param cy chunk y coordinate
 * @param cz chunk z coordinate
 */
public record UnloadChunkPacket(int cx, int cy, int cz) implements Packet {
}
