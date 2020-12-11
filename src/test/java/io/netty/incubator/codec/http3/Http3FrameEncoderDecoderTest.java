/*
 * Copyright 2020 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.incubator.codec.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.incubator.codec.quic.QuicChannel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.concurrent.ThreadLocalRandom;

import static io.netty.incubator.codec.http3.Http3TestUtils.assertException;
import static io.netty.incubator.codec.http3.Http3TestUtils.mockParent;
import static io.netty.incubator.codec.http3.Http3TestUtils.verifyClose;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(Parameterized.class)
public class Http3FrameEncoderDecoderTest {

    @Parameterized.Parameters(name = "{index}: fragmented = {0}")
    public static Object[] parameters() {
        return new Object[] { false, true };
    }

    private final boolean fragmented;

    public Http3FrameEncoderDecoderTest(boolean fragmented) {
        this.fragmented = fragmented;
    }

    protected ChannelOutboundHandler newEncoder() {
        return new Http3FrameEncoder(new QpackEncoder());
    }

    protected ChannelInboundHandler newDecoder() {
        return new Http3FrameDecoder(new QpackDecoder(), Long.MAX_VALUE);
    }

    @Test
    public void testHttp3CancelPushFrame_63() {
        testFrameEncodedAndDecoded(new DefaultHttp3CancelPushFrame(63));
    }

    @Test
    public void testHttp3CancelPushFrame_16383() {
        testFrameEncodedAndDecoded(new DefaultHttp3CancelPushFrame(16383));
    }

    @Test
    public void testHttp3CancelPushFrame_1073741823() {
        testFrameEncodedAndDecoded(new DefaultHttp3CancelPushFrame(1073741823));
    }

    @Test
    public void testHttp3CancelPushFrame_4611686018427387903() {
        testFrameEncodedAndDecoded(new DefaultHttp3CancelPushFrame(4611686018427387903L));
    }

    @Test
    public void testHttp3DataFrame() {
        byte[] bytes = new byte[1024];
        ThreadLocalRandom.current().nextBytes(bytes);
        testFrameEncodedAndDecoded(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(bytes)));
    }

    @Test
    public void testHttp3GoAwayFrame_63() {
        testFrameEncodedAndDecoded(new DefaultHttp3GoAwayFrame(63));
    }

    @Test
    public void testHttp3GoAwayFrame_16383() {
        testFrameEncodedAndDecoded(new DefaultHttp3GoAwayFrame(16383));
    }

    @Test
    public void testHttp3GoAwayFrame_1073741823() {
        testFrameEncodedAndDecoded(new DefaultHttp3GoAwayFrame(1073741823));
    }

    @Test
    public void testHttp3MaxPushIdFrame_63() {
        testFrameEncodedAndDecoded(new DefaultHttp3MaxPushIdFrame(63));
    }

    @Test
    public void testHttp3MaxPushIdFrame_16383() {
        testFrameEncodedAndDecoded(new DefaultHttp3MaxPushIdFrame(16383));
    }

    @Test
    public void testHttp3MaxPushIdFrame_1073741823() {
        testFrameEncodedAndDecoded(new DefaultHttp3MaxPushIdFrame(1073741823));
    }

    @Test
    public void testHttp3SettingsFrame() {
        Http3SettingsFrame settingsFrame = new DefaultHttp3SettingsFrame();
        settingsFrame.put(Http3Constants.SETTINGS_QPACK_MAX_TABLE_CAPACITY, 100L);
        settingsFrame.put(Http3Constants.SETTINGS_QPACK_BLOCKED_STREAMS, 1L);
        settingsFrame.put(Http3Constants.SETTINGS_MAX_FIELD_SECTION_SIZE, 128L);
        // Ensure we can encode and decode all sizes correctly.
        settingsFrame.put(63, 63L);
        settingsFrame.put(16383, 16383L);
        settingsFrame.put(1073741823, 1073741823L);
        settingsFrame.put(4611686018427387903L, 4611686018427387903L);
        testFrameEncodedAndDecoded(settingsFrame);
    }

    @Test
    public void testHttp3HeadersFrame() {
        Http3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        addRequestHeaders(headersFrame.headers());
        testFrameEncodedAndDecoded(headersFrame);
    }

    @Test
    public void testHttp3PushPromiseFrame() {
        Http3PushPromiseFrame pushPromiseFrame = new DefaultHttp3PushPromiseFrame(9);
        addRequestHeaders(pushPromiseFrame.headers());
        testFrameEncodedAndDecoded(pushPromiseFrame);
    }

    @Test
    public void testHttp3UnknownFrame() {
        testFrameEncodedAndDecoded(new DefaultHttp3UnknownFrame(Http3CodecUtils.MIN_RESERVED_FRAME_TYPE,
                Unpooled.buffer().writeLong(8)));
    }

    // Reserved types that were used in HTTP/2 and should close the connection with an error
    @Test
    public void testDecodeReserved0x2() {
        testDecodeReserved(0x2);
    }

    @Test
    public void testDecodeReserved0x6() {
        testDecodeReserved(0x6);
    }

    @Test
    public void testDecodeReserved0x8() {
        testDecodeReserved(0x8);
    }

    @Test
    public void testDecodeReserved0x9() {
        testDecodeReserved(0x9);
    }

    private void testDecodeReserved(long type) {
        QuicChannel parent = mockParent();

        EmbeddedChannel decoderChannel = new EmbeddedChannel(parent, DefaultChannelId.newInstance(),
                true, false, newDecoder());
        ByteBuf buffer = Unpooled.buffer();
        Http3CodecUtils.writeVariableLengthInteger(buffer, type);

        try {
            decoderChannel.writeInbound(buffer);
        } catch (Exception e) {
            assertException(Http3ErrorCode.H3_FRAME_UNEXPECTED, e);
        }
        verifyClose(Http3ErrorCode.H3_FRAME_UNEXPECTED, parent);
        assertFalse(decoderChannel.finish());
    }

    @Test
    public void testEncodeReserved0x2() {
        testEncodeReserved(0x2);
    }

    @Test
    public void testEncodeReserved0x6() {
        testEncodeReserved(0x6);
    }

    @Test
    public void testEncodeReserved0x8() {
        testEncodeReserved(0x8);
    }

    @Test
    public void testEncodeReserved0x9() {
        testEncodeReserved(0x9);
    }

    private void testEncodeReserved(long type) {
        QuicChannel parent = mockParent();

        EmbeddedChannel encoderChannel = new EmbeddedChannel(parent, DefaultChannelId.newInstance(),
                true, false, newEncoder());
        Http3UnknownFrame frame = mock(Http3UnknownFrame.class);
        when(frame.type()).thenReturn(type);
        when(frame.touch()).thenReturn(frame);
        when(frame.touch(any())).thenReturn(frame);
        try {
            encoderChannel.writeOutbound(frame);
        } catch (Exception e) {
            assertException(Http3ErrorCode.H3_FRAME_UNEXPECTED, e);
        }
        // should have released the frame as well
        verify(frame, times(1)).release();
        verifyClose(Http3ErrorCode.H3_FRAME_UNEXPECTED, parent);
        assertFalse(encoderChannel.finish());
    }

    private static void addRequestHeaders(Http3Headers headers) {
        headers.add(":authority", "netty.quic"); // name only
        headers.add(":path", "/"); // name & value
        headers.add(":method", "GET"); // name & value with few options per name
        headers.add("x-qpack-draft", "19");
    }

    private void testFrameEncodedAndDecoded(Http3Frame frame) {
        EmbeddedChannel encoderChannel = new EmbeddedChannel(newEncoder());
        EmbeddedChannel decoderChannel = new EmbeddedChannel(newDecoder());

        assertTrue(encoderChannel.writeOutbound(retainAndDuplicate(frame)));
        ByteBuf buffer = encoderChannel.readOutbound();
        if (fragmented) {
            do {
                ByteBuf slice = buffer.readRetainedSlice(
                        ThreadLocalRandom.current().nextInt(buffer.readableBytes() + 1));
                assertEquals(!buffer.isReadable(), decoderChannel.writeInbound(slice));
            } while (buffer.isReadable());
            buffer.release();
        } else {
            assertTrue(decoderChannel.writeInbound(buffer));
        }
        Http3Frame readFrame = decoderChannel.readInbound();
        Http3TestUtils.assertFrameEquals(frame, readFrame);
        assertFalse(encoderChannel.finish());
        assertFalse(decoderChannel.finish());
    }

    private static Http3Frame retainAndDuplicate(Http3Frame frame) {
        if (frame instanceof ByteBufHolder) {
            return (Http3Frame) ((ByteBufHolder) frame).retainedDuplicate();
        }
        return frame;
    }
}
