package io.netty.channel.quic;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;

public interface QuicChannel extends Channel {
    @Override
    QuicServerChannel parent();

    @Override
    QuicChannelConfig config();
}
