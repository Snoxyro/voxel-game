package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the client when the player places a block.
 * blockId is the selected block type's registry ID.
 *
 * @param worldX world x coordinate
 * @param worldY world y coordinate
 * @param worldZ world z coordinate
 * @param blockId selected block type registry ID
 */
public record BlockPlacePacket(int worldX, int worldY, int worldZ, int blockId) implements Packet {
}
