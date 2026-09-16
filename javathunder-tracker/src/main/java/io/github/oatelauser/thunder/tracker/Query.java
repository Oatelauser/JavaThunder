package io.github.oatelauser.thunder.tracker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** raw query-string 解析（%XX → 原始字节）；同名键（如多次 info_hash）聚合为列表。 */
final class Query {

    private Query() {
    }

    static Map<String, List<byte[]>> parse(@Nullable String rawQuery) {
        Map<String, List<byte[]>> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            params.computeIfAbsent(new String(decode(pair.substring(0, eq)), StandardCharsets.UTF_8),
                    k -> new ArrayList<>()).add(decode(pair.substring(eq + 1)));
        }
        return params;
    }

    static @Nullable byte[] first(Map<String, List<byte[]>> params, String key) {
        List<byte[]> values = params.get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    static @Nullable String textOf(@Nullable byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.US_ASCII);
    }

    /** left=0 → seeder；缺失或非法按 leecher 处理。 */
    static boolean seederByLeft(@Nullable byte[] leftBytes) {
        if (leftBytes == null) {
            return false;
        }
        try {
            return Long.parseLong(new String(leftBytes, StandardCharsets.US_ASCII)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static byte[] decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '%') {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                out.write(s.charAt(i));
                i++;
            }
        }
        return out.toByteArray();
    }
}
