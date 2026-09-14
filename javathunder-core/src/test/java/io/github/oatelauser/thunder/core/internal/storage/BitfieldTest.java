package io.github.oatelauser.thunder.core.internal.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BEP 3 位序：piece 0 是首字节最高位（MSB-first），尾部空闲位必须为零。 */
class BitfieldTest {

    @Test
    void setsBitsInMsbFirstOrder() {
        Bitfield bitfield = new Bitfield(10);
        assertEquals(2, bitfield.toBytes().length); // ceil(10/8)

        bitfield.set(0);
        assertArrayEquals(new byte[]{(byte) 0x80, 0x00}, bitfield.toBytes());

        bitfield.set(7); // 首字节的最低位
        assertArrayEquals(new byte[]{(byte) 0x81, 0x00}, bitfield.toBytes());

        bitfield.set(8); // 第二字节最高位
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x80}, bitfield.toBytes());

        bitfield.set(9);
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0xC0}, bitfield.toBytes());
    }

    @Test
    void roundTripsThroughWireBytes() {
        Bitfield bitfield = new Bitfield(16);
        bitfield.set(1);
        bitfield.set(15);
        Bitfield restored = Bitfield.fromBytes(bitfield.toBytes(), 16);
        assertEquals(bitfield, restored);
        assertTrue(restored.has(1));
        assertTrue(restored.has(15));
        assertFalse(restored.has(0));
    }

    @Test
    void fromBytesRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class,
            () -> Bitfield.fromBytes(new byte[]{1}, 16));
    }

    @Test
    void fromBytesRejectsNonZeroPaddingBits() {
        // 10 个 piece：第二个字节的低 6 位是空闲位，0x20 表示 bit 10 被置位 → 非法
        assertThrows(IllegalArgumentException.class,
            () -> Bitfield.fromBytes(new byte[]{(byte) 0x80, 0x20}, 10));
        // 0x40 对应 piece 9（合法），0x3F 全部越界（非法）
        assertThrows(IllegalArgumentException.class,
            () -> Bitfield.fromBytes(new byte[]{(byte) 0x80, 0x3F}, 10));
        Bitfield ok = Bitfield.fromBytes(new byte[]{(byte) 0x80, 0x40}, 10);
        assertTrue(ok.has(9));
    }

    @Test
    void countsAndCompletion() {
        Bitfield bitfield = new Bitfield(3);
        assertEquals(0, bitfield.cardinality());
        assertFalse(bitfield.allSet());
        bitfield.set(0);
        bitfield.set(2);
        assertEquals(2, bitfield.cardinality());
        assertFalse(bitfield.allSet());
        bitfield.set(1);
        assertTrue(bitfield.allSet());
        assertEquals(3, bitfield.cardinality());
    }

    @Test
    void clearRemovesPiece() {
        Bitfield bitfield = new Bitfield(8);
        bitfield.set(3);
        bitfield.clear(3);
        assertFalse(bitfield.has(3));
        assertEquals(0, bitfield.cardinality());
    }

    @Test
    void indexBoundsAreEnforced() {
        Bitfield bitfield = new Bitfield(8);
        assertThrows(IndexOutOfBoundsException.class, () -> bitfield.set(8));
        assertThrows(IndexOutOfBoundsException.class, () -> bitfield.has(-1));
        assertThrows(IllegalArgumentException.class, () -> Bitfield.fromBytes(new byte[1], 0));
    }
}
