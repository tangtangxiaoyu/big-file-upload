package com.example.bigfileupload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FileUploadControllerTest {

    @TempDir
    static Path tempDir;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.example.bigfileupload.service.FileUploadService fileUploadService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("app.upload.dir", () -> tempDir.toString());
    }

    @Test
    void doesNotMergeWhenOnlyLastChunkArrives() throws Exception {
        MockMultipartFile lastChunk = new MockMultipartFile(
                "chunk",
                "chunk_1",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "world".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/file/chunk")
                        .file(lastChunk)
                        .param("md5", "file-md5")
                        .param("chunkIndex", "1")
                        .param("totalChunks", "2")
                        .param("fileName", "demo.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploaded", is(false)))
                .andExpect(jsonPath("$.message", is("Chunk uploaded successfully")));

        Path chunkPath = tempDir.resolve("chunks").resolve("file-md5").resolve("1");
        Path mergedFilePath = tempDir.resolve("demo.txt");

        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(chunkPath));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(mergedFilePath));
    }

    @Test
    void mergesAfterAllChunksArrive() throws Exception {
        MockMultipartFile firstChunk = new MockMultipartFile(
                "chunk",
                "chunk_0",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "hello ".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile secondChunk = new MockMultipartFile(
                "chunk",
                "chunk_1",
                MediaType.APPLICATION_OCTET_STREAM_VALUE,
                "world".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/file/chunk")
                        .file(secondChunk)
                        .param("md5", "merge-md5")
                        .param("chunkIndex", "1")
                        .param("totalChunks", "2")
                        .param("fileName", "hello.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploaded", is(false)));

        mockMvc.perform(multipart("/api/file/chunk")
                        .file(firstChunk)
                        .param("md5", "merge-md5")
                        .param("chunkIndex", "0")
                        .param("totalChunks", "2")
                        .param("fileName", "hello.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uploaded", is(true)))
                .andExpect(jsonPath("$.message", is("File upload completed and merged")));

        Path mergedFilePath = tempDir.resolve("hello.txt");
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(mergedFilePath));
        org.junit.jupiter.api.Assertions.assertEquals("hello world", new String(Files.readAllBytes(mergedFilePath), StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(tempDir.resolve("metadata").resolve("merge-md5.properties")));
    }

    @Test
    void checkEndpointFindsPreviouslyUploadedFileByMd5() throws Exception {
        byte[] content = "already uploaded".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "existing.txt",
                MediaType.TEXT_PLAIN_VALUE,
                content
        );

        mockMvc.perform(multipart("/api/file/upload")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)));

        String md5 = org.apache.commons.codec.digest.DigestUtils.md5Hex(content);

        mockMvc.perform(post("/api/file/check")
                        .param("md5", md5)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists", is(true)))
                .andExpect(jsonPath("$.fileInfo", notNullValue()))
                .andExpect(jsonPath("$.fileInfo.size", is(content.length)))
                .andExpect(jsonPath("$.fileInfo.originalFilename", is("existing.txt")));
    }

    @Test
    void concurrentCompletionProducesOnlyOneMergedFile() throws Exception {
        String md5 = "race-md5";
        fileUploadService.saveChunk(md5, 0, new MockMultipartFile(
                "chunk", "chunk_0", MediaType.APPLICATION_OCTET_STREAM_VALUE, "hello ".getBytes(StandardCharsets.UTF_8)
        ));
        fileUploadService.saveChunk(md5, 1, new MockMultipartFile(
                "chunk", "chunk_1", MediaType.APPLICATION_OCTET_STREAM_VALUE, "world".getBytes(StandardCharsets.UTF_8)
        ));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<?> first = executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            if (fileUploadService.isAllChunksUploaded(md5, 2)) {
                fileUploadService.mergeChunks(md5, 2, "race.txt");
            }
            return null;
        });

        Future<?> second = executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            if (fileUploadService.isAllChunksUploaded(md5, 2)) {
                fileUploadService.mergeChunks(md5, 2, "race.txt");
            }
            return null;
        });

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        first.get(5, TimeUnit.SECONDS);
        second.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(tempDir.resolve("race.txt")));
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(tempDir.resolve("race_1.txt")));
    }

    @Test
    void onlyOneConcurrentRequestReportsMerged() throws Exception {
        String md5 = "merge-once-md5";
        fileUploadService.saveChunk(md5, 0, new MockMultipartFile(
                "chunk", "chunk_0", MediaType.APPLICATION_OCTET_STREAM_VALUE, "hello ".getBytes(StandardCharsets.UTF_8)
        ));
        fileUploadService.saveChunk(md5, 1, new MockMultipartFile(
                "chunk", "chunk_1", MediaType.APPLICATION_OCTET_STREAM_VALUE, "world".getBytes(StandardCharsets.UTF_8)
        ));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Future<com.example.bigfileupload.service.FileUploadService.MergeResult> first = executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return fileUploadService.completeUploadIfReady(md5, 2, "merge-once.txt");
        });

        Future<com.example.bigfileupload.service.FileUploadService.MergeResult> second = executor.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return fileUploadService.completeUploadIfReady(md5, 2, "merge-once.txt");
        });

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        com.example.bigfileupload.service.FileUploadService.MergeResult firstResult = first.get(5, TimeUnit.SECONDS);
        com.example.bigfileupload.service.FileUploadService.MergeResult secondResult = second.get(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        int mergedCount = 0;
        if (firstResult == com.example.bigfileupload.service.FileUploadService.MergeResult.MERGED) {
            mergedCount++;
        }
        if (secondResult == com.example.bigfileupload.service.FileUploadService.MergeResult.MERGED) {
            mergedCount++;
        }

        org.junit.jupiter.api.Assertions.assertEquals(1, mergedCount);
    }
}
