# Java bindings

The native library (see the repository root) exposed to the JVM through the
Foreign Function & Memory API. No JNA, no JNI, no runtime dependencies.

| module | artifact | what |
| --- | --- | --- |
| `core` | `simdpaddleocr-java` | FFM binding + all six 64-bit natives inside the jar |
| `server` | `simdpaddleocr-server` | Spring Boot 4 REST API |

## Build

```bash
cd java
mvn package
```

Requires JDK 25 (the FFM API is used directly) and Maven.

## Core usage

```java
try (OcrEngine engine = OcrEngine.openInDir("models", "small", null)) {
    byte[] bgra = ...;                       // width * height * 4
    OcrResult result = engine.run(bgra, width, height, OcrEngine.PixelFormat.BGRA32);
    System.out.println(result.text());
    result.lines().forEach(l -> System.out.println(l.box() + " " + l.text()));
}
```

- `openInDir` accepts `tiny` / `small` / `medium` and picks up `cls.onnx`
  automatically. The directory is the upstream `models/` folder.
- One engine is thread-safe: share it, call `run` from many threads.
- `close()` must not race with an in-flight `run`.
- `recognitionScore` is passed through from the engine, not normalized to 0..1.
- By default the library is extracted from `/native/<os>-<arch>/` inside the
  jar. Override with `-Dsimdpaddleocr.native.path=/path/to/simdpaddleocr.so`.

JDK 25 warns about restricted FFM methods unless native access is enabled:

```bash
java --enable-native-access=ALL-UNNAMED ...
```

## REST API

```bash
mvn -pl server -am package
java --enable-native-access=ALL-UNNAMED -jar server/target/simdpaddleocr-server-1.0.0.jar \
  --simdpaddleocr.models-dir=/path/to/SimdPaddleOCR/models
```

```bash
curl -F "file=@photo.png" "http://localhost:8080/api/ocr?variant=small"
curl http://localhost:8080/api/health
```

```json
{
  "variant": "small",
  "elapsedMs": 188,
  "detectedCount": 2,
  "detectorWidth": 960,
  "detectorHeight": 256,
  "lines": [
    {
      "text": "Java 调用 NativeAOT 共享库",
      "recognitionScore": 20.94,
      "classificationScore": 1.0,
      "rotationDegrees": 0,
      "emittedCount": 21,
      "box": { "x1": 27.84, "y1": 44.0, "x2": 605.0, "y2": 42.0, "x3": 605.16, "y3": 88.0, "x4": 28.0, "y4": 90.0 },
      "detectionScore": 0.927
    }
  ]
}
```

`POST /api/ocr` takes a multipart field `file` (any format `ImageIO` reads)
and an optional `variant`. Engines are created lazily per variant and closed on
shutdown.

| property | default | meaning |
| --- | --- | --- |
| `simdpaddleocr.models-dir` | `./models` | upstream model folder |
| `simdpaddleocr.variant` | `small` | default variant |
| `simdpaddleocr.options-json` | unset | engine options, see `simdpaddleocr.h` |

Environment variables `SIMDPADDLEOCR_MODELS` / `SIMDPADDLEOCR_VARIANT` override
the defaults.

## Bundled platforms

```
core/src/main/resources/native/
  windows-x86_64/  windows-aarch64/
  linux-x86_64/    linux-aarch64/
  osx-x86_64/      osx-aarch64/
```

Only 64-bit targets are supported: there is no 32-bit JVM for Windows, so the
`win-x86` native artifact is unused here. To refresh them, download the
artifacts from the native build workflow and unpack the library into the
matching directory.

## Solon

`core` has no framework dependency, so a Solon app is the same three lines:

```java
OcrEngine engine = OcrEngine.openInDir("models", "small", null);
OcrResult result = engine.run(bgra, width, height, OcrEngine.PixelFormat.BGRA32);
```
