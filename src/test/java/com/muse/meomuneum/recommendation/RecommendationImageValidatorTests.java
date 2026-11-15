package com.muse.meomuneum.recommendation;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.muse.meomuneum.recommendation.exception.RecommendationException;
import com.muse.meomuneum.recommendation.media.RecommendationImageValidator;

class RecommendationImageValidatorTests {
    private final RecommendationImageValidator validator = new RecommendationImageValidator();

    @Test
    void acceptsDecodableJpeg() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", output);
        assertThatCode(() -> validator.validate(output.toByteArray())).doesNotThrowAnyException();
    }

    @Test
    void rejectsNonJpegBytes() {
        assertThatThrownBy(() -> validator.validate("not an image".getBytes()))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(415);
    }

    @Test
    void rejectsImagesAboveFiveMibBeforeDecode() {
        byte[] tooLarge = new byte[RecommendationImageValidator.MAX_BYTES + 1];
        tooLarge[0] = (byte) 0xff;
        tooLarge[1] = (byte) 0xd8;
        tooLarge[2] = (byte) 0xff;
        assertThatThrownBy(() -> validator.validate(tooLarge))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(413);
    }

    @Test
    void acceptsJpegAtFiveMibBoundary() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", output);
        byte[] jpeg = Arrays.copyOf(output.toByteArray(), RecommendationImageValidator.MAX_BYTES);

        assertThatCode(() -> validator.validate(jpeg)).doesNotThrowAnyException();
    }

    @Test
    void rejectsJpegAboveSixtyMegapixelsBeforePixelDecode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", output);
        byte[] jpeg = output.toByteArray();
        for (int index = 0; index < jpeg.length - 8; index++) {
            if ((jpeg[index] & 0xff) == 0xff && ((jpeg[index + 1] & 0xff) == 0xc0
                    || (jpeg[index + 1] & 0xff) == 0xc2)) {
                jpeg[index + 5] = 0x1d;
                jpeg[index + 6] = 0x4d;
                jpeg[index + 7] = 0x1f;
                jpeg[index + 8] = 0x40;
                break;
            }
        }

        assertThatThrownBy(() -> validator.validate(jpeg))
                .isInstanceOf(RecommendationException.class)
                .extracting(exception -> ((RecommendationException) exception).getStatus()).isEqualTo(413);
    }
}
