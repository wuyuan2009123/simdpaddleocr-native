package io.github.wuyuan2009123.simdpaddleocr.server;

import io.github.wuyuan2009123.simdpaddleocr.ImageSupport;
import io.github.wuyuan2009123.simdpaddleocr.OcrException;
import io.github.wuyuan2009123.simdpaddleocr.OcrResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class OcrController {

    private final OcrService service;

    public OcrController(OcrService service) {
        this.service = service;
    }

    /** @param variant tiny, small or medium; defaults to simdpaddleocr.variant */
    public record OcrResponse(String variant,
                              long elapsedMs,
                              int detectedCount,
                              int detectorWidth,
                              int detectorHeight,
                              List<OcrResult.OcrLine> lines) {
    }

    @PostMapping(path = "/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public OcrResponse recognize(@RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "variant", required = false) String variant) throws IOException {
        ImageSupport.Image image = ImageSupport.decode(file.getBytes());
        long started = System.nanoTime();
        OcrResult result = service.recognize(image, variant);
        return new OcrResponse(
                variant == null || variant.isBlank() ? "default" : variant,
                (System.nanoTime() - started) / 1_000_000,
                result.detectedCount(),
                result.detectorWidth(),
                result.detectorHeight(),
                result.lines());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "native", service.nativeVersion());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler(OcrException.class)
    public ResponseEntity<Map<String, String>> ocrFailed(OcrException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", String.valueOf(e.getMessage()), "status", Integer.toString(e.status())));
    }
}
