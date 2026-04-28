package com.assignment2.multithreaded_file_processing_engine.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.assignment2.multithreaded_file_processing_engine.dto.JobStatusResponse;
import com.assignment2.multithreaded_file_processing_engine.entity.CsvJob;
import com.assignment2.multithreaded_file_processing_engine.entity.ImportedUser;
import com.assignment2.multithreaded_file_processing_engine.entity.JobState;
import com.assignment2.multithreaded_file_processing_engine.repository.CsvJobRepository;
import com.assignment2.multithreaded_file_processing_engine.repository.ImportedUserRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CsvProcessingService {

    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private final ExecutorService csvProcessorExecutor;
    private final SseEmitterRegistry emitterRegistry;
    private final CsvJobRepository csvJobRepository;
    private final ImportedUserRepository importedUserRepository;

    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    @Value("${csv.processor.chunk-size:1000}")
    private int chunkSize;

    public String submitJob(MultipartFile file) throws IOException {
        StagedUpload stagedUpload = stageUpload(file);
        Optional<CsvJob> existingJob = csvJobRepository.findByFileHash(stagedUpload.fileHash());
        if (existingJob.isPresent()) {
            Files.deleteIfExists(stagedUpload.path());
            cacheState(existingJob.get());
            return existingJob.get().getId();
        }

        CsvJob job = CsvJob.queued(stagedUpload.fileHash(), file.getOriginalFilename());
        csvJobRepository.save(job);
        jobs.put(job.getId(), new JobState(job.getId(), 0));

        Thread.ofPlatform()
            .name("csv-job-" + job.getId())
            .start(() -> processFile(job.getId(), stagedUpload.path()));

        return job.getId();
    }

    public Optional<JobState> getJobState(String jobId) {
        JobState inMemory = jobs.get(jobId);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }

        return csvJobRepository.findById(jobId).map(this::cacheState);
    }

    public JobStatusResponse toResponse(JobState state) {
        return new JobStatusResponse(
            state.getJobId(),
            state.getStatus().name(),
            state.getTotalRows(),
            state.getProcessedRows().get(),
            state.getFailedRows().get(),
            state.getProgressPercent(),
            state.getErrorMessage()
        );
    }

    public SseEmitter register(String jobId) {
        SseEmitter emitter = emitterRegistry.register(jobId);
        getJobState(jobId).ifPresent(state -> {
            try {
                emitterRegistry.send(emitter, buildEvent(state));
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private void processFile(String jobId, Path stagedFile) {
        JobState state = jobs.computeIfAbsent(jobId, id -> new JobState(id, 0));

        try {
            int totalRows = countRows(stagedFile);
            state.setTotalRows(totalRows);
            updateStatus(jobId, JobState.JobStatus.PROCESSING, null, totalRows);
            emitterRegistry.sendUpdate(jobId, buildEvent(state));

            List<CompletableFuture<ChunkResult>> futures = new ArrayList<>();

            try (CSVReader reader = new CSVReader(Files.newBufferedReader(stagedFile))) {
                reader.readNext();

                List<String[]> chunk = new ArrayList<>(chunkSize);
                String[] row;
                while ((row = reader.readNext()) != null) {
                    chunk.add(row);
                    if (chunk.size() == chunkSize) {
                        futures.add(dispatchChunk(jobId, state, List.copyOf(chunk)));
                        chunk.clear();
                    }
                }
                if (!chunk.isEmpty()) {
                    futures.add(dispatchChunk(jobId, state, List.copyOf(chunk)));
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            updateStatus(jobId, JobState.JobStatus.COMPLETED, null, state.getTotalRows());
            emitterRegistry.sendUpdate(jobId, buildEvent(state));
            emitterRegistry.complete(jobId);
        } catch (Exception ex) {
            log.error("Job {} failed", jobId, ex);
            updateStatus(jobId, JobState.JobStatus.FAILED, ex.getMessage(), state.getTotalRows());
            emitterRegistry.sendUpdate(jobId, buildEvent(state));
            emitterRegistry.complete(jobId);
        } finally {
            try {
                Files.deleteIfExists(stagedFile);
            } catch (IOException cleanupException) {
                log.warn("Could not delete staged file {}", stagedFile, cleanupException);
            }
        }
    }

    private CompletableFuture<ChunkResult> dispatchChunk(String jobId, JobState state, List<String[]> chunk) {
        return CompletableFuture.supplyAsync(() -> processChunk(jobId, chunk), csvProcessorExecutor)
            .whenComplete((result, throwable) -> {
                if (throwable != null) {
                    throw new IllegalStateException(throwable);
                }
                state.getProcessedRows().addAndGet(result.processed());
                state.getFailedRows().addAndGet(result.failed());
                persistProgress(jobId, state);
            });
    }

    private ChunkResult processChunk(String jobId, List<String[]> chunk) {
        int processed = 0;
        int failed = 0;

        for (String[] row : chunk) {
            processed++;
            try {
                processRow(jobId, row);
            } catch (IllegalArgumentException ex) {
                failed++;
                log.warn("Skipping invalid row for job {}: {}", jobId, String.join(",", row));
            } catch (Exception ex) {
                failed++;
                log.warn("Row failed for job {}", jobId, ex);
            }
        }

        return new ChunkResult(processed, failed);
    }

    private void processRow(String jobId, String[] row) {
        if (row.length < 2) {
            throw new IllegalArgumentException("Expected name and email");
        }

        String name = clean(row[0]);
        String email = clean(row[1]).toLowerCase();
        if (email.isBlank() || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email");
        }

        String fingerprint = sha256(email + "|" + name);
        if (importedUserRepository.existsByRowFingerprint(fingerprint)) {
            return;
        }

        ImportedUser user = new ImportedUser();
        user.setJobId(jobId);
        user.setName(name.isBlank() ? null : name);
        user.setEmail(email);
        user.setRowFingerprint(fingerprint);

        try {
            importedUserRepository.save(user);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("Duplicate row fingerprint {} ignored", fingerprint);
        }
    }

    private int countRows(Path stagedFile) throws IOException, CsvException {
        try (CSVReader reader = new CSVReader(Files.newBufferedReader(stagedFile))) {
            reader.readNext();
            int total = 0;
            while (reader.readNext() != null) {
                total++;
            }
            return total;
        }
    }

    private synchronized void persistProgress(String jobId, JobState state) {
        csvJobRepository.findById(jobId).ifPresent(job -> {
            job.setProcessed(state.getProcessedRows().get());
            job.setFailed(state.getFailedRows().get());
            job.setTotalRows(state.getTotalRows());
            job.setUpdatedAt(Instant.now());
            csvJobRepository.save(job);
            if (state.getProcessedRows().get() % 100 == 0 || state.getProcessedRows().get() == state.getTotalRows()) {
                emitterRegistry.sendUpdate(jobId, buildEvent(state));
            }
        });
    }

    private synchronized void updateStatus(String jobId, JobState.JobStatus status, String errorMessage, int totalRows) {
        JobState state = jobs.computeIfAbsent(jobId, id -> new JobState(id, totalRows));
        state.setStatus(status);
        state.setErrorMessage(errorMessage);
        state.setTotalRows(totalRows);

        csvJobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(status.name());
            job.setErrorMessage(errorMessage);
            job.setTotalRows(totalRows);
            job.setUpdatedAt(Instant.now());
            csvJobRepository.save(job);
        });
    }

    private Map<String, Object> buildEvent(JobState state) {
        return Map.of(
            "jobId", state.getJobId(),
            "status", state.getStatus().name(),
            "processed", state.getProcessedRows().get(),
            "failed", state.getFailedRows().get(),
            "total", state.getTotalRows(),
            "progress", state.getProgressPercent(),
            "errorMessage", state.getErrorMessage() == null ? "" : state.getErrorMessage()
        );
    }

    private JobState cacheState(CsvJob job) {
        JobState state = new JobState(job.getId(), job.getTotalRows());
        state.getProcessedRows().set(job.getProcessed());
        state.getFailedRows().set(job.getFailed());
        state.setStatus(JobState.JobStatus.valueOf(job.getStatus()));
        state.setErrorMessage(job.getErrorMessage());
        jobs.put(job.getId(), state);
        return state;
    }

    private StagedUpload stageUpload(MultipartFile file) throws IOException {
        MessageDigest digest = messageDigest();
        Path tempFile = Files.createTempFile("csv-upload-", ".tmp");

        try (
            InputStream inputStream = file.getInputStream();
            DigestInputStream digestStream = new DigestInputStream(inputStream, digest);
            OutputStream outputStream = Files.newOutputStream(tempFile)
        ) {
            digestStream.transferTo(outputStream);
        } catch (IOException ex) {
            Files.deleteIfExists(tempFile);
            throw ex;
        }

        return new StagedUpload(tempFile, HexFormat.of().formatHex(digest.digest()));
    }

    private MessageDigest messageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private String sha256(String value) {
        MessageDigest digest = messageDigest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record StagedUpload(Path path, String fileHash) {
    }

    private record ChunkResult(int processed, int failed) {
    }
}
