package com.freshcutz.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
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

    /** An admin thumbnail is never bigger than this many bytes (about 1 KB). The admin cards show the photo ~72px
     * wide, so a small, soft preview is enough. To make them even smaller, lower this number (below ~800 they start
     * to look blocky); for sharper, raise it (e.g. 1600 or 2000) — the sizes/qualities below adapt automatically. */
    public static final int THUMB_MAX_BYTES = 1200;
    /** Sides to try, biggest first, and for each the JPEG qualities to try, best first; the first that fits wins. */
    private static final int[] THUMB_SIZES = {112, 96, 80, 72, 64, 56, 48, 40, 32};
    private static final float[] THUMB_QUALITIES = {0.5f, 0.4f, 0.3f, 0.2f};

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

        BufferedImage scaled = scaleDown(source, MAX_DIMENSION);
        byte[] jpegBytes = toJpeg(scaled, JPEG_QUALITY);
        log.info("Compressed product photo: {} bytes ({}) -> {} bytes (image/jpeg)",
                original.length, originalContentType, jpegBytes.length);
        return new ProcessedImage(jpegBytes, "image/jpeg");
    }

    /** A tiny square preview (centre of the photo, about 1 KB) for the admin Products page, which shows photos
     * only ~72px wide. Tries a sharper/bigger version first and steps down until it fits in THUMB_MAX_BYTES. It is
     * stored next to the photo (products.preview_data), so the admin page reads only about 1 KB per product
     * from the database instead of the whole photo. A photo that can't be decoded (e.g. WEBP) is returned unchanged. */
    public ProcessedImage thumbnail(byte[] stored, String storedContentType) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(stored));
        } catch (IOException | RuntimeException e) {
            return new ProcessedImage(stored, storedContentType);
        }
        if (source == null) {
            return new ProcessedImage(stored, storedContentType);
        }

        int side = Math.min(source.getWidth(), source.getHeight());
        BufferedImage square = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        byte[] smallest = null;
        for (int size : THUMB_SIZES) {
            BufferedImage small = scaleSmooth(square, Math.min(size, side));
            for (float quality : THUMB_QUALITIES) {
                byte[] jpeg = toJpeg(small, quality, true);
                if (jpeg.length <= THUMB_MAX_BYTES) return new ProcessedImage(jpeg, "image/jpeg");
                if (smallest == null || jpeg.length < smallest.length) smallest = jpeg;
            }
        }
        return new ProcessedImage(smallest, "image/jpeg"); // practically never reached; still tiny
    }

    private BufferedImage scaleDown(BufferedImage source, int maxDimension) {
        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, maxDimension / (double) Math.max(width, height));
        return scaleTo(source, Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)));
    }

    /** Shrinks a square a lot without the harsh, noisy look of one big jump: halves it again and again, then
     * makes the last small step. Smoother pictures also compress to far fewer bytes. */
    private BufferedImage scaleSmooth(BufferedImage square, int size) {
        BufferedImage current = square;
        while (current.getWidth() / 2 >= size) {
            int half = current.getWidth() / 2;
            BufferedImage next = new BufferedImage(half, half, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = next.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(current, 0, 0, half, half, null);
            } finally {
                g.dispose();
            }
            current = next;
        }
        return current.getWidth() == size ? current : scaleTo(current, size, size);
    }

    private BufferedImage scaleTo(BufferedImage source, int targetWidth, int targetHeight) {
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

    private byte[] toJpeg(BufferedImage image, float quality) {
        return toJpeg(image, quality, false);
    }

    private byte[] toJpeg(BufferedImage image, float quality, boolean optimizeTables) {
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
            params.setCompressionQuality(quality);
            if (optimizeTables && params instanceof JPEGImageWriteParam jpegParams) {
                jpegParams.setOptimizeHuffmanTables(true); // smaller file, same picture
            }

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
