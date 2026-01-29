package com.sufi.indgoveservices.services;

import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

@Service
public class ImageService {

    // Keep the previous signature for compatibility and delegate to new method with centerCrop=false
    public byte[] processImage(InputStream inputStream, int targetWidth, int targetHeight, float maxSizeKB) throws IOException {
        return processImage(inputStream, targetWidth, targetHeight, maxSizeKB, false);
    }

    /**
     * Process the input image into a JPEG that matches the requested pixel dimensions
     * and maximum file size. Uses only core Java ImageIO and AWT.
     *
     * @param inputStream input image stream (any readable image format)
     * @param targetWidth required output width in pixels
     * @param targetHeight required output height in pixels
     * @param maxSizeKB maximum allowed filesize in kilobytes
     * @param centerCrop true => scale-to-cover (may crop), false => scale-to-fit (full image)
     * @return JPEG bytes ready for download
     * @throws IOException when reading or writing image data fails
     */
    public byte[] processImage(InputStream inputStream, int targetWidth, int targetHeight, float maxSizeKB, boolean centerCrop) throws IOException {
        BufferedImage src = ImageIO.read(inputStream);
        if (src == null) {
            throw new IOException("Invalid or unsupported image file");
        }

        // Convert source to a known RGB type (remove alpha) using white background
        BufferedImage srcRgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D gSrc = srcRgb.createGraphics();
        gSrc.setColor(Color.WHITE);
        gSrc.fillRect(0, 0, srcRgb.getWidth(), srcRgb.getHeight());
        gSrc.drawImage(src, 0, 0, null);
        gSrc.dispose();

        // Calculate scaling to fit or cover the source into target while preserving aspect ratio
        double scale;
        if (centerCrop) {
            // COVER (scale so target is fully covered by image; may crop edges)
            scale = Math.max((double) targetWidth / srcRgb.getWidth(), (double) targetHeight / srcRgb.getHeight());
        } else {
            // CONTAIN (fit inside target; no cropping)
            scale = Math.min((double) targetWidth / srcRgb.getWidth(), (double) targetHeight / srcRgb.getHeight());
        }

        int drawW = Math.max(1, (int) Math.round(srcRgb.getWidth() * scale));
        int drawH = Math.max(1, (int) Math.round(srcRgb.getHeight() * scale));

        // Create the target canvas with white background (JPEG doesn't support alpha)
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            // White background
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, targetWidth, targetHeight);

            // High quality rendering hints
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Center the scaled image. If drawW/drawH exceed target, parts will be cropped automatically.
            int x = (targetWidth - drawW) / 2;
            int y = (targetHeight - drawH) / 2;
            g.drawImage(srcRgb, x, y, drawW, drawH, null);

        } finally {
            g.dispose();
        }

        // Compress to JPEG and attempt to meet file size constraint by reducing quality
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter available");
        }
        ImageWriter writer = writers.next();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Start from high quality and step down
        float quality = 0.95f;
        float minQuality = 0.05f;
        float step = 0.05f;

        ImageWriteParam param = writer.getDefaultWriteParam();
        boolean canCompress = param.canWriteCompressed();

        // If writer supports compression, loop to reduce quality until size satisfied
        if (canCompress) {
            while (quality >= minQuality) {
                baos.reset();
                ImageWriteParam iwParam = writer.getDefaultWriteParam();
                iwParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                iwParam.setCompressionQuality(quality);

                ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(target, null, null), iwParam);
                ios.close();

                double sizeKB = baos.size() / 1024.0;
                if (sizeKB <= maxSizeKB) {
                    break; // satisfied
                }
                quality -= step;
            }
        } else {
            // Fallback: write once with default params
            ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(target, null, null), param);
            ios.close();
        }

        writer.dispose();

        return baos.toByteArray();
    }
}