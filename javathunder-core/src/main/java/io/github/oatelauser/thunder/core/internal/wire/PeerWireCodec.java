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
        return decodeMessage(buf.get() & 0xFF, (int) length - 1, buf);
    }

    /** 按消息 ID（BEP 3）解码载荷；长度前缀与整帧完整性已由 {@link #decodeFrame} 校验。 */
    private static PeerWireMessage decodeMessage(int id, int payloadLength, ByteBuffer buf) {
        return switch (id) {
            case 0 -> requireLength(payloadLength, 0, Choke.INSTANCE);
            case 1 -> requireLength(payloadLength, 0, Unchoke.INSTANCE);
            case 2 -> requireLength(payloadLength, 0, Interested.INSTANCE);
            case 3 -> requireLength(payloadLength, 0, NotInterested.INSTANCE);
            case 4 -> decodeHave(payloadLength, buf);
            case 5 -> decodeBitfield(payloadLength, buf);
            case 6 -> {
                requireExact(payloadLength, 12, "request");
                yield new Request(buf.getInt(), buf.getInt(), buf.getInt());
            }
            case 7 -> decodePiece(payloadLength, buf);
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
            case 20 -> decodeExtended(payloadLength, buf);
            default -> {
                byte[] payload = new byte[payloadLength];
                buf.get(payload); // 未实现的 ID：吞掉载荷，容忍解码
                yield new UnsupportedMessage(id);
            }
        };
    }

    /** have：4 字节 piece 序号。 */
    private static PeerWireMessage decodeHave(int payloadLength, ByteBuffer buf) {
        requireExact(payloadLength, 4, "have");
        return new Have(buf.getInt());
    }

    /** bitfield：载荷即原始位图字节，位数与 piece 数的对齐由上层校验。 */
    private static PeerWireMessage decodeBitfield(int payloadLength, ByteBuffer buf) {
        byte[] bits = new byte[payloadLength];
        buf.get(bits);
        return new BitfieldMessage(bits);
    }

    /** piece：8 字节头（piece 序号 + 块内偏移）之后是整个 Block 数据。 */
    private static PeerWireMessage decodePiece(int payloadLength, ByteBuffer buf) {
        requireAtLeast(payloadLength, 8, "piece");
        int pieceIndex = buf.getInt();
        int begin = buf.getInt();
        byte[] block = new byte[payloadLength - 8];
        buf.get(block);
        return new PieceMessage(pieceIndex, begin, block);
    }

    /** 扩展消息（BEP 10）：首字节为握手协商出的子 ID，其后是 bencoded 载荷。 */
    private static PeerWireMessage decodeExtended(int payloadLength, ByteBuffer buf) {
        if (payloadLength < 1) {
            throw new PeerWireException("extended message requires a sub-id byte");
        }
        int extendedId = buf.get() & 0xFF; // 子 ID 紧跟消息 ID
        byte[] payload = new byte[payloadLength - 1]; // 其后是 bencoded 字典
        buf.get(payload);
        return new ExtendedMessage(extendedId, payload);
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
