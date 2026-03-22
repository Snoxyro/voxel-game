package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;
import com.voxelgame.common.network.PacketId;

/**
 * First packet sent by the client on connect. Carries the protocol version
 * so the server can reject clients running an incompatible build.
 * Must be sent before any other packet.
 */
public record HandshakePacket(int protocolVersion) implements Packet {

    /**
     * Convenience constructor that always uses the current protocol version.
     * Most callers want this — only tests need an explicit version.
     */
    public HandshakePacket() {
        this(PacketId.PROTOCOL_VERSION);
    }
}