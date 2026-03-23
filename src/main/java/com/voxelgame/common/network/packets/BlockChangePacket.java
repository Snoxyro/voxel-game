package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Broadcast by the server when a block changes.
 * blockOrdinal=0 means air (removal).
 *
 * @param worldX world x coordinate
 * @param worldY world y coordinate
 * @param worldZ world z coordinate
 * @param blockOrdinal block ordinal (0 means air/removal)
 */
public record BlockChangePacket(int worldX, int worldY, int worldZ, int blockOrdinal) implements Packet {
}
