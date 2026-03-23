package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the client when the player breaks a block.
 *
 * @param worldX world x coordinate
 * @param worldY world y coordinate
 * @param worldZ world z coordinate
 */
public record BlockBreakPacket(int worldX, int worldY, int worldZ) implements Packet {
}
