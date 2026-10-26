# SimdPaddleOCR native library

Builds [sdcb/SimdPaddleOCR](https://github.com/sdcb/SimdPaddleOCR) into a
NativeAOT shared library with a flat C ABI, so non-.NET callers get the whole
DET + CLS + REC pipeline without the runtime, ONNX Runtime, Paddle Inference or
OpenCV.

The engine is **not vendored**. Every build clones the upstream repository at a
tag you choose and compiles against that checkout:

| what | where |
| --- | --- |
| `native/SimdPaddleOcr.Native.csproj` | NativeAOT shared-library build |
| `native/NativeExports.cs` | the exported C ABI |
| `native/simdpaddleocr.h` | public header, annotated |
| `smoke/` | loads a built library and runs real OCR on it |

## Outputs

| RID | file |
| --- | --- |
| `win-x64` / `win-arm64` / `win-x86` | `simdpaddleocr.dll` |
| `linux-x64` / `linux-arm64` | `simdpaddleocr.so` |
| `osx-x64` / `osx-arm64` | `simdpaddleocr.dylib` |

NativeAOT emits no `lib` prefix, so `dlopen` needs the exact name or a path.

Each archive also carries the header, `version.txt`, the upstream `LICENSE`
and `THIRD-PARTY-NOTICES.md`.

## Local build

```bash
git clone https://github.com/sdcb/SimdPaddleOCR ../sharp    # any path
dotnet publish native/SimdPaddleOcr.Native.csproj -c Release -r linux-x64 \
  -p:UpstreamRoot=$PWD/../sharp
```

Then push it through the real ABI:

```bash
dotnet run --project smoke -c Release -- \
  --lib bin/Release/net10.0/linux-x64/publish/simdpaddleocr.so \
  --models ../sharp/models --variant small
```

AOT needs a native linker: MSVC on Windows, `clang` + `zlib1g-dev` on Linux,
Xcode CLT on macOS.

Models stay external — pass the upstream `models/` directory to
`ocr_create_in_dir` at runtime, so the library itself is only a few MB.
x64 builds use an AVX2 baseline; pass `-p:SimdOcrEnableAvx2=false` for
pre-AVX2 CPUs.

## CI

`.github/workflows/build.yml` builds all seven RIDs, smoke-tests every library
its runner can load, and attaches the archives to the GitHub Release when a
`v*` tag is pushed.

Pick the upstream revision with `workflow_dispatch` →

`upstream-ref`: empty means "latest upstream tag", otherwise a tag, branch or
SHA.

## API

```c
ocr_engine* engine = ocr_create_in_dir("models", "small", NULL);
ocr_result result;
if (ocr_run(engine, bgra, w, h, w * 4, OCR_PIXEL_BGRA32, &result) == OCR_OK) {
    for (int i = 0; i < result.count; i++)
        printf("%.*s\n", result.lines[i].text_length, result.lines[i].text);
}
ocr_result_free(&result);
ocr_destroy(engine);
```

`ocr_create` takes explicit model paths, `ocr_create_in_dir` accepts the
upstream layout (`tiny` / `small` / `medium`). One engine is thread-safe;
`ocr_last_error` is per thread. Pixels are never copied — pass interleaved
BGR24/RGB24/BGRA32/RGBA32 plus its stride.

See `native/simdpaddleocr.h` for the struct layout, `options_json` keys and the
memory ownership rules.
