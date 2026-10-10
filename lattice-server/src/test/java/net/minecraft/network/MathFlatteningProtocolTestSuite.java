package net.minecraft.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MathFlatteningProtocolTestSuite {
    private static void originalWrite(ByteBuf buffer, long value) {
        while ((value & -128L) != 0L) {
            buffer.writeByte((int)(value & 127L) | 128);
            value >>>= 7;
        }
        buffer.writeByte((int)value);
    }

    private static void check(long value) {
        for (ByteOrder order : new ByteOrder[]{ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
            ByteBuf expected = Unpooled.buffer().order(order);
            ByteBuf actual = Unpooled.buffer().order(order);
            try {
                originalWrite(expected, value);
                assertSame(actual, VarLong.write(actual, value));
                assertEquals(expected, actual);
                assertEquals(expected.readableBytes(), VarLong.getByteSize(value));
                assertEquals(value, VarLong.read(actual));
            } finally {
                expected.release(); actual.release();
            }
        }
    }

    @Test
    void varLongMatchesOriginalAtAllBitBoundariesAndRandomValues() {
        check(0); check(-1); check(Long.MIN_VALUE); check(Long.MAX_VALUE);
        for (int bit = 0; bit < 63; bit++) {
            long value = 1L << bit;
            check(value - 1); check(value); check(value + 1); check(-value);
        }
        Random random = new Random(784324);
        for (int i = 0; i < 10000; i++) check(random.nextLong());
    }

    @Test
    void varLongPreservesPartialWritesOnCapacityFailure() {
        for (long value : new long[]{0, 127, 128, 16383, 16384, Long.MAX_VALUE, -1}) {
            for (int capacity = 0; capacity < VarLong.getByteSize(value); capacity++) {
                ByteBuf expected = Unpooled.buffer(capacity, capacity);
                ByteBuf actual = Unpooled.buffer(capacity, capacity);
                try {
                    assertThrows(IndexOutOfBoundsException.class, () -> originalWrite(expected, value));
                    assertThrows(IndexOutOfBoundsException.class, () -> VarLong.write(actual, value));
                    assertEquals(expected.writerIndex(), actual.writerIndex());
                    assertEquals(expected, actual);
                } finally {
                    expected.release(); actual.release();
                }
            }
        }
    }

    private static ChannelHandlerContext context(boolean active) {
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(active);
        ChannelHandlerContext context = mock(ChannelHandlerContext.class);
        when(context.channel()).thenReturn(channel);
        return context;
    }

    @Test
    void decoderPreservesFragmentResetAndIndependentPayload() {
        BandwidthDebugMonitor monitor = mock(BandwidthDebugMonitor.class);
        Varint21FrameDecoder decoder = new Varint21FrameDecoder(monitor);
        ByteBuf input = Unpooled.buffer();
        ArrayList<Object> output = new ArrayList<>();
        try {
            input.writeByte(99).readByte(); // Nonzero reader mark.
            input.writeByte(0x81);
            decoder.decode(context(true), input, output);
            assertEquals(1, input.readerIndex());
            input.writeByte(0);
            decoder.decode(context(true), input, output);
            assertEquals(1, input.readerIndex());
            verifyNoInteractions(monitor);
            input.writeByte(42);
            decoder.decode(context(true), input, output);
            assertEquals(4, input.readerIndex());
            verify(monitor).onReceive(2); // Preserve canonical-length accounting.
            ByteBuf payload = (ByteBuf)output.getFirst();
            input.setByte(3, 7);
            assertEquals(42, payload.readUnsignedByte());
            assertEquals(1, input.refCnt());
            payload.release();
        } finally { input.release(); }
    }

    @Test
    void decoderHandlesFullThreeByteLengthsAndEveryHeaderSplit() {
        for (int length : new int[]{128, 16384, 0x1fffff}) {
            ByteBuf input = Unpooled.buffer(length + 3);
            var decoder = new Varint21FrameDecoder(null);
            var output = new ArrayList<>();
            try {
                int rest = length;
                while ((rest & -128) != 0) {
                    input.writeByte((rest & 127) | 128); rest >>>= 7;
                    decoder.decode(context(true), input, output);
                    assertEquals(0, input.readerIndex()); assertTrue(output.isEmpty());
                }
                input.writeByte(rest);
                decoder.decode(context(true), input, output);
                assertEquals(0, input.readerIndex()); assertTrue(output.isEmpty());
                input.writeZero(length);
                decoder.decode(context(true), input, output);
                assertEquals(1, output.size());
                ByteBuf payload = (ByteBuf)output.getFirst();
                assertEquals(length, payload.readableBytes()); payload.release();
            } finally { input.release(); }
        }
    }

    @Test
    void decoderPreservesInvalidHeaderConsumptionAndInactiveDiscard() {
        for (byte[] bytes : new byte[][]{{0}, {(byte)0x80, 0}, {(byte)0x80, (byte)0x80, (byte)0x80, 1}}) {
            ByteBuf input = Unpooled.wrappedBuffer(bytes);
            try {
                var decoder = new Varint21FrameDecoder(null);
                assertThrows(CorruptedFrameException.class, () -> decoder.decode(context(true), input, new ArrayList<>()));
                assertEquals(Math.min(3, bytes.length), input.readerIndex());
            } finally { input.release(); }
        }
        ByteBuf input = Unpooled.wrappedBuffer(new byte[]{(byte)0x80, 0, 5});
        try {
            var out = new ArrayList<>();
            new Varint21FrameDecoder(null).decode(context(false), input, out);
            assertFalse(input.isReadable()); assertTrue(out.isEmpty());
        } finally { input.release(); }
    }
}
