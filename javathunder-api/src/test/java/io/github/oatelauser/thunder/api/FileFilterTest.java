package io.github.oatelauser.thunder.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文件过滤器的判定语义（路径约定 / 扩展名大小写 / 精确匹配不含根目录名）。
 */
class FileFilterTest {

    @Test
    void allAcceptsEverything() {
        assertTrue(FileFilter.all().wanted(List.of("anything.bin")));
        assertTrue(FileFilter.all().wanted(List.of("a", "b", "c")));
    }

    @Test
    void pathsMatchExactlyWithoutRootPrefix() {
        FileFilter filter = FileFilter.paths("docs/readme.txt", "img/cover.png");
        assertTrue(filter.wanted(List.of("docs", "readme.txt")), "多组件按 / 拼接精确匹配");
        assertTrue(filter.wanted(List.of("img", "cover.png")));
        assertFalse(filter.wanted(List.of("docs", "other.txt")));
        assertFalse(filter.wanted(List.of("readme.txt")), "不含根目录名——裸文件名不匹配子路径");
    }

    @Test
    void extensionsAreCaseInsensitive() {
        FileFilter filter = FileFilter.extensions("ISO", ".zip");
        assertTrue(filter.wanted(List.of("ubuntu.ISO")), "扩展名大小写不敏感");
        assertTrue(filter.wanted(List.of("data.zip")));
        assertFalse(filter.wanted(List.of("data.tar.gz")), "只看最后一段扩展名");
        assertFalse(filter.wanted(List.of("noext")));
        assertFalse(filter.wanted(List.of(".hidden")), "只有点没有扩展名不算");
    }

    @Test
    void customPredicateComposes() {
        FileFilter onlySmallText = path -> path.size() == 1 && path.get(0).endsWith(".txt");
        assertTrue(onlySmallText.wanted(List.of("a.txt")));
        assertFalse(onlySmallText.wanted(List.of("dir", "a.txt")));
    }
}
