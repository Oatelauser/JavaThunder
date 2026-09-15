package io.github.oatelauser.thunder.core.internal.wire;

/**
 * 未实现的消息 ID（如 BEP5 PORT=9、BEP6 Suggest=13/AllowedFast=17、BEP10 扩展=20）。
 * 解码容忍、引擎忽略——与真实客户端互操作的必要宽容。本方不发送。
 */
public record UnsupportedMessage(int id) implements PeerWireMessage {
}
