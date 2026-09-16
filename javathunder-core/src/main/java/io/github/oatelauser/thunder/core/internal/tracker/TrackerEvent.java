package io.github.oatelauser.thunder.core.internal.tracker;

/**
 * announce 的 event 参数（BEP 3）：周期性 announce 不携带该参数。
 */
public enum TrackerEvent {
    STARTED("started"),
    NONE(null),
    COMPLETED("completed"),
    STOPPED("stopped");

    private final String wireValue;

    TrackerEvent(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * @return null 表示本次 announce 不携带 event 参数。
     */
    public String wireValue() {
        return wireValue;
    }
}
