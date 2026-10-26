package io.github.simdpaddleocr;

/** Raised when a native call returns a non-zero status or the library cannot be loaded. */
public final class OcrException extends RuntimeException {

    private final int status;

    public OcrException(int status, String message) {
        super(message);
        this.status = status;
    }

    public OcrException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    /** Native status code: -1 bad argument, -2 model load failed, -3 run failed. */
    public int status() {
        return status;
    }
}
