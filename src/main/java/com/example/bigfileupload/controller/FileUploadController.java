package com.example.bigfileupload.controller;

import com.example.bigfileupload.service.FileUploadService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/file")
public class FileUploadController {

    @Autowired
    private FileUploadService fileUploadService;

    /**
     * Check if file already exists by MD5
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkFile(@RequestParam String md5) {
        Map<String, Object> response = new HashMap<>();

        try {
            boolean exists = fileUploadService.isFileExists(md5);

            if (exists) {
                FileUploadService.FileInfo fileInfo = fileUploadService.getFileInfoByMd5(md5);
                response.put("exists", true);
                response.put("message", "文件已存在");
                response.put("fileInfo", fileInfo);
            } else {
                response.put("exists", false);
                response.put("message", "File does not exist");
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Upload file chunk
     */
    @PostMapping("/chunk")
    public ResponseEntity<Map<String, Object>> uploadChunk(
            @RequestHeader(value = "Content-Range", required = false) String contentRange,
            @RequestParam String md5,
            @RequestParam int chunkIndex,
            @RequestParam int totalChunks,
            @RequestParam String fileName,
            @RequestParam MultipartFile chunk) {

        Map<String, Object> response = new HashMap<>();

        try {
            // 基础校验：防止空文件上传
            if (chunk.isEmpty()) {
                response.put("error", "Chunk file cannot be empty");
                return ResponseEntity.badRequest().body(response);
            }

            // Save the chunk
            fileUploadService.saveChunk(md5, chunkIndex, chunk);

            // Merge only after every chunk exists. With concurrent uploads,
            // the highest index can arrive before earlier chunks.
            FileUploadService.MergeResult mergeResult = fileUploadService.completeUploadIfReady(md5, totalChunks, fileName);

            if (mergeResult == FileUploadService.MergeResult.MERGED) {
                // 直接删除了错误的 sleep(100)
                response.put("uploaded", true);
                response.put("message", "File upload completed and merged");

                FileUploadService.FileInfo fileInfo = fileUploadService.getFileInfoByMd5(md5);
                response.put("fileInfo", fileInfo);
            } else {
                response.put("uploaded", false);
                response.put("message", "Chunk uploaded successfully");
                response.put("progress", ((double)(chunkIndex + 1) / totalChunks) * 100);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(500).body(response);
        }
    }

    /**
     * Handle single file upload (for small files)
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam MultipartFile file) {
        Map<String, Object> response = new HashMap<>();

        try {
            // 基础校验：防止空文件
            if (file.isEmpty()) {
                response.put("error", "File cannot be empty");
                return ResponseEntity.badRequest().body(response);
            }

            // Calculate MD5
            String md5 = fileUploadService.calculateFileMd5(file);

            // Check if file already exists
            if (fileUploadService.isFileExists(md5)) {
                response.put("success", true);
                response.put("message", "File already exists");
                FileUploadService.FileInfo fileInfo = fileUploadService.getFileInfoByMd5(md5);
                response.put("fileInfo", fileInfo);
            } else {
                fileUploadService.saveChunk(md5, 0, file);
                fileUploadService.mergeChunks(md5, 1, file.getOriginalFilename());

                response.put("success", true);
                response.put("message", "File uploaded successfully");
                FileUploadService.FileInfo fileInfo = fileUploadService.getFileInfoByMd5(md5);
                response.put("fileInfo", fileInfo);
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }
}
