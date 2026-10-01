package com.freshcutz.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Shrinks a product photo down to something sane to store and serve,
 * instead of keeping whatever a phone camera produced (often several MB,
 * several-thousand pixels wide) forever. Every photo that goes through
 * here ends up a small fraction of Neon's free-tier storage cap however
 * many products the catalog grows to, and far cheaper to serve to every
 * storefront visitor on their first (uncached) page load — see
 * AdminProductController.uploadImage(), the only place this is called
 * from today.
 *
 * Deliberately always re-encodes as JPEG: a photographic product shot
 * compresses far better as JPEG than PNG, and this keeps every stored
 * photo in one predictable format regardless of what was uploaded. Any
 * transparency (a PNG with an alpha channel) is flattened onto a white
 * background first, since JPEG has no alpha channel of its own — writing
 * an ARGB image straight to a JPEG encoder is what produces the classic
 * "photo looks like garbage with a black background" bug.
 *
 * WEBP is accepted by the upload endpoint's content-type allowlist, but
 * the plain JDK's ImageIO ships no built-in WEBP reader (that needs a
 * third-party plugin this project doesn't depend on), so a WEBP upload is
 * detected via ImageIO.read() returning null and passed through
 * unprocessed rather than failing the upload — same behaviour as before
 * this existed, just not shrunk. JPEG and PNG — by far the common case
 * from a phone camera, a screenshot, or a downloaded product photo — are
 * the ones that actually get resized and compressed.
 */
@Slf4j
@Component
public class ProductImageProcessor {

    /** Longest side a stored photo is ever allowed to be. A storefront
     * product card or the admin thumbnail never needs more pixels than
     * this even on a retina display — anything wider is wasted bytes on
     * every single page load, not just wasted storage. Images already
     * smaller than this are never upscaled, only ever shrunk or left
     * alone. */
    private static final int MAX_DIMENSION = 1000;

    /** JPEG quality, 0-1. 0.8 is the standard "very hard to tell the
     * difference from the original, meaningfully smaller file" sweet spot
     * for photographic content. */
    private static final float JPEG_QUALITY = 0.80f;

    /** Resizes (if needed) and re-compresses an uploaded photo. Returns
     * the original bytes/content type unchanged if ImageIO can't decode
     * them at all (an unsupported format, or not really an image) rather
     * than throwing — a failed compression attempt should never be the
     * reason a photo upload fails outright. */
    public ProcessedImage compress(byte[] original, String originalContentType) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(original));
        } catch (IOException e) {
            log.warn("Could not decode uploaded image ({}), storing it unprocessed: {}",
                    originalContentType, e.getMessage());
            return new ProcessedImage(original, originalContentType);
        }
        if (source == null) {
            log.info("No image reader available for content type {} — storing it unprocessed",
                    originalContentType);
            return new ProcessedImage(original, originalContentType);
        }

        BufferedImage scaled = scaleDown(source);
        byte[] jpegBytes = toJpeg(scaled);
        log.info("Compressed product photo: {} bytes ({}) -> {} bytes (image/jpeg)",
                original.length, originalContentType, jpegBytes.length);
        return new ProcessedImage(jpegBytes, "image/jpeg");
    }

    private BufferedImage scaleDown(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, MAX_DIMENSION / (double) Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));

        // TYPE_INT_RGB (no alpha channel) with a white fill first is what
        // correctly flattens a transparent PNG instead of it turning into
        // a black background once re-encoded as JPEG below.
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private byte[] toJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            // The JDK always ships a JPEG writer — reaching this would
            // mean a very unusual runtime. Fail loudly rather than
            // silently storing nothing.
            throw new IllegalStateException("No JPEG writer available in this JVM");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(JPEG_QUALITY);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), params);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not re-encode image as JPEG", e);
        } finally {
            writer.dispose();
        }
    }

    /** data is always a fully-formed, independent byte array — never a
     * view over the caller's original array — and contentType is always
     * "image/jpeg" unless compression was skipped (see compress() above),
     * in which case both fields are simply the original input unchanged. */
    public record ProcessedImage(byte[] data, String contentType) {}
}
