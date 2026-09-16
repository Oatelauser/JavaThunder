package io.github.oatelauser.thunder.core.internal.wire;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 期望帧字节按 BEP 3 的线格式手写（长度前缀大端 u32 + 消息 ID u8 + 载荷），
 * 与实现相互独立。
 */
class PeerWireCodecTest {

    private static ByteBuffer frame(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return ByteBuffer.wrap(data);
    }

    @Nested
    class HandshakeTest {

        @Test
        void encodesExactWireLayout() {
            byte[] infoHash = new byte[20];
            infoHash[0] = 1;
            byte[] peerId = "-JT0001-handshake001".getBytes(StandardCharsets.US_ASCII);

            byte[] wire = Handshake.encode(infoHash, peerId);

            assertEquals(68, wire.length);
            assertEquals(19, wire[0]);
            assertEquals("BitTorrent protocol",
                new String(wire, 1, 19, StandardCharsets.US_ASCII));
            for (int i = 20; i < 28; i++) {
                if (i != Handshake.EXTENSION_BIT_OFFSET) {
                    assertEquals(0, wire[i], "reserved bytes other than the capability byte must stay zero");
                }
            }
            assertEquals(Handshake.EXTENSION_BIT_MASK | Handshake.FAST_EXTENSION_BIT_MASK,
                wire[Handshake.EXTENSION_BIT_OFFSET],
                "reserved[5] must declare BEP 10 (0x10) + BEP 6 (0x04)");
            assertTrue(Handshake.supportsExtensions(wire), "our own handshake must declare BEP 10");
            assertTrue(Handshake.supportsFastExtension(wire), "our own handshake must declare BEP 6");
            assertEquals(infoHash[0], wire[28]);
            assertEquals('-', wire[48]);
            assertEquals('J', wire[49]);
        }

        @Test
        void decodesRoundTrip() {
            byte[] infoHash = new byte[20];
            for (int i = 0; i < 20; i++) {
                infoHash[i] = (byte) (0xA0 + i);
            }
            byte[] peerId = "-JT0001-handshake002".getBytes(StandardCharsets.US_ASCII);

            Handshake handshake = Handshake.decode(Handshake.encode(infoHash, peerId));

            assertArrayEquals(infoHash, handshake.infoHash());
            assertArrayEquals(peerId, handshake.peerId());
        }

        @Test
        void rejectsMalformedHandshakes() {
            byte[] infoHash = new byte[20];
            byte[] peerId = new byte[20];
            assertThrows(PeerWireException.class, () -> Handshake.decode(new byte[67]));
            byte[] badPstr = Handshake.encode(infoHash, peerId);
            badPstr[5] = 'X'; // 破坏 "BitTorrent protocol"
            assertThrows(PeerWireException.class, () -> Handshake.decode(badPstr));
            byte[] badPstrlen = Handshake.encode(infoHash, peerId);
            badPstrlen[0] = 18;
            assertThrows(PeerWireException.class, () -> Handshake.decode(badPstrlen));
        }
    }

    @Nested
    class EncodeTest {

        @Test
        void keepAliveIsFourZeroBytes() {
            assertArrayEquals(new byte[]{0, 0, 0, 0}, PeerWireCodec.encode(KeepAlive.INSTANCE));
        }

        @Test
        void singleByteMessagesUseTheirIds() {
            assertArrayEquals(new byte[]{0, 0, 0, 1, 0}, PeerWireCodec.encode(Choke.INSTANCE));
            assertArrayEquals(new byte[]{0, 0, 0, 1, 1}, PeerWireCodec.encode(Unchoke.INSTANCE));
            assertArrayEquals(new byte[]{0, 0, 0, 1, 2}, PeerWireCodec.encode(Interested.INSTANCE));
            assertArrayEquals(new byte[]{0, 0, 0, 1, 3}, PeerWireCodec.encode(NotInterested.INSTANCE));
        }

