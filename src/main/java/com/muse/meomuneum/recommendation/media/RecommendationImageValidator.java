package com.muse.meomuneum.recommendation.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.stereotype.Component;

import com.muse.meomuneum.recommendation.exception.RecommendationException;

@Component
public class RecommendationImageValidator {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final long MAX_PIXELS = 60_000_000L;

    public void validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new RecommendationException(400, "invalid image recommendation request");
        }
        if (bytes.length > MAX_BYTES) {
            throw new RecommendationException(413, "image too large");
        }
        if (bytes.length < 3 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8
                || (bytes[2] & 0xff) != 0xff) {
            throw new RecommendationException(415, "unsupported image format");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new RecommendationException(415, "unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) {
                    throw new RecommendationException(415, "unsupported image format");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new RecommendationException(400, "invalid image dimensions");
                }
                if ((long) width * height > MAX_PIXELS) {
                    throw new RecommendationException(413, "image too large");
                }
                var readParam = reader.getDefaultReadParam();
                readParam.setSourceSubsampling(8, 8, 0, 0);
                if (reader.read(0, readParam) == null) {
                    throw new RecommendationException(415, "unsupported image format");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof RecommendationException recommendationException) {
                throw recommendationException;
            }
            throw new RecommendationException(415, "unsupported image format");
        }
    }
}
