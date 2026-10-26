using System;
using System.IO;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;

namespace Sdcb.SimdPaddleOCR.Native;

/// <summary>One recognized text line. Mirrors <c>ocr_line</c> in <c>include/simdpaddleocr.h</c>.</summary>
[StructLayout(LayoutKind.Sequential)]
public unsafe struct OcrNativeLine
{
    /// <summary>NUL-terminated UTF-8 text owned by the result.</summary>
    public byte* Text;
    public int TextLength;
    public float RecognitionScore;
    public float ClassificationScore;
    public int RotationDegrees;
    public uint EmittedCount;
    public float X1, Y1, X2, Y2, X3, Y3, X4, Y4;
    public float DetectionScore;
}

/// <summary>Run output. Mirrors <c>ocr_result</c> in <c>include/simdpaddleocr.h</c>.</summary>
[StructLayout(LayoutKind.Sequential)]
public unsafe struct OcrNativeResult
{
    public OcrNativeLine* Lines;
    public int Count;
    public int DetectedCount;
    public int DetectorResizedWidth;
    public int DetectorResizedHeight;
}

/// <summary>
/// Flat C ABI over <see cref="PaddleOcrAll"/>. Every export is exception-safe:
/// managed exceptions never cross the boundary, they surface as a negative
/// return code plus <c>ocr_last_error</c>.
/// </summary>
public static unsafe class NativeExports
{
    /// <summary>All models and pixels were processed.</summary>
    public const int Ok = 0;
    /// <summary>A required argument was null, empty, or out of range.</summary>
    public const int InvalidArgument = -1;
    /// <summary>A model file or dictionary could not be read.</summary>
    public const int ModelLoadFailed = -2;
    /// <summary>Inference failed; see <c>ocr_last_error</c>.</summary>
    public const int RunFailed = -3;

    private static readonly System.Version s_version =
        typeof(PaddleOcrAll).Assembly.GetName().Version ?? new System.Version(0, 0, 0);

    [ThreadStatic] private static IntPtr t_lastError;
    [ThreadStatic] private static IntPtr t_version;

    private static void SetError(string message)
    {
        IntPtr previous = t_lastError;
        t_lastError = AllocUtf8(message);
        if (previous != IntPtr.Zero) NativeMemory.Free((void*)previous);
    }

    private static void ClearError()
    {
        IntPtr previous = t_lastError;
        t_lastError = IntPtr.Zero;
        if (previous != IntPtr.Zero) NativeMemory.Free((void*)previous);
    }

