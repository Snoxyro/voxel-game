package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Broadcast by the server when a block changes.
 * blockId=0 means air (removal).
 *
 * @param worldX world x coordinate
 * @param worldY world y coordinate
 * @param worldZ world z coordinate
 * @param blockId block registry ID (0 means air/removal)
 */
public record BlockChangePacket(int worldX, int worldY, int worldZ, int blockId) implements Packet {
}
