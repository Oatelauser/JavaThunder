package io.github.oatelauser.thunder.testkit;

import io.github.oatelauser.thunder.core.internal.metainfo.TorrentMetadata;
import io.github.oatelauser.thunder.tracker.EmbeddedTracker;
import io.github.oatelauser.thunder.core.internal.peer.PeerConnection;
import io.github.oatelauser.thunder.core.internal.peer.PeerIds;
import io.github.oatelauser.thunder.core.internal.wire.PeerWireMessage;
import io.github.oatelauser.thunder.core.internal.wire.PieceMessage;
import io.github.oatelauser.thunder.core.internal.wire.Request;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * 已知良好的种子方：全量位图、立即 unchoke、按文件分块读应答 request（D2：内存与文件大小无关）。
 * 记录按到达序的 Request 件号（{@link #requestedPieceOrder}）——顺序下载类验收用它
 * 断言客户端的请求序（比完成事件序更强也更真实：完成序受并发校验影响可局部翻转）。
 */
public final class FakeSeeder implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final FileChannel content;
    private final TorrentMetadata meta;
    private final byte[] peerId = PeerIds.generate();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Integer> requestedPieceOrder = new CopyOnWriteArrayList<>();

    private FakeSeeder(ServerSocket serverSocket, FileChannel content, TorrentMetadata meta) {
        this.serverSocket = serverSocket;
        this.content = content;
        this.meta = meta;
    }

    public static FakeSeeder start(Path contentFile, TorrentMetadata meta) throws IOException {
        ServerSocket serverSocket = new ServerSocket(0, 64, InetAddress.getLoopbackAddress());
        FakeSeeder seeder = new FakeSeeder(serverSocket, FileChannel.open(contentFile, READ), meta);
        seeder.threads.submit(seeder::acceptLoop);
        return seeder;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public void announceTo(EmbeddedTracker tracker) {
        tracker.register(meta.infoHash(), port());
    }

    /** 按到达序的 Request 件号（跨连接汇总；断言用首现序，见类注释）。 */
    public List<Integer> requestedPieceOrder() {
        return List.copyOf(requestedPieceOrder);
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
            for (PeerWireMessage hello : SeederCore.seederHello(meta)) {
                connection.write(hello);
            }
            while (running.get()) {
                PeerWireMessage message = connection.read();
                if (message instanceof Request request) {
                    requestedPieceOrder.add(request.pieceIndex());
                    connection.write(new PieceMessage(request.pieceIndex(), request.begin(),
                            SeederCore.readBlock(content, meta, request)));
                }
                // Interested / Have / Cancel / KeepAlive 一律忽略（已 unchoke）
            }
        } catch (IOException | RuntimeException e) {
            // 客户端断开，正常
        }
    }

    /**
     * 多文件形态：目录树按拼接流顺序复制进一个临时文件后分块读（D2：内存有界）。
     */
    public static FakeSeeder startMultiFile(TorrentMetadata meta, Path rootDir) throws IOException {
        Path concatenatedFile = Files.createTempFile("javathunder-multifile-", ".bin");
        try (FileChannel out = FileChannel.open(concatenatedFile, WRITE)) {
            for (TorrentMetadata.TorrentFile file : meta.files()) {
                Path path = rootDir;
                for (String component : file.path()) {
                    path = path.resolve(component);
                }
                if (file.length() > 0) {
                    out.transferFrom(FileChannel.open(path, READ), out.size(), file.length());
                }
            }
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(concatenatedFile);
            } catch (IOException ignored) {
            }
        }));
        return start(concatenatedFile, meta);
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
