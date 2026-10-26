package io.github.simdpaddleocr.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/**
 * Raw FFM handles for the C ABI in {@code native/simdpaddleocr.h}.
 *
 * <p>Struct layouts mirror the header exactly and are only valid for 64-bit
 * targets, which is all the Java binding supports.
 */
public final class NativeApi {

    /** {@code ocr_line}: 8 + 4 * 5 + 4 * 9 = 64 bytes. */
    public static final StructLayout LINE = MemoryLayout.structLayout(
            ADDRESS.withName("text"),
            JAVA_INT.withName("textLength"),
            JAVA_FLOAT.withName("recognitionScore"),
            JAVA_FLOAT.withName("classificationScore"),
            JAVA_INT.withName("rotationDegrees"),
            JAVA_INT.withName("emittedCount"),
            JAVA_FLOAT.withName("x1"), JAVA_FLOAT.withName("y1"),
            JAVA_FLOAT.withName("x2"), JAVA_FLOAT.withName("y2"),
            JAVA_FLOAT.withName("x3"), JAVA_FLOAT.withName("y3"),
            JAVA_FLOAT.withName("x4"), JAVA_FLOAT.withName("y4"),
            JAVA_FLOAT.withName("detectionScore"));

    /** {@code ocr_result}: pointer + 4 ints = 24 bytes. */
    public static final StructLayout RESULT = MemoryLayout.structLayout(
            ADDRESS.withName("lines"),
            JAVA_INT.withName("count"),
            JAVA_INT.withName("detectedCount"),
            JAVA_INT.withName("detectorResizedWidth"),
            JAVA_INT.withName("detectorResizedHeight"));

    public static final long LINE_SIZE = LINE.byteSize();
    public static final long RESULT_SIZE = RESULT.byteSize();

    public final MethodHandle createInDir;
    public final MethodHandle run;
    public final MethodHandle resultFree;
    public final MethodHandle destroy;
    public final MethodHandle version;
    public final MethodHandle lastError;

    public NativeApi(SymbolLookup lookup) {
        Linker linker = Linker.nativeLinker();
        createInDir = linker.downcallHandle(symbol(lookup, "ocr_create_in_dir"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        run = linker.downcallHandle(symbol(lookup, "ocr_run"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        resultFree = linker.downcallHandle(symbol(lookup, "ocr_result_free"),
                FunctionDescriptor.ofVoid(ADDRESS));
        destroy = linker.downcallHandle(symbol(lookup, "ocr_destroy"),
                FunctionDescriptor.ofVoid(ADDRESS));
        version = linker.downcallHandle(symbol(lookup, "ocr_version"),
                FunctionDescriptor.of(ADDRESS));
        lastError = linker.downcallHandle(symbol(lookup, "ocr_last_error"),
                FunctionDescriptor.of(ADDRESS));
    }

    private static java.lang.foreign.MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(
                "simdpaddleocr native library does not export " + name));
    }
}
