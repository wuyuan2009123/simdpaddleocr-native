package io.github.wuyuan2009123.simdpaddleocr;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/** Decodes encoded images (PNG/JPEG/BMP/GIF) into the packed pixels the engine wants. */
public final class ImageSupport {

    /** Interleaved BGRA32 pixels plus their dimensions. */
    public record Image(byte[] bgra, int width, int height) {
    }

    private ImageSupport() {
    }

    public static Image decode(byte[] encoded) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(encoded));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read image", e);
        }
        if (image == null) {
            throw new IllegalArgumentException("unsupported image format; send PNG, JPEG, BMP or GIF");
        }
        return toBgra(image);
    }

    public static Image toBgra(BufferedImage source) {
        BufferedImage argb = source;
        if (source.getType() != BufferedImage.TYPE_INT_ARGB) {
            argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = argb.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }

        int width = argb.getWidth();
        int height = argb.getHeight();
        int[] pixels = argb.getRGB(0, 0, width, height, null, 0, width);
        byte[] bgra = new byte[width * height * 4];
        for (int i = 0, o = 0; i < pixels.length; i++, o += 4) {
            int value = pixels[i];
            bgra[o] = (byte) value;
            bgra[o + 1] = (byte) (value >> 8);
            bgra[o + 2] = (byte) (value >> 16);
            bgra[o + 3] = (byte) (value >> 24);
        }
        return new Image(bgra, width, height);
    }
}
