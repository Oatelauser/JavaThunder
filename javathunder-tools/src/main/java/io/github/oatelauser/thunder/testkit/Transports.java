package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.TorrentClient;
import io.github.oatelauser.thunder.core.internal.peer.transport.BlockingTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.NioTransport;
import io.github.oatelauser.thunder.core.internal.peer.transport.PeerTransport;

import java.util.function.Function;

/**
 * 差分验收开关：系统属性 {@code javathunder.transport}=nio|blocking（默认 blocking）。
 * 例：{@code mvn test -Djavathunder.transport=nio}
 */
public final class Transports {

    private Transports() {
    }

    /** 差分选择结果（api 级枚举，供 {@code TorrentClient.builder().transport(...)} 使用）。 */
    public static TorrentClient.Transport select() {
        String name = System.getProperty("javathunder.transport", "blocking");
        return "nio".equalsIgnoreCase(name) ? TorrentClient.Transport.NIO
                : TorrentClient.Transport.BLOCKING;
    }

    /**
     * 差分选择结果（传输工厂形态）。
     * @deprecated 暴露 core 内部类型；改用 {@link #select()} 配合 api 级
     *     {@code TorrentClient.Builder.transport(Transport)}。保留是为 0.3.0 基线的
     *     二进制兼容，将在下一个 minor 版本移除。
     */
    @Deprecated
    public static Function<byte[], PeerTransport> fromSystemProperty() {
        return select() == TorrentClient.Transport.NIO
                ? NioTransport::new
                : BlockingTransport::new;
    }

}
