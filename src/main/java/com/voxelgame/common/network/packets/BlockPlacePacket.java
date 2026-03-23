package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the client when the player places a block.
 * blockOrdinal is Block.ordinal() of the selected block type.
 *
 * @param worldX world x coordinate
 * @param worldY world y coordinate
 * @param worldZ world z coordinate
 * @param blockOrdinal Block.ordinal() of the selected block type
 */
public record BlockPlacePacket(int worldX, int worldY, int worldZ, int blockOrdinal) implements Packet {
}