        @Test
        void haveCarriesBigEndianPieceIndex() {
            assertArrayEquals(new byte[]{0, 0, 0, 5, 4, 0, 0, 0, 42},
                PeerWireCodec.encode(new Have(42)));
        }

        @Test
        void bitfieldIsOpaqueBytes() {
            byte[] bits = {(byte) 0xC0, 0x00};
            assertArrayEquals(new byte[]{0, 0, 0, 3, 5, (byte) 0xC0, 0x00},
                PeerWireCodec.encode(new BitfieldMessage(bits)));
        }

        @Test
        void requestAndCancelShareLayout() {
            byte[] expected = {0, 0, 0, 13, 6, 0, 0, 1, 1, 0, 0, 2, 2, 0, 1, 0, 0};
            assertArrayEquals(expected, PeerWireCodec.encode(new Request(257, 514, 65536)));
            expected[4] = 8;
            assertArrayEquals(expected, PeerWireCodec.encode(new Cancel(257, 514, 65536)));
        }

        @Test
        void pieceCarriesBinaryBlock() {
            byte[] block = {(byte) 0xFF, 0x00, 0x7A};
            byte[] expected = {0, 0, 0, 12, 7, 0, 0, 3, 3, 0, 0, 0, 16, (byte) 0xFF, 0x00, 0x7A};
            assertArrayEquals(expected, PeerWireCodec.encode(new PieceMessage(771, 16, block)));
        }
    }

    @Nested
    class DecodeTest {

        @Test
        void decodeRoundTripsEveryMessageType() {
            PeerWireMessage[] samples = {
                KeepAlive.INSTANCE, Choke.INSTANCE, Unchoke.INSTANCE,
                Interested.INSTANCE, NotInterested.INSTANCE,
                new Have(1023),
                new BitfieldMessage(new byte[]{(byte) 0x80, 0x40}),
                new Request(5, 4096, 16384),
                new PieceMessage(5, 4096, new byte[]{1, 2, 3}),
                new Cancel(6, 0, 16384),
            };
            for (PeerWireMessage sample : samples) {
                assertEquals(sample, PeerWireCodec.decodeFrame(ByteBuffer.wrap(PeerWireCodec.encode(sample))));
            }
        }

        @Test
        void decodeFrameConsumesExactlyOneFrameAndLeavesPositionAfterIt() {
            byte[] frame = PeerWireCodec.encode(new Have(9));
            ByteBuffer buf = ByteBuffer.allocate(frame.length + 1);
            buf.put(frame).put((byte) 0xEE); // 附加字节属于"下一帧"
            buf.flip();
            assertEquals(new Have(9), PeerWireCodec.decodeFrame(buf));
            assertEquals(1, buf.remaining());
        }

        @Test
        void rejectsUnknownMessageId() {
            // 语义已变更：未知 ID 不再断连（互操作容忍），见 unknownIdsAreToleratedNotFatal
            assertDoesNotThrow(
                () -> PeerWireCodec.decodeFrame(frame(0, 0, 0, 1, 9)));
        }

        @Test
        void rejectsOversizedLengthPrefix() {
            // 声明 4MB 帧：远超 16KiB 块的合法上限，防内存炸弹
            assertThrows(PeerWireException.class,
                () -> PeerWireCodec.decodeFrame(frame(0x00, 0x40, 0x00, 0x00, 7)));
        }

        @Test
        void rejectsTruncatedPayload() {
            assertThrows(PeerWireException.class,
                () -> PeerWireCodec.decodeFrame(frame(0, 0, 0, 5, 4, 0, 0)));
        }

        @Test
        void rejectsHaveWithWrongPayloadSize() {
            assertThrows(PeerWireException.class,
                () -> PeerWireCodec.decodeFrame(frame(0, 0, 0, 6, 4, 0, 0, 0, 1, 0)));
        }

        @Test
        void rejectsRequestWithWrongPayloadSize() {
            assertThrows(PeerWireException.class,
                () -> PeerWireCodec.decodeFrame(frame(0, 0, 0, 13, 6, 0, 0, 0, 1, 0, 0, 2, 2, 0, 1, 0)));
        }

