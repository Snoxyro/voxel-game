package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the server when a chunk enters a player's range.
 * The {@code blockData} field is 4096 raw bytes (the chunk's flat block array).
 *
 * @param cx chunk x coordinate
 * @param cy chunk y coordinate
 * @param cz chunk z coordinate
 * @param blockData chunk block data (4096 raw bytes)
 */
public record ChunkDataPacket(int cx, int cy, int cz, byte[] blockData) implements Packet {
}
