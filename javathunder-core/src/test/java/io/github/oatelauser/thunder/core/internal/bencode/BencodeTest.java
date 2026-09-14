package io.github.oatelauser.thunder.core.internal.bencode;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 期望值来自 BEP 3 规范中的字面示例与编码规则，属独立事实来源。
 */
class BencodeTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class RoundTrips {

        @Test
        void integerFromSpecLiteral() {
            assertEquals(new BInteger(42), Bencode.decode(utf8("i42e")));
            assertEquals(new BInteger(0), Bencode.decode(utf8("i0e")));
            assertEquals(new BInteger(-17), Bencode.decode(utf8("i-17e")));
            assertEquals(Long.MAX_VALUE, ((BInteger) Bencode.decode(utf8("i9223372036854775807e"))).value());
        }

        @Test
        void byteStringFromSpecLiteral() {
            assertEquals(new BString(utf8("hello")), Bencode.decode(utf8("5:hello")));
            assertEquals(new BString(new byte[0]), Bencode.decode(utf8("0:")));
            // 任意二进制字节都合法（种子里的 SHA-1 段是纯二进制）
            byte[] binary = {'3', ':', 0x00, (byte) 0xFF, 0x7F};
            assertEquals(new BString(new byte[]{0x00, (byte) 0xFF, 0x7F}), Bencode.decode(binary));
        }

        @Test
        void listFromSpecLiteral() {
            String spec = "l4:spami42ee"; // BEP 3: ["spam", 42]
            assertEquals(
                new BList(List.of(new BString(utf8("spam")), new BInteger(42))),
                Bencode.decode(utf8(spec)));
        }

        @Test
        void dictFromSpecLiteral() {
            String spec = "d3:bar4:spam3:fooi42ee"; // BEP 3: {"bar":"spam","foo":42}
            Map<BString, BencodeValue> expected = new TreeMap<>(BString.UNSIGNED_ORDER);
            expected.put(BString.of("bar"), new BString(utf8("spam")));
            expected.put(BString.of("foo"), new BInteger(42));
            assertEquals(new BDict(expected), Bencode.decode(utf8(spec)));
        }

        @Test
        void encodeMatchesSpecCanonicalForm() {
            assertEquals("i42e", new String(Bencode.encode(new BInteger(42)), StandardCharsets.UTF_8));
            assertEquals("5:hello", new String(Bencode.encode(new BString(utf8("hello"))), StandardCharsets.UTF_8));
            assertEquals("l4:spami42ee",
                new String(Bencode.encode(new BList(List.of(new BString(utf8("spam")), new BInteger(42)))),
                    StandardCharsets.UTF_8));
        }

        @Test
        void decodeThenEncodeIsIdentityForCanonicalInput() {
            byte[] canonical = utf8("d3:bar4:spam3:fooi42ee");
            assertArrayEquals(canonical, Bencode.encode(Bencode.decode(canonical)));
        }
    }

    @Nested
    class Rejections {

        @Test
        void integerMalformations() {
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("i01e")));  // 前导零
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("i-0e")));  // 负零
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("ie")));    // 空数字体
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("i12")));   // 缺 e
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("i+12e"))); // 不允许 +
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("i9223372036854775808e"))); // 溢出 long
        }

        @Test
        void stringMalformations() {
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("05:x")));  // 长度前导零
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("5:foo"))); // 截断
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("x")));     // 非法起始字节
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8(":foo")));  // 缺长度
        }

        @Test
        void structureMalformations() {
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("")));          // 空输入
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("i42eZ")));     // 尾部垃圾
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("d3:foo")));    // dict 值缺失
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("di42ei1ee"))); // 键必须是字节串
        }

        @Test
        void depthBombRejected() {
            byte[] bomb = utf8("l".repeat(100) + "e".repeat(100));
            assertThrows(BencodeException.class, () -> Bencode.decode(bomb));
        }

        @Test
        void oversizedStringLengthRejectedBeforeAllocation() {
            // 声明 17MB 串但输入只有几字节：必须在分配前拒绝
            assertThrows(BencodeException.class, () -> Bencode.decode(utf8("17825792:x")));
        }
    }

    @Nested
    class CanonicalEncoding {

        @Test
        void dictKeysSortedOnEncodeEvenIfInputUnordered() {
            Map<BString, BencodeValue> unordered = new TreeMap<>(BString.UNSIGNED_ORDER.reversed());
            unordered.put(BString.of("zz"), new BInteger(1));
            unordered.put(BString.of("aa"), new BInteger(2));
            byte[] encoded = Bencode.encode(new BDict(unordered));
            assertEquals("d2:aai2e2:zzi1ee", new String(encoded, StandardCharsets.UTF_8));
        }

        @Test
        void decodeToleratesUnsortedDictKeys() {
            // 现实世界的种子不总按键序输出，解码必须容忍
            BDict dict = (BDict) Bencode.decode(utf8("d2:zzi1e2:aai2ee"));
            assertEquals(new BInteger(2), dict.value().get(BString.of("aa")));
        }

        @Test
        void unsignedByteOrderingForBinaryKeys() {
            // 0xFF 无符号应排在 0x01 之后；带符号比较会得到相反结果
            Map<BString, BencodeValue> m = new TreeMap<>(BString.UNSIGNED_ORDER);
            m.put(new BString(new byte[]{(byte) 0xFF}), new BInteger(1));
            m.put(new BString(new byte[]{0x01}), new BInteger(2));
            byte[] encoded = Bencode.encode(new BDict(m));
            byte[] expected = {'d', '1', ':', 0x01, 'i', '2', 'e', '1', ':', (byte) 0xFF, 'i', '1', 'e', 'e'};
            assertArrayEquals(expected, encoded);
        }
    }
}
