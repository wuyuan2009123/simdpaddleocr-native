/*
 * simdpaddleocr.h - flat C ABI for Sdcb.SimdPaddleOCR (NativeAOT shared library).
 *
 * Build output names:
 *   Windows   simdpaddleocr.dll      (x64 / x86 / arm64)
 *   Linux     libsimdpaddleocr.so
 *   macOS     libsimdpaddleocr.dylib
 *
 * Calling convention is __cdecl on every platform.
 * Every function is thread-safe. A single ocr_engine may be used concurrently
 * from multiple threads; each thread sees its own ocr_last_error().
 *
 * Typical use:
 *
 *   ocr_engine* engine = ocr_create_in_dir("models", "small", NULL);
 *   if (!engine) { fprintf(stderr, "%s\n", ocr_last_error()); return 1; }
 *
 *   ocr_result result;
 *   if (ocr_run(engine, bgra, w, h, w * 4, OCR_PIXEL_BGRA32, &result) == OCR_OK) {
 *       for (int i = 0; i < result.count; i++)
 *           printf("%.*s (%.3f)\n", result.lines[i].text_length,
 *                  result.lines[i].text, result.lines[i].recognition_score);
 *   }
 *   ocr_result_free(&result);
 *   ocr_destroy(engine);
 */
#ifndef SIMDPADDLEOCR_H
#define SIMDPADDLEOCR_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  define OCR_API __declspec(dllimport)
#  define OCR_CDECL __cdecl
#else
#  define OCR_API
#  define OCR_CDECL
#endif

/* Status codes returned by ocr_run(). */
#define OCR_OK               0
#define OCR_INVALID_ARGUMENT (-1)
#define OCR_MODEL_LOAD_FAILED (-2)
#define OCR_RUN_FAILED        (-3)

/* ocr_run() pixel formats. */
#define OCR_PIXEL_BGR24  0
#define OCR_PIXEL_RGB24  1
#define OCR_PIXEL_BGRA32 2
#define OCR_PIXEL_RGBA32 3

/* Opaque engine handle. */
typedef struct ocr_engine ocr_engine;

/* One recognized text line, always in reading order. */
typedef struct ocr_line {
    /* NUL-terminated UTF-8. Owned by the result; do not free. */
    const uint8_t* text;
    /* Byte length of text, excluding the NUL. */
    int32_t text_length;
    /* Passed through unchanged from the managed engine; not normalized to
     * 0..1, so do not compare it against a probability threshold. */
    float recognition_score;
    float classification_score;
    /* 0 or 180: rotation that was undone before recognition. */
    int32_t rotation_degrees;
    /* Number of CTC emissions that produced text. */
    uint32_t emitted_count;
    /* Quad in source-image pixels: top-left, top-right, bottom-right, bottom-left. */
    float x1, y1;
    float x2, y2;
    float x3, y3;
    float x4, y4;
    float detection_score;
} ocr_line;

typedef struct ocr_result {
    ocr_line* lines;
    int32_t count;
    /* Lines the detector found before recognition. */
    int32_t detected_count;
    /* Detector input size after the internal resize. */
    int32_t detector_resized_width;
    int32_t detector_resized_height;
} ocr_result;

/* Library version as "major.minor.patch". Never NULL, never freed. */
OCR_API const char* OCR_CDECL ocr_version(void);

/*
 * Message of the last failed call on the calling thread, or NULL when the last
 * call succeeded. The pointer stays valid until the next OCR call on the same
 * thread; copy it if you need to keep it.
 */
OCR_API const char* OCR_CDECL ocr_last_error(void);

/*
 * Creates an engine from explicit model paths. classification_path and
 * options_json may be NULL. Returns NULL on failure.
 *
 * options_json (all keys optional):
 *   {
 *     "useDirectionClassification": true,
 *     "classifierThreshold": 0.0,
 *     "lineWorkerCount": 0,
 *     "detIntraOpThreads": 0,
 *     "recBatchLines": 1
 *   }
 * Zero means "engine default". Treat the whole object as optional; unknown
 * keys are ignored.
 */
OCR_API ocr_engine* OCR_CDECL ocr_create(const char* detection_path,
                                         const char* classification_path,
                                         const char* recognition_path,
                                         const char* dictionary_path,
                                         const char* options_json);

/*
 * Creates an engine from a directory laid out like the repository's models/
 * folder. variant is NULL, "" or "tiny" for det.onnx / rec.onnx /
 * ppocr_keys.txt, "small" for small_det.onnx / small_rec.onnx / rec_keys.txt,
 * "medium" for medium_det.onnx / medium_rec.onnx / rec_keys.txt.
 * cls.onnx is enabled automatically when it exists. Returns NULL on failure.
 */
OCR_API ocr_engine* OCR_CDECL ocr_create_in_dir(const char* directory,
                                                const char* variant,
                                                const char* options_json);

/* Releases an engine. NULL is a no-op. Blocks until in-flight runs finish. */
OCR_API void OCR_CDECL ocr_destroy(ocr_engine* engine);

/*
 * Runs DET + CLS + REC over one packed image.
 *
 *   pixels  interleaved image memory, not copied
 *   stride  row pitch in bytes, 0 for width * bytes_per_pixel
 *   format  OCR_PIXEL_*, matching pixels
 *
 * On OCR_OK, *result owns memory that must be released with ocr_result_free().
 * On failure *result is zeroed and ocr_last_error() explains why.
 */
OCR_API int32_t OCR_CDECL ocr_run(ocr_engine* engine,
                                  const uint8_t* pixels,
                                  int32_t width,
                                  int32_t height,
                                  int32_t stride,
                                  int32_t format,
                                  ocr_result* result);

/* Frees the lines of a result and zeroes the struct. Safe on zeroed structs. */
OCR_API void OCR_CDECL ocr_result_free(ocr_result* result);

#ifdef __cplusplus
}
#endif

#endif /* SIMDPADDLEOCR_H */
