package io.netty.channel.quic;

import io.netty.channel.ServerChannel;

import java.net.InetSocketAddress;

public interface QuicServerChannel extends ServerChannel {
    @Override
    QuicServerChannelConfig config();

    @Override
    InetSocketAddress localAddress();


}
