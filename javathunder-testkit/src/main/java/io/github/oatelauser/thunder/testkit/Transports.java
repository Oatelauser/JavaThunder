package io.github.oatelauser.thunder.testkit;

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

    public static Function<byte[], PeerTransport> fromSystemProperty() {
        String name = System.getProperty("javathunder.transport", "blocking");
        if ("nio".equalsIgnoreCase(name)) {
            return NioTransport::new;
        }
        return BlockingTransport::new;
    }

}
