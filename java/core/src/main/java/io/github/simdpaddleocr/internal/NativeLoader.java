package io.github.simdpaddleocr.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Finds the native library for this platform.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code -Dsimdpaddleocr.native.path=/abs/path/to/simdpaddleocr.so}</li>
 *   <li>{@code /native/&lt;platform&gt;/simdpaddleocr.&lt;ext&gt;} inside the jar,
 *       extracted to a temp directory</li>
 * </ol>
 */
public final class NativeLoader {

    private static final String OVERRIDE = "simdpaddleocr.native.path";
    private static volatile SymbolLookup lookup;

    private NativeLoader() {
    }

    public static SymbolLookup lookup() {
        SymbolLookup local = lookup;
        if (local == null) {
            synchronized (NativeLoader.class) {
                local = lookup;
                if (local == null) {
                    lookup = local = load();
                }
            }
        }
        return local;
    }

    private static SymbolLookup load() {
        if (ValueLayout.ADDRESS.byteSize() != 8) {
            throw new UnsatisfiedLinkError("SimdPaddleOCR requires a 64-bit JVM");
        }

        String override = System.getProperty(OVERRIDE);
        if (override != null && !override.isBlank()) {
            return SymbolLookup.libraryLookup(Path.of(override), Arena.global());
        }

        String platform = platform();
        String fileName = fileName(platform);
        String resource = "/native/" + platform + "/" + fileName;
        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("no native library bundled for " + platform
                        + " (missing " + resource + "); set -D" + OVERRIDE + " to point at one,"
                        + " or add the matching native artifact");
            }
            Path dir = Files.createTempDirectory("simdpaddleocr-");
            Path lib = dir.resolve(fileName);
            Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
            lib.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            return SymbolLookup.libraryLookup(lib, Arena.global());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to extract " + resource, e);
        }
    }

    /** e.g. {@code linux-x86_64}, matching the directories under {@code resources/native}. */
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osKey = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "osx" : os.contains("linux") ? "linux" : null;
        if (osKey == null) {
            throw new UnsatisfiedLinkError("unsupported os: " + os);
        }
        String archKey = switch (arch) {
            case "x86_64", "amd64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new UnsatisfiedLinkError("unsupported arch: " + arch);
        };
        return osKey + "-" + archKey;
    }

    private static String fileName(String platform) {
        if (platform.startsWith("windows")) {
            return "simdpaddleocr.dll";
        }
        return platform.startsWith("osx") ? "simdpaddleocr.dylib" : "simdpaddleocr.so";
    }
}
