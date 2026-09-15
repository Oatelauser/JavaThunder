package io.github.oatelauser.thunder.core.internal.wire;

import java.nio.ByteBuffer;

/**
 * 线协议帧编解码（BEP 3）：4 字节大端长度前缀 + 1 字节消息 ID + 载荷。
 *
 * <p>帧上限 {@value #MAX_FRAME_BYTES}：合法 Block 请求恒为 16KiB（BEP 3），
 * 上限给到 128KiB 以容忍个别客户端的更大请求，同时阻断内存炸弹。
 */
public final class PeerWireCodec {

    public static final int MAX_FRAME_BYTES = 128 * 1024;
    private static final int LENGTH_PREFIX = 4;

    private PeerWireCodec() {
    }

    public static byte[] encode(PeerWireMessage message) {
        return switch (message) {
            case KeepAlive k -> new byte[4];
            case Choke c -> single(0);
            case Unchoke u -> single(1);
            case Interested i -> single(2);
            case NotInterested n -> single(3);
            case Have h -> frame(4, 4, buf -> buf.putInt(h.pieceIndex()));
            case BitfieldMessage b -> frame(5, b.bits().length, buf -> buf.put(b.bits()));
            case Request r -> frame(6, 12, buf ->
                buf.putInt(r.pieceIndex()).putInt(r.begin()).putInt(r.length()));
            case PieceMessage p -> frame(7, 8 + p.block().length, buf ->
                buf.putInt(p.pieceIndex()).putInt(p.begin()).put(p.block()));
            case Cancel c -> frame(8, 12, buf ->
                buf.putInt(c.pieceIndex()).putInt(c.begin()).putInt(c.length()));
            case HaveAll h -> single(14);
            case HaveNone h -> single(15);
            case RejectRequest r -> frame(16, 12, buf ->
                buf.putInt(r.pieceIndex()).putInt(r.begin()).putInt(r.length()));
            case ExtendedMessage e -> frame(20, 1 + e.payload().length, buf ->
                buf.put((byte) e.extendedId()).put(e.payload()));
            case UnsupportedMessage u -> throw new PeerWireException(
                "cannot encode unsupported message id " + u.id());
        };
    }

    /** 从缓冲区当前位置解码一帧，结束后位置停在该帧之后。 */
    public static PeerWireMessage decodeFrame(ByteBuffer buf) {
        if (buf.remaining() < LENGTH_PREFIX) {
            throw new PeerWireException("frame length prefix truncated");
        }
        long length = buf.getInt() & 0xFFFFFFFFL;
        if (length == 0) {
            return KeepAlive.INSTANCE;
        }
        if (length > MAX_FRAME_BYTES) {
            throw new PeerWireException("frame of " + length + " bytes exceeds limit " + MAX_FRAME_BYTES);
        }
        if (buf.remaining() < length) {
            throw new PeerWireException("frame payload truncated: declared " + length
                + ", available " + buf.remaining());
        }
        int id = buf.get() & 0xFF;
        int payloadLength = (int) length - 1;
        return switch (id) {
            case 0 -> requireLength(payloadLength, 0, Choke.INSTANCE);
            case 1 -> requireLength(payloadLength, 0, Unchoke.INSTANCE);
            case 2 -> requireLength(payloadLength, 0, Interested.INSTANCE);
            case 3 -> requireLength(payloadLength, 0, NotInterested.INSTANCE);
            case 4 -> {
                requireExact(payloadLength, 4, "have");
                yield new Have(buf.getInt());
            }
            case 5 -> {
                byte[] bits = new byte[payloadLength];
                buf.get(bits);
                yield new BitfieldMessage(bits);
            }
            case 6 -> {
                requireExact(payloadLength, 12, "request");
                yield new Request(buf.getInt(), buf.getInt(), buf.getInt());
            }
            case 7 -> {
                requireAtLeast(payloadLength, 8, "piece");
                int pieceIndex = buf.getInt();
                int begin = buf.getInt();
                byte[] block = new byte[payloadLength - 8];
                buf.get(block);
                yield new PieceMessage(pieceIndex, begin, block);
            }
            case 8 -> {
                requireExact(payloadLength, 12, "cancel");
                yield new Cancel(buf.getInt(), buf.getInt(), buf.getInt());
            }
            case 14 -> requireLength(payloadLength, 0, HaveAll.INSTANCE);
            case 15 -> requireLength(payloadLength, 0, HaveNone.INSTANCE);
            case 16 -> {
                requireExact(payloadLength, 12, "reject");
                yield new RejectRequest(buf.getInt(), buf.getInt(), buf.getInt());
            }
            case 20 -> {
                if (payloadLength < 1) {
                    throw new PeerWireException("extended message requires a sub-id byte");
                }
                int extendedId = buf.get() & 0xFF; // 子 ID 紧跟消息 ID
                byte[] payload = new byte[payloadLength - 1]; // 其后是 bencoded 字典
                buf.get(payload);
                yield new ExtendedMessage(extendedId, payload);
            }
            default -> {
                byte[] payload = new byte[payloadLength];
                buf.get(payload); // 未实现的 ID：吞掉载荷，容忍解码
                yield new UnsupportedMessage(id);
            }
        };
    }

    private static byte[] single(int id) {
        return new byte[]{0, 0, 0, 1, (byte) id};
    }

    private interface PayloadWriter {
        void write(ByteBuffer buf);
    }

    private static byte[] frame(int id, int payloadLength, PayloadWriter writer) {
        ByteBuffer buf = ByteBuffer.allocate(LENGTH_PREFIX + 1 + payloadLength);
        buf.putInt(1 + payloadLength);
        buf.put((byte) id);
        writer.write(buf);
        return buf.array();
    }

    private static <T extends PeerWireMessage> T requireLength(int actual, int expected, T message) {
        requireExact(actual, expected, message.getClass().getSimpleName());
        return message;
    }

    private static void requireExact(int actual, int expected, String what) {
        if (actual != expected) {
            throw new PeerWireException(what + " payload must be " + expected + " bytes, got " + actual);
        }
    }

    private static void requireAtLeast(int actual, int minimum, String what) {
        if (actual < minimum) {
            throw new PeerWireException(what + " payload must be at least " + minimum + " bytes, got " + actual);
        }
    }
}
