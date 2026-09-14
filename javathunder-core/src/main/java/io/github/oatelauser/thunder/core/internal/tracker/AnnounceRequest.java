package io.github.oatelauser.thunder.core.internal.tracker;

/** 一次 HTTP announce 的请求参数（BEP 3 §trackers）。 */
public record AnnounceRequest(
    byte[] infoHash,
    byte[] peerId,
    int port,
    long uploaded,
    long downloaded,
    long left,
    TrackerEvent event,
    int numwant) {

    public AnnounceRequest {
        if (infoHash.length != 20 || peerId.length != 20) {
            throw new IllegalArgumentException("info-hash and peer-id must be 20 bytes");
        }
    }
}
