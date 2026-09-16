package io.github.oatelauser.thunder.core.internal.wire;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

/**
 * 6 字节紧凑 Peer 地址编解码（4 字节 IPv4 点分十进制 + 2 字节大端端口）：
 * HTTP tracker 应答（BEP 3 compact peers）、PEX added 紧凑表（BEP 11）与 UDP tracker
 * 应答（BEP 15）共用同一线上格式，此为唯一实现。
 */
public final class CompactPeer {

    private CompactPeer() {
    }

    /** 从字节数组的指定偏移解出一个地址（调用方保证 [offset, offset+6) 可读且长度对齐 6）。 */
    public static InetSocketAddress decode6(byte[] data, int offset) {
        String host = (data[offset] & 0xFF) + "." + (data[offset + 1] & 0xFF) + "."
                + (data[offset + 2] & 0xFF) + "." + (data[offset + 3] & 0xFF);
        int port = ((data[offset + 4] & 0xFF) << 8) | (data[offset + 5] & 0xFF);
        return new InetSocketAddress(host, port);
    }

    /** 从 ByteBuffer 的绝对偏移解出一个地址（UDP 应答场景，不移动 position）。 */
    public static InetSocketAddress decode6(ByteBuffer buf, int offset) {
        String host = (buf.get(offset) & 0xFF) + "." + (buf.get(offset + 1) & 0xFF) + "."
                + (buf.get(offset + 2) & 0xFF) + "." + (buf.get(offset + 3) & 0xFF);
        int port = ((buf.get(offset + 4) & 0xFF) << 8) | (buf.get(offset + 5) & 0xFF);
        return new InetSocketAddress(host, port);
    }

    /**
     * 编码为 6 字节紧凑表；非 IPv4 地址或未解析地址返回 null——调用方自行跳过
     * （PEX 的 IPv6 需要 added6 字段，本库暂不支持）。
     */
    public static byte @Nullable [] encode6(InetSocketAddress address) {
        if (address.getAddress() == null) {
            return null;
        }
        byte[] ip = address.getAddress().getAddress();
        if (ip.length != 4) {
            return null;
        }
        return new byte[]{
                ip[0], ip[1], ip[2], ip[3],
                (byte) (address.getPort() >> 8),
                (byte) address.getPort()
        };
    }
}
