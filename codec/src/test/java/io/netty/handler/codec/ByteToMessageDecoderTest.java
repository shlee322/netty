/*
 * Copyright 2013 The Netty Project
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
package io.netty.handler.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.Assert.*;

public class ByteToMessageDecoderTest {

    @Test
    public void testRemoveItself() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            private boolean removed;

            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                assertFalse(removed);
                in.readByte();
                ctx.pipeline().remove(this);
                removed = true;
            }
        });

        ByteBuf buf = Unpooled.wrappedBuffer(new byte[] {'a', 'b', 'c'});
        channel.writeInbound(buf.copy());
        ByteBuf b = channel.readInbound();
        assertEquals(b, buf.skipBytes(1));
        b.release();
        buf.release();
    }

    @Test
    public void testRemoveItselfWriteBuffer() {
        final ByteBuf buf = Unpooled.buffer().writeBytes(new byte[] {'a', 'b', 'c'});
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            private boolean removed;

            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                assertFalse(removed);
                in.readByte();
                ctx.pipeline().remove(this);

                // This should not let it keep call decode
                buf.writeByte('d');
                removed = true;
            }
        });

        channel.writeInbound(buf.copy());
        ByteBuf expected = Unpooled.wrappedBuffer(new byte[] {'b', 'c'});
        ByteBuf b = channel.readInbound();
        assertEquals(expected, b);
        expected.release();
        buf.release();
        b.release();
    }

    /**
     * Verifies that internal buffer of the ByteToMessageDecoder is released once decoder is removed from pipeline. In
     * this case input is read fully.
     */
    @Test
    public void testInternalBufferClearReadAll() {
        final ByteBuf buf = Unpooled.buffer().writeBytes(new byte[] {'a'});
        EmbeddedChannel channel = newInternalBufferTestChannel();
        assertFalse(channel.writeInbound(buf));
        assertFalse(channel.finish());
    }

    /**
     * Verifies that internal buffer of the ByteToMessageDecoder is released once decoder is removed from pipeline. In
     * this case input was not fully read.
     */
    @Test
    public void testInternalBufferClearReadPartly() {
        final ByteBuf buf = Unpooled.buffer().writeBytes(new byte[] {'a', 'b'});
        EmbeddedChannel channel = newInternalBufferTestChannel();
        assertTrue(channel.writeInbound(buf));
        assertTrue(channel.finish());
        ByteBuf expected = Unpooled.wrappedBuffer(new byte[] {'b'});
        ByteBuf b = channel.readInbound();
        assertEquals(expected, b);
        assertNull(channel.readInbound());
        expected.release();
        b.release();
    }

    private EmbeddedChannel newInternalBufferTestChannel() {
        return new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                ByteBuf byteBuf = internalBuffer();
                assertEquals(1, byteBuf.refCnt());
                in.readByte();
                // Removal from pipeline should clear internal buffer
                ctx.pipeline().remove(this);
            }

            @Override
            protected void handlerRemoved0(ChannelHandlerContext ctx) {
                assertCumulationReleased(internalBuffer());
            }
        });
    }

    @Test
    public void handlerRemovedWillNotReleaseBufferIfDecodeInProgress() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                ctx.pipeline().remove(this);
                assertTrue(in.refCnt() != 0);
            }

            @Override
            protected void handlerRemoved0(ChannelHandlerContext ctx) {
                assertCumulationReleased(internalBuffer());
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(bytes)));
        assertTrue(channel.finishAndReleaseAll());
    }

    private static void assertCumulationReleased(ByteBuf byteBuf) {
        assertTrue("unexpected value: " + byteBuf,
                byteBuf == null || byteBuf == Unpooled.EMPTY_BUFFER || byteBuf.refCnt() == 0);
    }

    @Test
    public void testFireChannelReadCompleteOnInactive() throws InterruptedException {
        final BlockingQueue<Integer> queue = new LinkedBlockingDeque<>();
        final ByteBuf buf = Unpooled.buffer().writeBytes(new byte[] {'a', 'b'});
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                int readable = in.readableBytes();
                assertTrue(readable > 0);
                in.skipBytes(readable);
            }

            @Override
            protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                assertFalse(in.isReadable());
                out.add("data");
            }
        }, new ChannelInboundHandler() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                queue.add(3);
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                queue.add(1);
            }

            @Override
            public void channelReadComplete(ChannelHandlerContext ctx) {
                if (!ctx.channel().isActive()) {
                    queue.add(2);
                }
            }
        });
        assertFalse(channel.writeInbound(buf));
        channel.finish();
        assertEquals(1, (int) queue.take());
        assertEquals(2, (int) queue.take());
        assertEquals(3, (int) queue.take());
        assertTrue(queue.isEmpty());
    }

    // See https://github.com/netty/netty/issues/4635
    @Test
    public void testRemoveWhileInCallDecode() {
        final Object upgradeMessage = new Object();
        final ByteToMessageDecoder decoder = new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                assertEquals('a', in.readByte());
                out.add(upgradeMessage);
            }
        };

        EmbeddedChannel channel = new EmbeddedChannel(decoder, new ChannelInboundHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (msg == upgradeMessage) {
                    ctx.pipeline().remove(decoder);
                    return;
                }
                ctx.fireChannelRead(msg);
            }
        });

        ByteBuf buf = Unpooled.wrappedBuffer(new byte[] { 'a', 'b', 'c' });
        assertTrue(channel.writeInbound(buf.copy()));
        ByteBuf b = channel.readInbound();
        assertEquals(b, buf.skipBytes(1));
        assertFalse(channel.finish());
        buf.release();
        b.release();
    }

    @Test
    public void testDecodeLastEmptyBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                int readable = in.readableBytes();
                assertTrue(readable > 0);
                out.add(in.readBytes(readable));
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(bytes)));
        assertBuffer(Unpooled.wrappedBuffer(bytes), channel.readInbound());
        assertNull(channel.readInbound());
        assertFalse(channel.finish());
        assertNull(channel.readInbound());
    }

    @Test
    public void testDecodeLastNonEmptyBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            private boolean decodeLast;

            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
                int readable = in.readableBytes();
                assertTrue(readable > 0);
                if (!decodeLast && readable == 1) {
                    return;
                }
                out.add(in.readBytes(decodeLast ? readable : readable - 1));
            }

            @Override
            protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                assertFalse(decodeLast);
                decodeLast = true;
                super.decodeLast(ctx, in, out);
            }
        });
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);

        assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(bytes)));
        assertBuffer(Unpooled.wrappedBuffer(bytes, 0, bytes.length - 1), channel.readInbound());
        assertNull(channel.readInbound());
        assertTrue(channel.finish());
        assertBuffer(Unpooled.wrappedBuffer(bytes, bytes.length - 1, 1), channel.readInbound());
        assertNull(channel.readInbound());
    }

    private static void assertBuffer(ByteBuf expected, ByteBuf buffer) {
        try {
            assertEquals(expected, buffer);
        } finally {
            buffer.release();
            expected.release();
        }
    }

    @Test
    public void testReadOnlyBuffer() {
        EmbeddedChannel channel = new EmbeddedChannel(new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            }
        });
        assertFalse(channel.writeInbound(Unpooled.buffer(8).writeByte(1).asReadOnly()));
        assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(new byte[] { (byte) 2 })));
        assertFalse(channel.finish());
    }

    @Test
    public void releaseWhenMergeCumulateThrows() {
        final Error error = new Error();

        ByteBuf cumulation = new UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, 0, 64) {
            @Override
            public ByteBuf writeBytes(ByteBuf src) {
                throw error;
            }
        };
        ByteBuf in = Unpooled.buffer().writeZero(12);
        try {
            ByteToMessageDecoder.MERGE_CUMULATOR.cumulate(UnpooledByteBufAllocator.DEFAULT, cumulation, in);
            fail();
        } catch (Error expected) {
            assertSame(error, expected);
            assertEquals(0, in.refCnt());
        }
    }

    @Test
    public void releaseWhenCompositeCumulateThrows() {
        final Error error = new Error();

        ByteBuf cumulation = new CompositeByteBuf(UnpooledByteBufAllocator.DEFAULT, false, 64) {
            @Override
            public CompositeByteBuf addComponent(boolean increaseWriterIndex, ByteBuf buffer) {
                throw error;
            }
        };
        ByteBuf in = Unpooled.buffer().writeZero(12);
        try {
            ByteToMessageDecoder.COMPOSITE_CUMULATOR.cumulate(UnpooledByteBufAllocator.DEFAULT, cumulation, in);
            fail();
        } catch (Error expected) {
            assertSame(error, expected);
            assertEquals(0, in.refCnt());
        }
    }
}
