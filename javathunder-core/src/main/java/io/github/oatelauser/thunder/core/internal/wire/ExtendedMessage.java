package io.github.oatelauser.thunder.core.internal.wire;

/**
 * BEP 10 扩展消息（消息 ID 20，载荷 = 扩展子 ID u8 + bencoded 字典）。
 * 子 ID 0 = 扩展握手（声明各扩展的消息子 ID 与 metadata_size）；
 * 其余子 ID 由扩展握手协商出的 m 字典决定（如 ut_metadata）。
 */
public record ExtendedMessage(int extendedId, byte[] payload) implements PeerWireMessage {

    public ExtendedMessage {
        payload = payload.clone();
    }
}
