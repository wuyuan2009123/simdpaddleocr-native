package io.github.simdpaddleocr.server;

import io.github.simdpaddleocr.OcrEngine;
import io.github.simdpaddleocr.OcrResult;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Owns one engine per model variant and keeps them alive for the process lifetime. */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final OcrProperties properties;
    private final Map<String, OcrEngine> engines = new ConcurrentHashMap<>();

    public OcrService(OcrProperties properties) {
        this.properties = properties;
    }

    public OcrResult recognize(ImageDecoder.Pixels pixels, String variant) {
        OcrEngine engine = engine(variant == null || variant.isBlank() ? properties.variant() : variant);
        return engine.run(pixels.data(), pixels.width(), pixels.height(), pixels.format());
    }

    public String nativeVersion() {
        return engine(properties.variant()).nativeVersion();
    }

    private OcrEngine engine(String variant) {
        return engines.computeIfAbsent(variant, key -> {
            long started = System.nanoTime();
            OcrEngine engine = OcrEngine.openInDir(properties.modelsDir(), key, properties.optionsJson());
            log.info("loaded model variant '{}' from {} in {} ms (native {})",
                    key, properties.modelsDir(), (System.nanoTime() - started) / 1_000_000, engine.nativeVersion());
            return engine;
        });
    }

    @PreDestroy
    void shutdown() {
        engines.values().forEach(OcrEngine::close);
        engines.clear();
    }
}
