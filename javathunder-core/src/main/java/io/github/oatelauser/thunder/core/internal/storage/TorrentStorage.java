package io.github.oatelauser.thunder.core.internal.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** 种子存储契约：单文件（StorageManager）与多文件（MultiFileStorage）共同面向引擎的接口。 */
public interface TorrentStorage extends AutoCloseable {

    void writeBlock(int pieceIndex, int begin, byte[] block) throws IOException;

    boolean verifyPiece(int pieceIndex) throws IOException;

    void clearPiece(int pieceIndex) throws IOException;

    byte[] readBlock(int pieceIndex, int begin, int length) throws IOException;

    void writePieceBuffers(int pieceIndex, ByteBuffer[] buffers) throws IOException;

    int pieceCount();

    int pieceLengthOf(int pieceIndex);

    void finish() throws IOException;

    Path partFile();

    Path finalFile();

    @Override
    void close() throws IOException;
}
