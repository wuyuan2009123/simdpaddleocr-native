package io.github.wuyuan2009123.simdpaddleocr.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param modelsDir   directory holding det.onnx / rec.onnx / the dictionary, plus cls.onnx
 * @param variant     default model variant: tiny, small or medium
 * @param optionsJson engine options, see simdpaddleocr.h; may be null
 */
@ConfigurationProperties("simdpaddleocr")
public record OcrProperties(String modelsDir, String variant, String optionsJson) {

    public OcrProperties {
        modelsDir = modelsDir == null || modelsDir.isBlank() ? "models" : modelsDir;
        variant = variant == null || variant.isBlank() ? "small" : variant;
    }
}
