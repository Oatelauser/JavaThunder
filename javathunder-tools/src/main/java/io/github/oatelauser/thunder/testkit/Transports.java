package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.api.TorrentClient;

/**
 * 差分验收开关：系统属性 {@code javathunder.transport}=nio|blocking（默认 nio，
 * 与生产缺省一致；显式传 blocking 跑参照实现臂）。
 * 例：{@code mvn test -Djavathunder.transport=blocking}
 */
public final class Transports {

    private Transports() {
    }

    /**
     * 差分选择结果（api 级枚举，供 {@code TorrentClient.builder().transport(...)} 使用）。
     */
    public static TorrentClient.Transport select() {
        String name = System.getProperty("javathunder.transport", "nio");
        return "blocking".equalsIgnoreCase(name) ? TorrentClient.Transport.BLOCKING
                : TorrentClient.Transport.NIO;
    }

}
