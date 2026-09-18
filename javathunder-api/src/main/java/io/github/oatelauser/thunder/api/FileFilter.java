package io.github.oatelauser.thunder.api;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多文件种子的文件取舍谓词（选择性下载）：返回 true 的文件参与下载与完成判定，
 * 其余文件不请求、不校验。单文件种子等价于对唯一文件名的一次判定。
 *
 * <p>路径约定：{@code path} 为种子内相对路径组件（多文件种子不含根目录名，如
 * {@code ["docs", "readme.txt"]}；单文件种子即文件名本身，如 {@code ["model.bin"]}）。
 * 磁力路径在元数据就绪后按同一约定求值——过滤器写的是路径形状，与元数据来源无关。
 *
 * <p>边界（v1 多文件）：Piece 覆盖拼接字节流，可能同时压住想要的与不想要的文件——
 * 这类跨界 Piece 仍会整件下载（包含少量不需要的字节），属协议粒度所限，与主流
 * 客户端行为一致；完全不落在想要文件上的 Piece 不会请求。被过滤文件在磁盘上以
 * 稀疏占位存在（不做种时）。
 */
@FunctionalInterface
public interface FileFilter {

    /** 是否下载该文件。 */
    boolean wanted(List<String> path);

    /** 全量下载（默认——不设过滤器时的行为，与历史版本完全一致）。 */
    static FileFilter all() {
        return path -> true;
    }

    /**
     * 按种子内相对路径精确匹配（多文件不含根目录名；分隔符 {@code /}），
     * 如 {@code FileFilter.paths("docs/readme.txt", "img/cover.png")}。
     */
    static FileFilter paths(String... paths) {
        Set<String> wanted = Set.of(paths).stream()
                .map(p -> p.startsWith("/") ? p.substring(1) : p)
                .collect(Collectors.toUnmodifiableSet());
        return path -> wanted.contains(String.join("/", path));
    }

    /**
     * 按扩展名匹配（大小写不敏感，不含点），如
     * {@code FileFilter.extensions("iso", "zip")}。
     */
    static FileFilter extensions(String... extensions) {
        Set<String> suffixes = Set.of(extensions).stream()
                .map(e -> e.startsWith(".") ? e.substring(1) : e)
                .map(e -> e.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return path -> {
            String name = path.isEmpty() ? "" : path.get(path.size() - 1);
            int dot = name.lastIndexOf('.');
            return dot >= 0 && dot < name.length() - 1
                    && suffixes.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        };
    }
}
