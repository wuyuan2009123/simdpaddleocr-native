using System.Runtime.InteropServices;
using System.Text;
using SkiaSharp;

namespace Sdcb.SimdPaddleOCR.NativeSmoke;

/// <summary>
/// Loads a published NativeAOT shared library and drives it through the flat C
/// ABI declared in src/Sdcb.SimdPaddleOCR.Native/include/simdpaddleocr.h.
/// Function pointers resolved with NativeLibrary.GetExport keep the harness
/// independent of DllImport name probing, so it works unchanged on every RID.
/// </summary>
internal static unsafe class Program
{
    private const int Ok = 0;

    private static int Main(string[] args)
    {
        string? libPath = null;
        string modelsDir = "models";
        string variant = "small";
        int fontSize = 48;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--lib" when i + 1 < args.Length: libPath = args[++i]; break;
                case "--models" when i + 1 < args.Length: modelsDir = args[++i]; break;
                case "--variant" when i + 1 < args.Length: variant = args[++i]; break;
                case "--font-size" when i + 1 < args.Length: fontSize = int.Parse(args[++i]); break;
                case "-h" or "--help":
                    Console.WriteLine("usage: --lib <path> [--models <dir>] [--variant tiny|small|medium] [--font-size N]");
                    return 0;
                default:
                    Console.Error.WriteLine($"unknown argument: {args[i]}");
                    return 2;
            }
        }

        if (libPath is null)
        {
            Console.Error.WriteLine("--lib is required");
            return 2;
        }

        libPath = Path.GetFullPath(libPath);
        modelsDir = Path.GetFullPath(modelsDir);

        if (!File.Exists(libPath))
        {
            Console.Error.WriteLine($"FAIL library not found: {libPath}");
            return 1;
        }

        Console.WriteLine($"library : {libPath} ({new FileInfo(libPath).Length / 1024} KB)");
        Console.Out.Flush();

        try
        {
            IntPtr library = NativeLibrary.Load(libPath);
            try
            {
                return Verify(library, libPath, modelsDir, variant, fontSize);
            }
            finally
            {
                NativeLibrary.Free(library);
            }
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"FAIL {ex.GetType().FullName}: {ex.Message}");
            return 1;
        }
    }

    private static unsafe int Verify(IntPtr library, string libPath, string modelsDir, string variant, int fontSize)
    {
        var version = (delegate* unmanaged[Cdecl]<IntPtr>)NativeLibrary.GetExport(library, "ocr_version");
        var lastError = (delegate* unmanaged[Cdecl]<IntPtr>)NativeLibrary.GetExport(library, "ocr_last_error");
        var createInDir = (delegate* unmanaged[Cdecl]<byte*, byte*, byte*, IntPtr>)
            NativeLibrary.GetExport(library, "ocr_create_in_dir");
        var destroy = (delegate* unmanaged[Cdecl]<IntPtr, void>)NativeLibrary.GetExport(library, "ocr_destroy");
        var run = (delegate* unmanaged[Cdecl]<IntPtr, byte*, int, int, int, int, NativeResult*, int>)
            NativeLibrary.GetExport(library, "ocr_run");
        var resultFree = (delegate* unmanaged[Cdecl]<NativeResult*, void>)
            NativeLibrary.GetExport(library, "ocr_result_free");

        string? Error() => Marshal.PtrToStringUTF8(lastError());

        Console.WriteLine($"version : {Marshal.PtrToStringUTF8(version())}");
        Console.WriteLine($"models  : {modelsDir} ({variant})");
        Console.Out.Flush();

        IntPtr engine;
        fixed (byte* pDir = Utf8Z(modelsDir))
        fixed (byte* pVariant = Utf8Z(variant))
        {
            engine = createInDir(pDir, pVariant, null);
        }

        if (engine == IntPtr.Zero)
        {
            Console.Error.WriteLine($"FAIL ocr_create_in_dir: {Error()}");
            return 1;
        }

        try
        {
            (byte[] pixels, int width, int height, bool rendered) = RenderSample(fontSize);
            NativeResult result;
            int status;
            fixed (byte* p = pixels)
            {
                status = run(engine, p, width, height, width * 4, 2 /* BGRA32 */, &result);
            }

            if (status != Ok)
            {
                Console.Error.WriteLine($"FAIL ocr_run status={status}: {Error()}");
                return 1;
            }

            Console.WriteLine($"image   : {width}x{height} rendered={(rendered ? "yes" : "no")}");
            Console.WriteLine($"detected: {result.Count} recognized / {result.DetectedCount} boxes " +
                $"(det input {result.DetectorResizedWidth}x{result.DetectorResizedHeight})");
            for (int i = 0; i < result.Count; i++)
            {
                NativeLine line = result.Lines[i];
                string text = Marshal.PtrToStringUTF8((IntPtr)line.Text, line.TextLength) ?? string.Empty;
                Console.WriteLine($"  [{i}] rec={line.RecognitionScore:F3} det={line.DetectionScore:F3} " +
                    $"rot={line.RotationDegrees} " +
                    $"quad=({line.X1:F0},{line.Y1:F0}) ({line.X2:F0},{line.Y2:F0}) " +
                    $"({line.X3:F0},{line.Y3:F0}) ({line.X4:F0},{line.Y4:F0}) '{text}'");
            }

            // Capture before freeing: ocr_result_free zeroes the struct.
            int detectedCount = result.DetectedCount;
            resultFree(&result);
            if (result.Lines != null || result.Count != 0)
            {
                Console.Error.WriteLine("FAIL ocr_result_free did not zero the struct");
                return 1;
            }

            string? afterRun = Error();
            if (afterRun is not null)
            {
                Console.Error.WriteLine($"FAIL ocr_last_error not cleared after a successful run: {afterRun}");
                return 1;
            }

            if (!rendered)
            {
                Console.WriteLine("warn    : no typeface available, only the blank-image path was exercised");
            }
            else if (detectedCount == 0)
            {
                Console.Error.WriteLine("FAIL the rendered sample produced no detections");
                return 1;
            }

            Console.WriteLine("PASS");
            return 0;
        }
        finally
        {
            destroy(engine);
        }
    }

    private static byte[] Utf8Z(string value) => Encoding.UTF8.GetBytes(value + "\0");

    private static (byte[] Pixels, int Width, int Height, bool Rendered) RenderSample(int fontSize)
    {
        string[] lines = ["The quick brown fox jumps over", "the lazy dog 0123456789"];
        int width = 960;
        int lineHeight = (int)(fontSize * 1.6f);
        int height = lineHeight * lines.Length + 40;

        using SKTypeface? typeface = LoadTypeface();
        using var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Opaque));
        using (SKCanvas canvas = new(bitmap))
        {
            canvas.Clear(SKColors.White);
            if (typeface is not null)
            {
                using SKFont font = new(typeface, fontSize) { Edging = SKFontEdging.Antialias, Subpixel = true };
                using SKPaint paint = new() { Color = SKColors.Black, IsAntialias = true };
                for (int i = 0; i < lines.Length; i++)
                    canvas.DrawText(lines[i], 24, 24 + fontSize + i * lineHeight, SKTextAlign.Left, font, paint);
            }
        }

        byte[] pixels = new byte[width * height * 4];
        Marshal.Copy(bitmap.GetPixels(), pixels, 0, pixels.Length);
        return (pixels, width, height, typeface is not null);
    }

    private static SKTypeface? LoadTypeface()
    {
        string[] candidates =
        [
            @"C:\Windows\Fonts\arial.ttf",
            @"C:\Windows\Fonts\segoeui.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Helvetica.ttc",
        ];

        foreach (string candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                SKTypeface? fromFile = SKTypeface.FromFile(candidate);
                if (fromFile is not null) return fromFile;
            }
        }
        return SKTypeface.Default;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeLine
    {
        public byte* Text;
        public int TextLength;
        public float RecognitionScore;
        public float ClassificationScore;
        public int RotationDegrees;
        public uint EmittedCount;
        public float X1, Y1, X2, Y2, X3, Y3, X4, Y4;
        public float DetectionScore;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct NativeResult
    {
        public NativeLine* Lines;
        public int Count;
        public int DetectedCount;
        public int DetectorResizedWidth;
        public int DetectorResizedHeight;
    }
}
