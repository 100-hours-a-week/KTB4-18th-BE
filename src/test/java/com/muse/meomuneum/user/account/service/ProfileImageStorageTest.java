package com.muse.meomuneum.user.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.muse.meomuneum.user.account.exception.UserAccountException;

class ProfileImageStorageTest {

    @TempDir
    Path root;

    @Test
    void detectsBytesIgnoresFilenameAndNormalizesPngAndJpegProportionally() throws Exception {
        ProfileImageStorage storage = storage();
        for (String format : new String[]{"PNG", "JPEG"}) {
            String url = storage.store(1L, image(format, 1024, 256));
            assertThat(url).matches("/api/v1/users/me/profile-image/[0-9a-f-]{36}\\.png");
            byte[] bytes = storage.read(1L, url);
            assertThat(bytes).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
            BufferedImage output = ImageIO.read(new ByteArrayInputStream(bytes));
            assertThat(output.getWidth()).isEqualTo(512);
            assertThat(output.getHeight()).isEqualTo(128);
            try (var files = Files.list(root.resolve("1"))) {
                assertThat(files.count()).isGreaterThanOrEqualTo(1);
            }
        }
    }

    @Test
    void removesSourceTextMetadata() throws Exception {
        BufferedImage source = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("PNG").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    new javax.imageio.ImageTypeSpecifier(source), writer.getDefaultWriteParam());
            IIOMetadataNode tree = (IIOMetadataNode) metadata.getAsTree("javax_imageio_png_1.0");
            IIOMetadataNode text = new IIOMetadataNode("tEXt");
            IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Comment");
            entry.setAttribute("value", "private-source-metadata");
            text.appendChild(entry);
            tree.appendChild(text);
            metadata.setFromTree("javax_imageio_png_1.0", tree);
            writer.write(null, new IIOImage(source, null, metadata), null);
        } finally {
            writer.dispose();
        }
        ProfileImageStorage storage = storage();
        String url = storage.store(1L, new MockMultipartFile("image", bytes.toByteArray()));
        assertThat(new String(storage.read(1L, url), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("private-source-metadata").doesNotContain("tEXt");
    }

    @Test
    void rejectsEmptyMalformedUnsupportedAndOversizedDimensionsBeforeStorage() throws Exception {
        ProfileImageStorage storage = storage();
        for (MockMultipartFile input : new MockMultipartFile[]{new MockMultipartFile("image", new byte[0]),
                new MockMultipartFile("image", "fake.png", "image/png", new byte[]{1, 2, 3}),
                image("GIF", 10, 10), image("PNG", 4097, 1), image("PNG", 4001, 4000)}) {
            assertThatThrownBy(() -> storage.store(1L, input)).isInstanceOfSatisfying(UserAccountException.class,
                    exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        try (var files = Files.list(root)) {
            assertThat(files.count()).isZero();
        }
    }

    @Test
    void rejectsTruncatedSupportedImageAndOversizedBytes() throws Exception {
        ProfileImageStorage storage = storage();
        byte[] valid = image("PNG", 10, 10).getBytes();
        assertThatThrownBy(() -> storage.store(1L,
                new MockMultipartFile("image", java.util.Arrays.copyOf(valid, 33))))
                .isInstanceOf(UserAccountException.class);
        assertThatThrownBy(() -> storage.store(1L,
                new MockMultipartFile("image", new byte[(int) ProfileImageStorage.MAX_BYTES + 1])))
                .isInstanceOfSatisfying(UserAccountException.class,
                        exception -> assertThat(exception.getStatus().value()).isEqualTo(413));
    }

    @Test
    void foreignUuidAndTraversalCannotReadOrDeleteAnotherUsersFile() throws Exception {
        ProfileImageStorage storage = storage();
        String url = storage.store(2L, image("PNG", 10, 10));
        assertThatThrownBy(() -> storage.read(1L, url)).isInstanceOf(UserAccountException.class);
        storage.delete(1L, url);
        storage.delete(2L, ProfileImageStorage.URL_PREFIX + "../outside.png");
        storage.delete(2L, "https://example.com/image.png");
        assertThat(storage.read(2L, url)).isNotEmpty();
        assertThatThrownBy(() -> storage.read(2L, ProfileImageStorage.URL_PREFIX + "../outside.png"))
                .isInstanceOf(UserAccountException.class);
        storage.delete(2L, url);
        assertThatThrownBy(() -> storage.read(2L, url)).isInstanceOf(UserAccountException.class);
    }

    @Test
    void storageFailuresUseGenericMessage() throws Exception {
        Path blocked = root.resolve("blocked");
        Files.writeString(blocked, "file");
        assertThatThrownBy(() -> new ProfileImageStorage(blocked.toString()).store(1L, image("PNG", 10, 10)))
                .isInstanceOfSatisfying(UserAccountException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(exception.getMessage()).isEqualTo("image storage failed");
                });
    }

    @Test
    void normalizesRealLosslessAndLossyWebpRegardlessOfDeclaredType() throws Exception {
        for (String fixture : new String[]{"lossless.webp", "lossy.webp"}) {
            byte[] source;
            try (var input = getClass().getResourceAsStream("/profile-images/" + fixture)) {
                source = input.readAllBytes();
            }
            ProfileImageStorage storage = storage();
            String url = storage.store(1L,
                    new MockMultipartFile("image", "fake.jpg", "image/jpeg", source));
            byte[] png = storage.read(1L, url);
            assertThat(png).startsWith((byte) 137, (byte) 80, (byte) 78, (byte) 71);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
            assertThat(decoded.getWidth()).isEqualTo(32);
            assertThat(decoded.getHeight()).isEqualTo(20);
            java.awt.Color pixel = new java.awt.Color(decoded.getRGB(10, 10), true);
            assertThat(pixel.getRed()).isBetween(25, 35);
            assertThat(pixel.getGreen()).isBetween(175, 185);
            assertThat(pixel.getBlue()).isBetween(65, 75);
            assertThat(pixel.getAlpha()).isEqualTo(255);
        }
    }

    @Test
    void rejectsCorruptWebpAndGifSpoofedAsWebpWithExactMessage() throws Exception {
        byte[] webp;
        try (var input = getClass().getResourceAsStream("/profile-images/lossless.webp")) {
            webp = java.util.Arrays.copyOf(input.readAllBytes(), 24);
        }
        for (byte[] bytes : new byte[][]{webp, image("GIF", 10, 10).getBytes()}) {
            assertThatThrownBy(() -> storage().store(1L,
                    new MockMultipartFile("image", "fake.webp", "image/webp", bytes)))
                    .isInstanceOfSatisfying(UserAccountException.class, exception -> {
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(exception.getMessage()).isEqualTo("JPG, PNG, WEBP 형식의 이미지만 등록할 수 있어요.");
                    });
        }
    }

    @Test
    void acceptsExactlyDecimalTenMegabytesAndRejectsNextByteIncludingHiddenStreamSize() throws Exception {
        assertThat(ProfileImageStorage.MAX_BYTES).isEqualTo(10_000_000L);
        ProfileImageStorage storage = storage();
        byte[] boundary = java.util.Arrays.copyOf(image("PNG", 10, 10).getBytes(), 10_000_000);
        String url = storage.store(1L, new MockMultipartFile("image", boundary));
        assertThat(storage.read(1L, url).length).isLessThan(1000);
        byte[] oversized = java.util.Arrays.copyOf(boundary, 10_000_001);
        MockMultipartFile hiddenSize = new MockMultipartFile("image", oversized) {
            @Override
            public long getSize() {
                return 1;
            }
        };
        MockMultipartFile declaredOversized = new MockMultipartFile("image", boundary) {
            @Override
            public long getSize() {
                return 10_000_001;
            }
        };
        for (MockMultipartFile input : new MockMultipartFile[]{new MockMultipartFile("image", oversized),
                hiddenSize, declaredOversized}) {
            assertThatThrownBy(() -> storage.store(1L, input))
                    .isInstanceOfSatisfying(UserAccountException.class, exception -> {
                        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
                        assertThat(exception.getMessage()).isEqualTo("10MB 이하의 이미지만 등록할 수 있어요.");
                    });
        }
        try (var files = Files.list(root.resolve("1"))) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    private ProfileImageStorage storage() {
        return new ProfileImageStorage(root.toString());
    }

    private MockMultipartFile image(String format, int width, int height) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, bytes);
        return new MockMultipartFile("image", "../../wrong.svg", "application/octet-stream", bytes.toByteArray());
    }
}
