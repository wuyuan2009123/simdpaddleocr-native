package io.github.simdpaddleocr;

import io.github.simdpaddleocr.internal.NativeApi;
import io.github.simdpaddleocr.internal.NativeLoader;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

/**
 * One OCR engine: model graphs plus their thread-safe inference state.
 *
 * <p>Instances are safe to share between threads; concurrent {@link #run} calls
 * execute in parallel. {@link #close()} must not race with an in-flight
 * {@link #run}, so shut the engine down after your callers have stopped.
 *
 * <pre>{@code
 * try (OcrEngine engine = OcrEngine.openInDir("models", "small", null)) {
 *     OcrResult result = engine.run(bgra, width, height);
 *     System.out.println(result.text());
 * }
 * }</pre>
 */
public final class OcrEngine implements AutoCloseable {

    private static final class Holder {
        static final NativeApi API = new NativeApi(NativeLoader.lookup());
    }

    /** Interleaved pixel layouts accepted by {@link #run(MemorySegment, int, int, int, PixelFormat)}. */
    public enum PixelFormat {
        BGR24(0, 3),
        RGB24(1, 3),
        BGRA32(2, 4),
        RGBA32(3, 4);

        private final int code;
        private final int bytesPerPixel;

        PixelFormat(int code, int bytesPerPixel) {
            this.code = code;
            this.bytesPerPixel = bytesPerPixel;
        }

        int code() {
            return code;
        }

        public int bytesPerPixel() {
            return bytesPerPixel;
        }
    }

    private final MemorySegment handle;
    private final String variant;
    private volatile boolean closed;

    private OcrEngine(MemorySegment handle, String variant) {
        this.handle = handle;
        this.variant = variant;
    }

    /**
     * Loads the models laid out like the upstream {@code models/} folder:
     * {@code tiny} (also {@code null}/blank), {@code small} or {@code medium},
     * plus {@code cls.onnx} when it exists.
     *
     * @param optionsJson optional engine options, see the header; may be null
     */
    public static OcrEngine openInDir(String modelsDir, String variant, String optionsJson) {
        String normalized = variant == null || variant.isBlank() ? "tiny" : variant;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dir = arena.allocateFrom(modelsDir);
            MemorySegment variantArg = arena.allocateFrom(normalized);
            MemorySegment options = optionsJson == null || optionsJson.isBlank()
                    ? MemorySegment.NULL
                    : arena.allocateFrom(optionsJson);

            MemorySegment result;
            try {
                result = (MemorySegment) Holder.API.createInDir.invokeExact(dir, variantArg, options);
            } catch (Throwable t) {
                throw asRuntime(t);
            }
            if (result.address() == 0) {
                throw new OcrException(-2, "ocr_create_in_dir failed: " + lastError());
            }
            return new OcrEngine(result, normalized);
        }
    }

    /** Copies {@code pixels} into native memory and runs the pipeline. */
    public OcrResult run(byte[] pixels, int width, int height, PixelFormat format) {
        int stride = Math.multiplyExact(width, format.bytesPerPixel());
        if (pixels.length < (long) stride * height) {
            throw new IllegalArgumentException("pixels hold " + pixels.length
                    + " bytes, need " + (long) stride * height + " for " + width + "x" + height);
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(pixels.length);
            MemorySegment.copy(pixels, 0, segment, ValueLayout.JAVA_BYTE, 0, pixels.length);
            return run(segment, width, height, stride, format);
        }
    }

    /**
     * Runs DET + CLS + REC over packed pixels without copying them.
     *
     * @param stride row pitch in bytes; 0 means {@code width * bytesPerPixel}
     */
    public OcrResult run(MemorySegment pixels, int width, int height, int stride, PixelFormat format) {
        if (closed) {
            throw new IllegalStateException("engine is closed");
        }
        int pitch = stride == 0 ? Math.multiplyExact(width, format.bytesPerPixel()) : stride;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment result = arena.allocate(NativeApi.RESULT_SIZE);
            int status;
            try {
                status = (int) Holder.API.run.invokeExact(handle, pixels, width, height, pitch, format.code(), result);
            } catch (Throwable t) {
                throw asRuntime(t);
            }
            if (status != 0) {
                throw new OcrException(status, "ocr_run failed: " + lastError());
            }
            try {
                return read(result);
            } finally {
                try {
                    Holder.API.resultFree.invokeExact(result);
                } catch (Throwable t) {
                    throw asRuntime(t);
                }
            }
        }
    }

    /** Library version reported by the native side, e.g. {@code 2.0.0}. */
    public String nativeVersion() {
        try {
            return cString((MemorySegment) Holder.API.version.invokeExact());
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    /** Model variant this engine was created with. */
    public String variant() {
        return variant;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            Holder.API.destroy.invokeExact(handle);
        } catch (Throwable t) {
            throw asRuntime(t);
        }
    }

    private static OcrResult read(MemorySegment result) {
        int count = result.get(ValueLayout.JAVA_INT, 8);
        int detected = result.get(ValueLayout.JAVA_INT, 12);
        int detWidth = result.get(ValueLayout.JAVA_INT, 16);
        int detHeight = result.get(ValueLayout.JAVA_INT, 20);
        if (count == 0) {
            return new OcrResult(detected, detWidth, detHeight, java.util.List.of());
        }

        MemorySegment lines = result.get(ValueLayout.ADDRESS, 0).reinterpret(count * NativeApi.LINE_SIZE);
        java.util.List<OcrResult.OcrLine> parsed = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            MemorySegment line = lines.asSlice(i * NativeApi.LINE_SIZE, NativeApi.LINE_SIZE);
            MemorySegment textPointer = line.get(ValueLayout.ADDRESS, 0);
            int textLength = line.get(ValueLayout.JAVA_INT, 8);
            String text = textLength == 0 ? ""
                    : new String(textPointer.reinterpret(textLength).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
            parsed.add(new OcrResult.OcrLine(
                    text,
                    line.get(ValueLayout.JAVA_FLOAT, 12),
                    line.get(ValueLayout.JAVA_FLOAT, 16),
                    line.get(ValueLayout.JAVA_INT, 20),
                    line.get(ValueLayout.JAVA_INT, 24),
                    new OcrResult.Box(
                            line.get(ValueLayout.JAVA_FLOAT, 28), line.get(ValueLayout.JAVA_FLOAT, 32),
                            line.get(ValueLayout.JAVA_FLOAT, 36), line.get(ValueLayout.JAVA_FLOAT, 40),
                            line.get(ValueLayout.JAVA_FLOAT, 44), line.get(ValueLayout.JAVA_FLOAT, 48),
                            line.get(ValueLayout.JAVA_FLOAT, 52), line.get(ValueLayout.JAVA_FLOAT, 56)),
                    line.get(ValueLayout.JAVA_FLOAT, 60)));
        }
        return new OcrResult(detected, detWidth, detHeight, java.util.List.copyOf(parsed));
    }

    private static String lastError() {
        try {
            return cString((MemorySegment) Holder.API.lastError.invokeExact());
        } catch (Throwable t) {
            return "unavailable";
        }
    }

    private static String cString(MemorySegment pointer) {
        if (pointer == null || pointer.address() == 0) {
            return "";
        }
        return pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static RuntimeException asRuntime(Throwable t) {
        if (t instanceof RuntimeException runtime) {
            return runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new OcrException(-1, t.getMessage(), t);
    }
}
