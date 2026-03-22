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
            // 4096 bytes — the raw flat block array from Chunk.toBytes()
            out.writeBytes(p.blockData());

        } else if (msg instanceof UnloadChunkPacket p) {
            out.writeByte(PacketId.UNLOAD_CHUNK.id);
            out.writeInt(p.cx());
            out.writeInt(p.cy());
            out.writeInt(p.cz());

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