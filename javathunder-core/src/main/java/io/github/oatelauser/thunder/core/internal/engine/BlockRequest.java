package io.github.oatelauser.thunder.core.internal.engine;

/**
 * 一个待传输的 Block（16 KiB，末块取剩余长度）。
 */
public record BlockRequest(int pieceIndex, int begin, int length) {
}
