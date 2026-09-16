package io.github.oatelauser.thunder.core.internal.storage;

import java.util.Arrays;

/**
 * Piece 完成位图（BEP 3 bitfield 语义）：piece 0 对应首字节最高位，
 * 尾部空闲位恒为零。用于进度跟踪、线协议 Bitfield 消息与 Resume 状态文件。
 */
public final class Bitfield {

    private final int pieceCount;
    private final byte[] bits;

    public Bitfield(int pieceCount) {
        if (pieceCount <= 0) {
            throw new IllegalArgumentException("pieceCount must be positive: " + pieceCount);
        }
        this.pieceCount = pieceCount;
        this.bits = new byte[(pieceCount + 7) / 8];
    }

    /** 全部位置 1 的位图（BEP 6 HaveAll 的等价表示）；尾部空闲位仍保持为零。 */
    public static Bitfield allSet(int pieceCount) {
        Bitfield bitfield = new Bitfield(pieceCount);
        Arrays.fill(bitfield.bits, (byte) 0xFF);
        int validBitsInLastByte = pieceCount - (bitfield.bits.length - 1) * 8;
        if (validBitsInLastByte < 8) {
            int paddingMask = (1 << (8 - validBitsInLastByte)) - 1;
            bitfield.bits[bitfield.bits.length - 1] &= (byte) ~paddingMask;
        }
        return bitfield;
    }

    /** 从线协议字节恢复；字节数必须恰为 ⌈N/8⌉ 且空闲位为零。 */
    public static Bitfield fromBytes(byte[] bytes, int pieceCount) {
        if (pieceCount <= 0) {
            throw new IllegalArgumentException("pieceCount must be positive: " + pieceCount);
        }
        int expected = (pieceCount + 7) / 8;
        if (bytes.length != expected) {
            throw new IllegalArgumentException("bitfield must be " + expected
                + " bytes for " + pieceCount + " pieces, got " + bytes.length);
        }
        int validBitsInLastByte = pieceCount - (expected - 1) * 8;
        if (validBitsInLastByte < 8) {
            int paddingMask = (1 << (8 - validBitsInLastByte)) - 1;
            if ((bytes[expected - 1] & paddingMask) != 0) {
                throw new IllegalArgumentException("bitfield padding bits must be zero");
            }
        }
        Bitfield bitfield = new Bitfield(pieceCount);
        System.arraycopy(bytes, 0, bitfield.bits, 0, expected);
        return bitfield;
    }

    public boolean has(int index) {
        requireInRange(index);
        return (bits[index >> 3] & (0x80 >> (index & 7))) != 0;
    }

    public void set(int index) {
        requireInRange(index);
        bits[index >> 3] |= (byte) (0x80 >> (index & 7));
    }

    public void clear(int index) {
        requireInRange(index);
        bits[index >> 3] &= (byte) ~(0x80 >> (index & 7));
    }

    public int size() {
        return pieceCount;
    }

    public int cardinality() {
        int count = 0;
        for (int i = 0; i < pieceCount; i++) {
            if (has(i)) {
                count++;
            }
        }
        return count;
    }

    public boolean allSet() {
        return cardinality() == pieceCount;
    }

    public byte[] toBytes() {
        return bits.clone();
    }

    private void requireInRange(int index) {
        if (index < 0 || index >= pieceCount) {
            throw new IndexOutOfBoundsException("piece index " + index + " out of [0," + pieceCount + ")");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Bitfield other
            && pieceCount == other.pieceCount && Arrays.equals(bits, other.bits);
    }

    @Override
    public int hashCode() {
        return 31 * pieceCount + Arrays.hashCode(bits);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Bitfield[");
        for (int i = 0; i < pieceCount; i++) {
            sb.append(has(i) ? '1' : '0');
        }
        return sb.append(']').toString();
    }
}