    private static IntPtr AllocUtf8(string value)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(value);
        byte* buffer = (byte*)NativeMemory.Alloc((nuint)bytes.Length + 1);
        bytes.AsSpan().CopyTo(new Span<byte>(buffer, bytes.Length));
        buffer[bytes.Length] = 0;
        return (IntPtr)buffer;
    }

    private static string? ToString(byte* value) => value is null ? null : Marshal.PtrToStringUTF8((IntPtr)value);

    private static PaddleOcrAll GetEngine(IntPtr handle)
    {
        if (handle == IntPtr.Zero) throw new ArgumentNullException(nameof(handle));
        return GCHandle.FromIntPtr(handle).Target as PaddleOcrAll
            ?? throw new ArgumentException("The handle is not a live OCR engine.", nameof(handle));
    }

    /// <summary>Library version, e.g. <c>2.0.0</c>. Never null.</summary>
    [UnmanagedCallersOnly(EntryPoint = "ocr_version", CallConvs = new[] { typeof(CallConvCdecl) })]
    public static IntPtr Version()
    {
        if (t_version == IntPtr.Zero) t_version = AllocUtf8(s_version.ToString(3));
        return t_version;
    }

    /// <summary>Message of the last failed call on this thread, or NULL. Valid until the next call.</summary>
    [UnmanagedCallersOnly(EntryPoint = "ocr_last_error", CallConvs = new[] { typeof(CallConvCdecl) })]
    public static IntPtr LastError() => t_lastError;

    /// <summary>
    /// Creates an engine from explicit model paths. <paramref name="classificationPath"/>,
    /// <paramref name="optionsJson"/> may be NULL. Returns NULL on failure.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "ocr_create", CallConvs = new[] { typeof(CallConvCdecl) })]
    public static IntPtr Create(byte* detectionPath, byte* classificationPath, byte* recognitionPath,
        byte* dictionaryPath, byte* optionsJson)
    {
        try
        {
            string? det = ToString(detectionPath);
            string? rec = ToString(recognitionPath);
            string? dict = ToString(dictionaryPath);
            string? cls = ToString(classificationPath);
            if (string.IsNullOrWhiteSpace(det) || string.IsNullOrWhiteSpace(rec) || string.IsNullOrWhiteSpace(dict))
            {
                SetError("detectionPath, recognitionPath and dictionaryPath are required.");
                return IntPtr.Zero;
            }

            PaddleOcrAll engine = PaddleOcrAll.Load(det, cls, rec, dict, ParseOptions(optionsJson));
            ClearError();
            return GCHandle.ToIntPtr(GCHandle.Alloc(engine));
        }
        catch (Exception ex)
        {
            SetError(ex.Message);
            return IntPtr.Zero;
        }
    }

    /// <summary>
    /// Creates an engine from a model directory laid out like the repository's
    /// <c>models/</c> folder. <paramref name="variant"/> is NULL/"" (tiny),
    /// "tiny", "small" or "medium"; <c>cls.onnx</c> is picked up when present.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "ocr_create_in_dir", CallConvs = new[] { typeof(CallConvCdecl) })]
    public static IntPtr CreateInDir(byte* directory, byte* variant, byte* optionsJson)
    {
        try
        {
            string? dir = ToString(directory);
            if (string.IsNullOrWhiteSpace(dir))
            {
                SetError("directory is required.");
                return IntPtr.Zero;
            }

            string prefix = (ToString(variant) ?? string.Empty).Trim().ToLowerInvariant() switch
            {
                "" or "tiny" => string.Empty,
                "small" => "small_",
                "medium" => "medium_",
                string other => throw new ArgumentException($"Unknown model variant '{other}'; use tiny, small or medium."),
            };
            string dictionary = prefix.Length == 0 ? "ppocr_keys.txt" : "rec_keys.txt";

            string det = Path.Combine(dir, prefix + "det.onnx");
            string rec = Path.Combine(dir, prefix + "rec.onnx");
            string dict = Path.Combine(dir, dictionary);
            foreach (string path in new[] { det, rec, dict })
            {
                if (!File.Exists(path))
                {
                    SetError($"Model file not found: {path}");
                    return IntPtr.Zero;
                }
            }

            string cls = Path.Combine(dir, "cls.onnx");
            PaddleOcrAll engine = PaddleOcrAll.Load(det, File.Exists(cls) ? cls : null, rec, dict,
                ParseOptions(optionsJson));
            ClearError();
            return GCHandle.ToIntPtr(GCHandle.Alloc(engine));
        }
        catch (Exception ex)
        {
            SetError(ex.Message);
            return IntPtr.Zero;
        }
    }

    /// <summary>Releases an engine. NULL is a no-op.</summary>
    [UnmanagedCallersOnly(EntryPoint = "ocr_destroy", CallConvs = new[] { typeof(CallConvCdecl) })]
    public static void Destroy(IntPtr handle)
    {
        if (handle == IntPtr.Zero) return;
        try
        {
            GCHandle gcHandle = GCHandle.FromIntPtr(handle);
            (gcHandle.Target as IDisposable)?.Dispose();
            gcHandle.Free();
        }
        catch (Exception ex)
        {
            SetError(ex.Message);
        }
    }

    /// <summary>
    /// Runs DET + CLS + REC over one packed image. <paramref name="format"/> is
    /// 0=BGR24, 1=RGB24, 2=BGRA32, 3=RGBA32; <paramref name="stride"/> 0 means
    /// tightly packed. On success <paramref name="result"/> owns memory that the
    /// caller must release with <c>ocr_result_free</c>.
    /// </summary>
    [UnmanagedCallersOnly(EntryPoint = "ocr_run", CallConvs = new[] { typeof(CallConvCdecl) })]
    public static int Run(IntPtr handle, byte* pixels, int width, int height, int stride, int format,
        OcrNativeResult* result)
    {
        if (result is null)
        {
            SetError("result is required.");
            return InvalidArgument;
        }

        *result = default;
        try
        {
            if (pixels is null)
            {
                SetError("pixels is required.");
                return InvalidArgument;
            }
            if (width <= 0 || height <= 0)
            {
                SetError("width and height must be positive.");
                return InvalidArgument;
            }

            ImagePixelFormat pixelFormat;
            int bytesPerPixel;
            switch (format)
            {
                case 0: pixelFormat = ImagePixelFormat.Bgr24; bytesPerPixel = 3; break;
                case 1: pixelFormat = ImagePixelFormat.Rgb24; bytesPerPixel = 3; break;
                case 2: pixelFormat = ImagePixelFormat.Bgra32; bytesPerPixel = 4; break;
                case 3: pixelFormat = ImagePixelFormat.Rgba32; bytesPerPixel = 4; break;
                default:
                    SetError($"Unknown pixel format {format}; use 0..3.");
                    return InvalidArgument;
            }

            int minStride = checked(width * bytesPerPixel);
            if (stride == 0) stride = minStride;
            else if (stride < minStride)
            {
                SetError("stride is smaller than width * bytesPerPixel.");
                return InvalidArgument;
            }

            PaddleOcrAll engine = GetEngine(handle);
            int length = checked(stride * height);
            PaddleOcrResult ocr = engine.Run(new ReadOnlySpan<byte>(pixels, length), width, height,
                returnCtcAlignment: false, stride, pixelFormat);

            Fill(result, ocr);
            ClearError();
            return Ok;
        }
        catch (Exception ex)
        {
            FreeResult(result);
            SetError(ex.Message);
            return ex is ArgumentException or OverflowException ? InvalidArgument : RunFailed;
        }
    }

    /// <summary>Frees a result produced by <c>ocr_run</c>. Zeroes the struct.</summary>
    [UnmanagedCallersOnly(EntryPoint = "ocr_result_free", CallConvs = new[] { typeof(CallConvCdecl) })]
    public static void ResultFree(OcrNativeResult* result) => FreeResult(result);

    private static void FreeResult(OcrNativeResult* result)
    {
        if (result is null || result->Lines is null) return;
        for (int i = 0; i < result->Count; i++)
        {
            byte* text = result->Lines[i].Text;
            if (text is not null) NativeMemory.Free(text);
        }
        NativeMemory.Free(result->Lines);
        *result = default;
    }

    private static void Fill(OcrNativeResult* result, PaddleOcrResult ocr)
    {
        result->Count = ocr.Lines.Length;
        result->DetectedCount = ocr.DetectedCount;
        result->DetectorResizedWidth = ocr.DetectorResizedWidth;
        result->DetectorResizedHeight = ocr.DetectorResizedHeight;
        if (ocr.Lines.Length == 0) return;

        OcrNativeLine* lines = (OcrNativeLine*)NativeMemory.Alloc((nuint)ocr.Lines.Length, (nuint)sizeof(OcrNativeLine));
        result->Lines = lines;
        for (int i = 0; i < ocr.Lines.Length; i++)
        {
            PaddleOcrLine source = ocr.Lines[i];
            PaddleOcrDetectionBox box = source.Box;
            int byteCount = Encoding.UTF8.GetByteCount(source.Text);
            byte* text = (byte*)NativeMemory.Alloc((nuint)byteCount + 1);
            Encoding.UTF8.GetBytes(source.Text, new Span<byte>(text, byteCount));
            text[byteCount] = 0;

            lines[i] = new OcrNativeLine
            {
                Text = text,
                TextLength = byteCount,
                RecognitionScore = source.RecognitionScore,
                ClassificationScore = source.ClassificationScore,
                RotationDegrees = source.AppliedRotationDegrees,
                EmittedCount = source.EmittedCount,
                X1 = box.X1, Y1 = box.Y1,
                X2 = box.X2, Y2 = box.Y2,
                X3 = box.X3, Y3 = box.Y3,
                X4 = box.X4, Y4 = box.Y4,
                DetectionScore = box.Score,
            };
        }
    }

    private static PaddleOcrOptions ParseOptions(byte* optionsJson)
    {
        string? json = ToString(optionsJson);
        if (string.IsNullOrWhiteSpace(json)) return new PaddleOcrOptions();

        using JsonDocument document = JsonDocument.Parse(json);
        JsonElement root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
            throw new ArgumentException("options_json must be a JSON object.");

        return new PaddleOcrOptions
        {
            UseDirectionClassification = ReadBool(root, "useDirectionClassification", true),
            ClassifierThreshold = ReadSingle(root, "classifierThreshold", 0f),
            LineWorkerCount = ReadInt32(root, "lineWorkerCount", 0),
            DetIntraOpThreads = ReadInt32(root, "detIntraOpThreads", 0),
            RecBatchLines = ReadInt32(root, "recBatchLines", 1),
        };
    }

    private static bool ReadBool(JsonElement root, string name, bool fallback) =>
        root.TryGetProperty(name, out JsonElement value) && value.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? value.GetBoolean()
            : fallback;

    private static int ReadInt32(JsonElement root, string name, int fallback) =>
        root.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.Number
            ? value.GetInt32()
            : fallback;

    private static float ReadSingle(JsonElement root, string name, float fallback) =>
        root.TryGetProperty(name, out JsonElement value) && value.ValueKind == JsonValueKind.Number
            ? value.GetSingle()
            : fallback;
}
