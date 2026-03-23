package com.voxelgame.common.network;

import com.voxelgame.common.network.packets.*;
import com.voxelgame.common.world.Chunk;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Netty inbound handler. Deserializes a complete {@link ByteBuf} frame into a
 * {@link Packet} object.
 *
 * <h3>Pipeline position</h3>
 * {@code LengthFieldBasedFrameDecoder} upstream guarantees that each {@link ByteBuf}
 * this decoder receives is exactly one complete packet. The first byte is the packet ID;
 * the remainder is the payload, read in the same order the encoder wrote it.
 *
 * <h3>Unknown packet IDs</h3>
 * {@link PacketId#fromId} throws {@link IllegalArgumentException} for unknown IDs.
 * Netty's exception pipeline catches it and calls {@code exceptionCaught} on the next
 * handler, which closes the channel.
 */
public class PacketDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        int rawId   = in.readUnsignedByte();
        PacketId id = PacketId.fromId(rawId);

        Packet packet = switch (id) {
            case HANDSHAKE     -> new HandshakePacket(in.readInt());
            case LOGIN_REQUEST -> new LoginRequestPacket(readString(in));
            case LOGIN_SUCCESS -> new LoginSuccessPacket(
                    in.readInt(), in.readFloat(), in.readFloat(), in.readFloat());

            case CHUNK_DATA -> {
                int cx = in.readInt(), cy = in.readInt(), cz = in.readInt();
                // Read exactly Chunk.SERIALIZED_SIZE bytes (8192 — 2 bytes per block)
                byte[] data = new byte[Chunk.SERIALIZED_SIZE];
                in.readBytes(data);
                yield new ChunkDataPacket(cx, cy, cz, data);
            }

            case UNLOAD_CHUNK ->
                new UnloadChunkPacket(in.readInt(), in.readInt(), in.readInt());

            case BLOCK_BREAK ->
                new BlockBreakPacket(in.readInt(), in.readInt(), in.readInt());

            case BLOCK_PLACE ->
                new BlockPlacePacket(in.readInt(), in.readInt(), in.readInt(), in.readInt());

            case PLAYER_MOVE_SB ->
                new PlayerMoveSBPacket(
                    in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());

            case BLOCK_CHANGE ->
                new BlockChangePacket(in.readInt(), in.readInt(), in.readInt(), in.readInt());

            case PLAYER_SPAWN ->
                new PlayerSpawnPacket(in.readInt(), readString(in), in.readFloat(), in.readFloat(), in.readFloat());

            case PLAYER_MOVE_CB ->
                new PlayerMoveCBPacket(in.readInt(), in.readFloat(), in.readFloat(), in.readFloat());

            case PLAYER_DESPAWN ->
                new PlayerDespawnPacket(in.readInt());

            default -> throw new IllegalStateException(
                "PacketDecoder: no deserializer for " + id);
        };

        out.add(packet);
    }

    /**
     * Reads a UTF-8 string written by {@link PacketEncoder#writeString}.
     * Format: {@code [2-byte unsigned length][bytes]}.
     */
    static String readString(ByteBuf buf) {
        int    len   = buf.readUnsignedShort();
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}