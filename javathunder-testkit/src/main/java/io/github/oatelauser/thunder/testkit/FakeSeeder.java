package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.core.internal.peer.PeerConnection;
import io.github.oatelauser.thunder.core.internal.storage.Bitfield;
import io.github.oatelauser.thunder.core.internal.tracker.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.BitfieldMessage;
import io.github.oatelauser.thunder.core.internal.wire.Cancel;
import io.github.oatelauser.thunder.core.internal.wire.Have;
import io.github.oatelauser.thunder.core.internal.wire.Interested;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;
import io.github.oatelauser.thunder.core.internal.wire.Unchoke;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 已知良好的种子方：全量位图、立即 unchoke、按文件分块读应答 request（D2：内存与文件大小无关）。 */
public final class FakeSeeder implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final java.nio.channels.FileChannel content;
    private final TorrentMetadata meta;
    private final byte[] peerId = PeerIds.generate();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private FakeSeeder(ServerSocket serverSocket, java.nio.channels.FileChannel content,
                       TorrentMetadata meta) {
        this.serverSocket = serverSocket;
        this.content = content;
        this.meta = meta;
    }

    public static FakeSeeder start(Path contentFile, TorrentMetadata meta) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 64, java.net.InetAddress.getLoopbackAddress());
        FakeSeeder seeder = new FakeSeeder(serverSocket,
            java.nio.channels.FileChannel.open(contentFile, java.nio.file.StandardOpenOption.READ),
            meta);
        seeder.threads.submit(seeder::acceptLoop);
        return seeder;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void announceTo(EmbeddedTracker tracker) {
        tracker.register(meta.infoHash(), port());
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                threads.submit(() -> serve(socket));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void serve(Socket socket) {
        try (PeerConnection connection = PeerConnection.accept(socket, meta.infoHash(), peerId)) {
            Bitfield all = new Bitfield(meta.pieceCount());
            for (int i = 0; i < meta.pieceCount(); i++) {
                all.set(i);
            }
            connection.write(new BitfieldMessage(all.toBytes()));
            connection.write(Unchoke.INSTANCE);
            while (running.get()) {
                PeerWireMessage message = connection.read();
                if (message instanceof Request request) {
                    connection.write(new PieceMessage(request.pieceIndex(), request.begin(),
                        readBlock(request)));
                }
                // Interested / Have / Cancel / KeepAlive 一律忽略（已 unchoke）
            }
        } catch (IOException | RuntimeException e) {
            // 客户端断开，正常
        }
    }

    private byte[] readBlock(Request request) throws IOException {
        long offset = request.pieceIndex() * meta.pieceLength() + request.begin();
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(request.length());
        while (buffer.hasRemaining()) {
            if (content.read(buffer, offset + buffer.position()) < 0) {
                throw new IOException("unexpected eof at " + (offset + buffer.position()));
            }
        }
        return buffer.array();
    }

    /** 多文件形态：目录树按拼接流顺序复制进一个临时文件后分块读（D2：内存有界）。 */
    public static FakeSeeder startMultiFile(TorrentMetadata meta, Path rootDir) throws IOException {
        Path concatenatedFile = java.nio.file.Files.createTempFile("javathunder-multifile-", ".bin");
        try (java.nio.channels.FileChannel out = java.nio.channels.FileChannel.open(concatenatedFile,
            java.nio.file.StandardOpenOption.WRITE)) {
            for (TorrentMetadata.TorrentFile file : meta.files()) {
                Path path = rootDir;
                for (String component : file.path()) {
                    path = path.resolve(component);
                }
                if (file.length() > 0) {
                    out.transferFrom(java.nio.channels.FileChannel.open(path,
                        java.nio.file.StandardOpenOption.READ), out.size(), file.length());
                }
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(concatenatedFile);
            } catch (IOException ignored) {
            }
        }));
        ServerSocket serverSocket = new ServerSocket(0, 64, java.net.InetAddress.getLoopbackAddress());
        FakeSeeder seeder = new FakeSeeder(serverSocket,
            java.nio.channels.FileChannel.open(concatenatedFile, java.nio.file.StandardOpenOption.READ),
            meta);
        seeder.threads.submit(seeder::acceptLoop);
        return seeder;
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        try {
            content.close();
        } catch (IOException ignored) {
        }
        threads.shutdownNow();
    }
}
