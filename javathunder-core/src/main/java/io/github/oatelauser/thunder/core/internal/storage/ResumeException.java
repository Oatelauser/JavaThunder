package io.github.oatelauser.thunder.core.internal.storage;

/** Resume 状态文件不可用（损坏、版本不符、不属于当前种子）。调用方据此从零开始。 */
public class ResumeException extends RuntimeException {

    public ResumeException(String message) {
        super(message);
    }
}
