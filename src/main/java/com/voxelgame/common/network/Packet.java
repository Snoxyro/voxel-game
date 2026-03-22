package com.voxelgame.common.network;

/**
 * Marker interface implemented by every network packet type.
 * Allows PacketEncoder and PacketDecoder to use a single typed pipeline
 * without casting to Object throughout handler code.
 */
public interface Packet {}