        @Test
        void rejectsPiecePayloadShorterThanHeader() {
            assertThrows(PeerWireException.class,
                () -> PeerWireCodec.decodeFrame(frame(0, 0, 0, 5, 7, 0, 0, 0, 1)));
        }

        @Test
        void bep6MessagesDecode() {
            assertEquals(HaveAll.INSTANCE, PeerWireCodec.decodeFrame(frame(0, 0, 0, 1, 14)));
            assertEquals(HaveNone.INSTANCE, PeerWireCodec.decodeFrame(frame(0, 0, 0, 1, 15)));
            assertEquals(new RejectRequest(1, 2, 3),
                PeerWireCodec.decodeFrame(frame(0, 0, 0, 13, 16, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3)));
        }

        @Test
        void unknownIdsAreToleratedNotFatal() {
            // 真实客户端会发未实现的 ID（BEP5 PORT=9、未知名=21/250）：
            // 容忍解码为 UnsupportedMessage，由引擎忽略——绝不断连
            assertEquals(new UnsupportedMessage(21),
                PeerWireCodec.decodeFrame(frame(0, 0, 0, 5, 21, 1, 2, 3, 4, 5)));
            assertEquals(new UnsupportedMessage(9),
                PeerWireCodec.decodeFrame(frame(0, 0, 0, 3, 9, 0x1F, (byte) 0x90)));
            assertEquals(new UnsupportedMessage(250),
                PeerWireCodec.decodeFrame(frame(0, 0, 0, 2, (byte) 250, 0, 0)));
        }

        @Test
        void bep6SuggestAndAllowedFastRoundTrip() {
            // Suggest（ID 13）与 AllowedFast（ID 17）：载荷 = piece 序号 u32
            assertEquals(new SuggestPiece(7),
                PeerWireCodec.decodeFrame(frame(0, 0, 0, 5, 13, 0, 0, 0, 7)));
            assertArrayEquals(new byte[]{0, 0, 0, 5, 13, 0, 0, 0, 7},
                PeerWireCodec.encode(new SuggestPiece(7)));
            assertEquals(new AllowedFast(3),
                PeerWireCodec.decodeFrame(frame(0, 0, 0, 5, 17, 0, 0, 0, 3)));
            assertArrayEquals(new byte[]{0, 0, 0, 5, 17, 0, 0, 0, 3},
                PeerWireCodec.encode(new AllowedFast(3)));
        }

        @Test
        void bep10ExtendedMessageRoundTrip() {
            byte[] payload = new byte[]{100, 53, 58, 109, 115, 103}; // "d5:msg" 片段即可
            // 线格式：长度前缀 = id(1) + sub-id(1) + payload(6) = 8
            ExtendedMessage decoded = (ExtendedMessage) PeerWireCodec.decodeFrame(
                frame(0, 0, 0, 8, 20, 3, 100, 53, 58, 109, 115, 103));
            assertEquals(3, decoded.extendedId());
            assertArrayEquals(payload, decoded.payload());
            assertArrayEquals(new byte[]{0, 0, 0, 8, 20, 3, 100, 53, 58, 109, 115, 103},
                PeerWireCodec.encode(decoded));
        }

        @Test
        void bep10ExtendedMessageRequiresSubId() {
            // 只有 id 无 sub-id 的 id-20 帧是协议违规，解码必须报错而非产脏值
            assertThrows(PeerWireException.class,
                () -> PeerWireCodec.decodeFrame(frame(0, 0, 0, 1, 20)));
        }

        @Test
        void bep6SinglesEncode() {
            assertArrayEquals(new byte[]{0, 0, 0, 1, 14}, PeerWireCodec.encode(HaveAll.INSTANCE));
            assertArrayEquals(new byte[]{0, 0, 0, 1, 15}, PeerWireCodec.encode(HaveNone.INSTANCE));
        }
    }
}
