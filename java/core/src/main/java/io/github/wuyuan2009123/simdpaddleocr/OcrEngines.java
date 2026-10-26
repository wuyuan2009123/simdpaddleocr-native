package io.github.wuyuan2009123.simdpaddleocr;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one {@link OcrEngine} per model variant so callers do not pay the
 * model-loading cost on every request.
 *
 * <p>Thread-safe: engines are created lazily and reused.
 */
public final class OcrEngines implements AutoCloseable {

    private final String modelsDir;
    private final String optionsJson;
    private final String defaultVariant;
    private final Map<String, OcrEngine> engines = new ConcurrentHashMap<>();

    public OcrEngines(String modelsDir, String optionsJson) {
        this(modelsDir, "small", optionsJson);
    }

    public OcrEngines(String modelsDir, String defaultVariant, String optionsJson) {
        this.modelsDir = modelsDir;
        this.defaultVariant = defaultVariant == null || defaultVariant.isBlank() ? "small" : defaultVariant;
        this.optionsJson = optionsJson;
    }

    public OcrResult run(ImageSupport.Image image) {
        return run(defaultVariant, image);
    }

    public OcrResult run(String variant, ImageSupport.Image image) {
        return engine(variant).run(image.bgra(), image.width(), image.height(), OcrEngine.PixelFormat.BGRA32);
    }

    public OcrResult run(String variant, byte[] bgra, int width, int height) {
        return engine(variant).run(bgra, width, height, OcrEngine.PixelFormat.BGRA32);
    }

    public String nativeVersion() {
        return engine(defaultVariant).nativeVersion();
    }

    public String defaultVariant() {
        return defaultVariant;
    }

    public OcrEngine engine(String variant) {
        String key = variant == null || variant.isBlank() ? defaultVariant : variant;
        return engines.computeIfAbsent(key, name -> OcrEngine.openInDir(modelsDir, name, optionsJson));
    }

    /** Closes every engine. Do not call while requests are in flight. */
    @Override
    public void close() {
        engines.values().forEach(OcrEngine::close);
        engines.clear();
    }
}
