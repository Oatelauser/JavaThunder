package io.github.oatelauser.thunder.core.internal.tracker;

/** Tracker 通信失败（HTTP 错误、响应不可解析、网络故障）。 */
public class TrackerException extends RuntimeException {

    public TrackerException(String message) {
        super(message);
    }

    public TrackerException(String message, Throwable cause) {
        super(message, cause);
    }
}
