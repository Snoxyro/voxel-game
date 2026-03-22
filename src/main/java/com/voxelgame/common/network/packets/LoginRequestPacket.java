package com.voxelgame.common.network.packets;

import com.voxelgame.common.network.Packet;

/**
 * Sent by the client immediately after {@link HandshakePacket} to request login.
 * In offline mode the username is trusted as-is — no verification occurs.
 */
public record LoginRequestPacket(String username) implements Packet {}