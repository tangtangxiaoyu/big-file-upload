package com.example.bigfileupload.service;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FileUploadService {
    public enum MergeResult {
        NOT_READY,
        MERGED,
        ALREADY_MERGED
    }

    @Value("${app.upload.dir:./files}")
    private String uploadDir;

    @Value("${app.chunk.size:1048576}") // Default 1MB
    private long chunkSize;

    private Path rootLocation;
    private final Map<String, Object> mergeLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws IOException {
        rootLocation = Paths.get(uploadDir);
        Files.createDirectories(rootLocation);
        Files.createDirectories(getMetadataDirectory());
    }

    /**
     * Calculate MD5 hash of a file
     */
    public String calculateFileMd5(MultipartFile file) throws IOException {
        return DigestUtils.md5Hex(file.getInputStream());
    }

    /**
     * Calculate MD5 hash of raw bytes
     */
    public String calculateBytesMd5(byte[] bytes) {
        return DigestUtils.md5Hex(bytes);
    }

    /**
     * Check if file with this MD5 already exists
     */
    public boolean isFileExists(String md5) {
        Path metadataPath = getMetadataPath(md5);
        if (!Files.exists(metadataPath)) {
            return false;
        }

        try {
            FileInfo fileInfo = getFileInfoByMd5(md5);
            return fileInfo != null && Files.exists(Paths.get(fileInfo.getPath()));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Save chunk to temporary directory
     */
    public void saveChunk(String md5, int chunkIndex, MultipartFile chunk) throws IOException {
        if (isFileExists(md5)) {
            return;
        }

        Path chunkDir = rootLocation.resolve("chunks").resolve(md5);
        Files.createDirectories(chunkDir);

        Path chunkPath = chunkDir.resolve(String.valueOf(chunkIndex));

        // Delete existing chunk file first (in case of retry)
        if (Files.exists(chunkPath)) {
            Files.delete(chunkPath);
        }

        try (InputStream inputStream = chunk.getInputStream();
             FileOutputStream outputStream = new FileOutputStream(chunkPath.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            // Force flush and sync to disk
            outputStream.flush();
            outputStream.getChannel().force(false);
        }
    }

    public MergeResult completeUploadIfReady(String md5, int totalChunks, String originalFilename) throws IOException {
        Object lock = mergeLocks.computeIfAbsent(md5, key -> new Object());

        synchronized (lock) {
            if (isFileExists(md5)) {
                return MergeResult.ALREADY_MERGED;
            }

            if (!isAllChunksUploaded(md5, totalChunks)) {
                return MergeResult.NOT_READY;
            }

            mergeChunks(md5, totalChunks, originalFilename);
            return MergeResult.MERGED;
        }
    }

    /**
     * Check if all chunks are uploaded
     */
    public boolean isAllChunksUploaded(String md5, int totalChunks) throws IOException {
        Path chunkDir = rootLocation.resolve("chunks").resolve(md5);
        if (!Files.exists(chunkDir)) {
            return false;
        }

        for (int i = 0; i < totalChunks; i++) {
            Path chunkPath = chunkDir.resolve(String.valueOf(i));
            if (!Files.exists(chunkPath)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Merge chunks into final file using the original filename and persist a
     * metadata index keyed by MD5 for duplicate checks.
     */
    public void mergeChunks(String md5, int totalChunks, String originalFilename) throws IOException {
        Path chunkDir = rootLocation.resolve("chunks").resolve(md5);
        String safeFilename = sanitizeFilename(originalFilename);
        Path targetPath = resolveUniqueTargetPath(safeFilename);

        try (OutputStream outputStream = Files.newOutputStream(targetPath)) {
            for (int i = 0; i < totalChunks; i++) {
                Path chunkPath = chunkDir.resolve(String.valueOf(i));
                Files.copy(chunkPath, outputStream);
            }
        }

        writeMetadata(md5, targetPath, safeFilename);

        // Clean up chunks
        deleteChunks(md5);
    }

    /**
     * Delete chunks after merging
     */
    private void deleteChunks(String md5) throws IOException {
        Path chunkDir = rootLocation.resolve("chunks").resolve(md5);
        if (Files.exists(chunkDir)) {
            Files.walk(chunkDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    /**
     * Get file info by MD5
     */
    public FileInfo getFileInfoByMd5(String md5) throws IOException {
        Path metadataPath = getMetadataPath(md5);
        if (!Files.exists(metadataPath)) {
            return null;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(metadataPath)) {
            properties.load(inputStream);
        }

        String storedPath = properties.getProperty("path");
        if (storedPath == null || storedPath.trim().isEmpty()) {
            return null;
        }

        Path filePath = Paths.get(storedPath);
        if (!Files.exists(filePath)) {
            return null;
        }

        String originalFilename = properties.getProperty("originalFilename");
        return new FileInfo(md5, Files.size(filePath), filePath.toString(), originalFilename);
    }

    /**
     * Get file info by original filename
     */
    public FileInfo getFileInfo(String filename) throws IOException {
        Path filePath = rootLocation.resolve(filename);
        if (!Files.exists(filePath)) {
            return null;
        }

        String md5 = calculateBytesMd5(Files.readAllBytes(filePath));

        return new FileInfo(md5, Files.size(filePath), filePath.toString());
    }

    private Path getMetadataDirectory() {
        return rootLocation.resolve("metadata");
    }

    private Path getMetadataPath(String md5) {
        return getMetadataDirectory().resolve(md5 + ".properties");
    }

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            return "unnamed";
        }
        return new File(originalFilename).getName();
    }

    private Path resolveUniqueTargetPath(String safeFilename) {
        int lastDotIndex = safeFilename.lastIndexOf('.');
        String baseName = safeFilename;
        String extension = "";

        if (lastDotIndex > 0) {
            baseName = safeFilename.substring(0, lastDotIndex);
            extension = safeFilename.substring(lastDotIndex);
        }

        Path targetPath = rootLocation.resolve(safeFilename);
        int counter = 1;
        while (Files.exists(targetPath)) {
            targetPath = rootLocation.resolve(baseName + "_" + counter++ + extension);
        }
        return targetPath;
    }

    private void writeMetadata(String md5, Path targetPath, String originalFilename) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("path", targetPath.toString());
        properties.setProperty("originalFilename", originalFilename);

        try (OutputStream outputStream = Files.newOutputStream(getMetadataPath(md5))) {
            properties.store(outputStream, null);
        }
    }

    public static class FileInfo {
        private String md5;
        private long size;
        private String path;
        private String originalFilename;

        public FileInfo(String md5, long size, String path) {
            this.md5 = md5;
            this.size = size;
            this.path = path;
        }

        public FileInfo(String md5, long size, String path, String originalFilename) {
            this.md5 = md5;
            this.size = size;
            this.path = path;
            this.originalFilename = originalFilename;
        }

        // Getters
        public String getMd5() { return md5; }
        public long getSize() { return size; }
        public String getPath() { return path; }
        public String getOriginalFilename() { return originalFilename; }
    }
}
