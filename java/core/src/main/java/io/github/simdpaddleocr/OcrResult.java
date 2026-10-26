package io.github.simdpaddleocr;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One pipeline run.
 *
 * @param detectedCount  boxes the detector found before recognition
 * @param detectorWidth  detector input width after the internal resize
 * @param detectorHeight detector input height after the internal resize
 * @param lines          recognized lines, in reading order
 */
public record OcrResult(int detectedCount, int detectorWidth, int detectorHeight, List<OcrLine> lines) {

    /** Joins every line with {@code \n}. */
    public String text() {
        return lines.stream().map(OcrLine::text).collect(Collectors.joining("\n"));
    }

    /**
     * @param recognitionScore passed through from the engine and <em>not</em>
     *                         normalized to 0..1, so do not use it as a
     *                         probability threshold
     * @param box              quad in source pixels, clockwise from the top-left
     */
    public record OcrLine(String text,
                          float recognitionScore,
                          float classificationScore,
                          int rotationDegrees,
                          int emittedCount,
                          Box box,
                          float detectionScore) {
    }

    public record Box(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4) {
    }
}
