package io.github.wuyuan2009123.simdpaddleocr.solon;

import io.github.wuyuan2009123.simdpaddleocr.ImageSupport;
import io.github.wuyuan2009123.simdpaddleocr.OcrEngines;
import io.github.wuyuan2009123.simdpaddleocr.OcrException;
import io.github.wuyuan2009123.simdpaddleocr.OcrResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.noear.solon.Solon;
import org.noear.solon.core.handle.Context;
import org.noear.solon.core.handle.MethodType;
import org.noear.solon.core.handle.UploadedFile;

/**
 * Solon REST server: the same two endpoints as the Spring Boot module, on the
 * same core jar.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -jar simdpaddleocr-solon-1.0.0.jar \
 *      --server.port=8080 --simdpaddleocr.models-dir=/path/to/models
 * </pre>
 */
public final class SolonOcrApp {

    public static void main(String[] args) {
        Map<String, String> cli = parseArgs(args);

        // Solon.cfg() only exists once start() has loaded app.yml, so wire the
        // routes from inside the startup callback.
        Solon.start(SolonOcrApp.class, args, app -> {
            OcrEngines engines = new OcrEngines(
                    config(cli, "simdpaddleocr.models-dir", "models"),
                    config(cli, "simdpaddleocr.variant", "small"),
                    config(cli, "simdpaddleocr.options-json", null));
            Runtime.getRuntime().addShutdownHook(new Thread(engines::close));

            app.router().add("/api/health", MethodType.GET, ctx -> {
                Map<String, String> body = new LinkedHashMap<>();
                body.put("status", "ok");
                body.put("native", engines.nativeVersion());
                body.put("framework", "solon " + Solon.version());
                ctx.render(body);
            });

            app.router().add("/api/ocr", MethodType.POST, ctx -> recognize(ctx, engines));
        });
    }

    private static void recognize(Context ctx, OcrEngines engines) throws Throwable {
        UploadedFile file = ctx.file("file");
        if (file == null || file.isEmpty()) {
            ctx.status(400);
            ctx.render(Map.of("error", "file field is required"));
            return;
        }

        long decodeStarted = System.nanoTime();
        ImageSupport.Image image;
        try {
            image = ImageSupport.decode(file.getContentAsBytes());
        } catch (IllegalArgumentException e) {
            ctx.status(400);
            ctx.render(Map.of("error", String.valueOf(e.getMessage())));
            return;
        }
        long decodeMs = (System.nanoTime() - decodeStarted) / 1_000_000;

        String variant = ctx.param("variant");
        try {
            long started = System.nanoTime();
            OcrResult result = engines.run(variant, image);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("variant", variant == null || variant.isBlank() ? "default" : variant);
            body.put("elapsedMs", elapsedMs);
            body.put("decodeMs", decodeMs);
            body.put("detectedCount", result.detectedCount());
            body.put("detectorWidth", result.detectorWidth());
            body.put("detectorHeight", result.detectorHeight());
            body.put("lines", result.lines());
            ctx.render(body);
        } catch (OcrException e) {
            ctx.status(500);
            ctx.render(Map.of("error", String.valueOf(e.getMessage()), "status", e.status()));
        }
    }

    private static String config(Map<String, String> cli, String key, String fallback) {
        String value = cli.get(key);
        if (value == null) {
            value = System.getProperty(key);
        }
        if (value == null) {
            try {
                value = Solon.cfg().get(key);
            } catch (RuntimeException e) {
                value = null;
            }
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq > 2) {
                    parsed.put(arg.substring(2, eq), arg.substring(eq + 1));
                }
            }
        }
        return parsed;
    }

    private SolonOcrApp() {
    }
}
