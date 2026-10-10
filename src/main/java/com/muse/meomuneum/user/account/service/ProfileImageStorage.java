package com.muse.meomuneum.user.account.service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.muse.meomuneum.user.account.exception.UserAccountException;

@Component
public class ProfileImageStorage {

    static final long MAX_BYTES = 10_000_000L;
    public static final String INVALID_IMAGE_MESSAGE = "JPG, PNG, WEBP 형식의 이미지만 등록할 수 있어요.";
    public static final String IMAGE_SIZE_MESSAGE = "10MB 이하의 이미지만 등록할 수 있어요.";
    static final String URL_PREFIX = "/api/v1/users/me/profile-image/";
    private final Path root;

    public ProfileImageStorage(
            @Value("${user.profile-image.storage-directory:${user.home}/.meomuneum/profile-images}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public String store(Long userId, MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw invalid();
        }
        if (image.getSize() > MAX_BYTES) {
            throw new UserAccountException("USER_IMAGE_SIZE", HttpStatus.CONTENT_TOO_LARGE, IMAGE_SIZE_MESSAGE);
        }
        byte[] bytes;
        try (var input = image.getInputStream()) {
            bytes = input.readNBytes((int) MAX_BYTES + 1);
        } catch (IOException exception) {
            throw storageFailure();
        }
        if (bytes.length > MAX_BYTES) {
            throw new UserAccountException("USER_IMAGE_SIZE", HttpStatus.CONTENT_TOO_LARGE, IMAGE_SIZE_MESSAGE);
        }
        BufferedImage normalized = normalize(bytes);
        String filename = UUID.randomUUID() + ".png";
        Path temporary = null;
        try {
            Path directory = directory(userId);
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)) {
                throw storageFailure();
            }
            temporary = Files.createTempFile(directory, "upload-", ".tmp");
            if (!ImageIO.write(normalized, "PNG", temporary.toFile())) {
                throw storageFailure();
            }
            Path destination = directory.resolve(filename);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
            return URL_PREFIX + filename;
        } catch (IOException exception) {
            throw storageFailure();
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    // A failed temporary-file cleanup must not change the upload result.
                }
            }
        }
    }

    public byte[] read(Long userId, String url) {
        Path path = managedPath(userId, url);
        if (path == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw notFound();
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw notFound();
        }
    }

    public void delete(Long userId, String url) {
        Path path = managedPath(userId, url);
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                throw storageFailure();
            }
        }
    }

    private Path managedPath(Long userId, String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return null;
        }
        String filename = url.substring(URL_PREFIX.length());
        if (!filename.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png")) {
            return null;
        }
        Path directory = directory(userId);
        if (Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)) {
            return null;
        }
        return directory.resolve(filename);
    }

    private Path directory(Long userId) {
        if (userId == null || userId <= 0) {
            throw notFound();
        }
        return root.resolve(userId.toString());
    }

    private BufferedImage normalize(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"JPEG".equalsIgnoreCase(format) && !"PNG".equalsIgnoreCase(format) &&
                        !"WEBP".equalsIgnoreCase(format)) {
                    throw invalid();
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width > 4096 || height > 4096 || (long) width * height > 16000000) {
                    throw invalid();
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null) {
                    throw invalid();
                }
                double scale = Math.min(1.0, 512.0 / Math.max(width, height));
                BufferedImage result = new BufferedImage(Math.max(1, (int) Math.round(width * scale)),
                        Math.max(1, (int) Math.round(height * scale)), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = result.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(decoded, 0, 0, result.getWidth(), result.getHeight(), null);
                } finally {
                    graphics.dispose();
                }
                return result;
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private UserAccountException invalid() {
        return new UserAccountException("USER_IMAGE_INVALID", HttpStatus.BAD_REQUEST, INVALID_IMAGE_MESSAGE);
    }

    private UserAccountException storageFailure() {
        return new UserAccountException("USER_IMAGE_STORAGE", HttpStatus.INTERNAL_SERVER_ERROR, "image storage failed");
    }

    private UserAccountException notFound() {
        return new UserAccountException("USER_IMAGE_NOT_FOUND", HttpStatus.NOT_FOUND, "image not found");
    }
}
