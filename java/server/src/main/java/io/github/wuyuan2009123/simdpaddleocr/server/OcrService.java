package io.github.wuyuan2009123.simdpaddleocr.server;

import io.github.wuyuan2009123.simdpaddleocr.ImageSupport;
import io.github.wuyuan2009123.simdpaddleocr.OcrEngines;
import io.github.wuyuan2009123.simdpaddleocr.OcrResult;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/** Bridges Spring configuration to the framework-neutral engine cache. */
@Service
public class OcrService implements AutoCloseable {

    private final OcrEngines engines;

    public OcrService(OcrProperties properties) {
        this.engines = new OcrEngines(properties.modelsDir(), properties.variant(), properties.optionsJson());
    }

    public OcrResult recognize(ImageSupport.Image image, String variant) {
        return engines.run(variant, image);
    }

    public String nativeVersion() {
        return engines.nativeVersion();
    }

    @PreDestroy
    @Override
    public void close() {
        engines.close();
    }
}
