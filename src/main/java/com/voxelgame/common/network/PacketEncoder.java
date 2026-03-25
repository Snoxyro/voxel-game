package com.voxelgame.common.network;

import com.voxelgame.common.network.packets.*;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.nio.charset.StandardCharsets;

/**
 * Netty outbound handler. Serializes a {@link Packet} object into a {@link ByteBuf}.
 *
 * <h3>Pipeline position</h3>
 * Sits between the application handler and {@code LengthFieldPrepender}.
 * Final wire format: {@code [4-byte length][1-byte ID][payload]}.
 *
 * <h3>Adding new packet types</h3>
 * Add an {@code else if (msg instanceof FooPacket p)} branch, write the ID byte first,
 * then write the payload fields in a consistent order. The decoder must read them in the
 * same order.
 *
 * <h3>String encoding</h3>
 * Strings use {@link #writeString} — {@code [2-byte unsigned length][UTF-8 bytes]}.
 * Max 65535 bytes. Always pair with {@link PacketDecoder#readString}.
 */
public class PacketEncoder extends MessageToByteEncoder<Packet> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet msg, ByteBuf out) {
        if (msg instanceof HandshakePacket p) {
            out.writeByte(PacketId.HANDSHAKE.id);
            out.writeInt(p.protocolVersion());

        } else if (msg instanceof LoginRequestPacket p) {
            out.writeByte(PacketId.LOGIN_REQUEST.id);
            writeString(out, p.username());

        } else if (msg instanceof LoginSuccessPacket p) {
            out.writeByte(PacketId.LOGIN_SUCCESS.id);
            out.writeInt(p.playerId());
            out.writeFloat(p.spawnX());
            out.writeFloat(p.spawnY());
            out.writeFloat(p.spawnZ());

        } else if (msg instanceof ChunkDataPacket p) {
            out.writeByte(PacketId.CHUNK_DATA.id);
            out.writeInt(p.cx());
            out.writeInt(p.cy());
            out.writeInt(p.cz());
            // Chunk.SERIALIZED_SIZE bytes (8192 — 2 bytes per block, big-endian short registry IDs)
            out.writeBytes(p.blockData());

        } else if (msg instanceof UnloadChunkPacket p) {
            out.writeByte(PacketId.UNLOAD_CHUNK.id);
            out.writeInt(p.cx());
            out.writeInt(p.cy());
            out.writeInt(p.cz());

        } else if (msg instanceof BlockBreakPacket p) {
            out.writeByte(PacketId.BLOCK_BREAK.id);
            out.writeInt(p.worldX());
            out.writeInt(p.worldY());
            out.writeInt(p.worldZ());

        } else if (msg instanceof BlockPlacePacket p) {
            out.writeByte(PacketId.BLOCK_PLACE.id);
            out.writeInt(p.worldX());
            out.writeInt(p.worldY());
            out.writeInt(p.worldZ());
            out.writeInt(p.blockId());

        } else if (msg instanceof PlayerMoveSBPacket p) {
            out.writeByte(PacketId.PLAYER_MOVE_SB.id);
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());
            out.writeFloat(p.yaw());
            out.writeFloat(p.pitch());

        } else if (msg instanceof BlockChangePacket p) {
            out.writeByte(PacketId.BLOCK_CHANGE.id);
            out.writeInt(p.worldX());
            out.writeInt(p.worldY());
            out.writeInt(p.worldZ());
            out.writeInt(p.blockId());

        } else if (msg instanceof PlayerSpawnPacket p) {
            out.writeByte(PacketId.PLAYER_SPAWN.id);
            out.writeInt(p.playerId());
            writeString(out, p.username());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());

        } else if (msg instanceof PlayerMoveCBPacket p) {
            out.writeByte(PacketId.PLAYER_MOVE_CB.id);
            out.writeInt(p.playerId());
            out.writeFloat(p.x());
            out.writeFloat(p.y());
            out.writeFloat(p.z());

        } else if (msg instanceof PlayerDespawnPacket p) {
            out.writeByte(PacketId.PLAYER_DESPAWN.id);
            out.writeInt(p.playerId());
            
        } else if (msg instanceof WorldTimePacket p) {
            out.writeByte(PacketId.WORLD_TIME.id);
            out.writeLong(p.worldTick());

        } else {
            throw new IllegalArgumentException(
                "PacketEncoder: no serializer for " + msg.getClass().getSimpleName());
        }
    }

    /**
     * Writes a UTF-8 string as {@code [2-byte unsigned length][bytes]}.
     * Paired with {@link PacketDecoder#readString}.
     *
     * @param buf the buffer to write into
     * @param s   the string to encode
     */
    static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }
}