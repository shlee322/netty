/*
 * Copyright 2019 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel.quic;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import org.junit.Test;

import java.net.InetSocketAddress;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;


public abstract class QuicTest {
    @Test(timeout = 5000)
    public void testTest() throws Exception {
        EventLoopGroup loop = newEventLoopGroup();
        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap.group(loop)
                    .channel(serverClass())
                    .localAddress(new InetSocketAddress(0))
                    .childHandler(new ChannelInboundHandlerAdapter());

            Bootstrap clientBootstrap = new Bootstrap()
                    .group(loop)
                    .channel(clientClass())
                    .handler(new ChannelInboundHandlerAdapter());

            Channel serverChannel = serverBootstrap.bind()
                    .syncUninterruptibly().channel();
            QuicChannel clientChannel = (QuicChannel) clientBootstrap.connect(serverChannel.localAddress())
                    .syncUninterruptibly().channel();

            // TODO

            serverChannel.close().syncUninterruptibly();
            clientChannel.close().syncUninterruptibly();
        } finally {
            loop.shutdownGracefully();
        }
    }

    protected abstract EventLoopGroup newEventLoopGroup();
    protected abstract Class<? extends QuicChannel> clientClass();
    protected abstract Class<? extends QuicServerChannel> serverClass();
}
