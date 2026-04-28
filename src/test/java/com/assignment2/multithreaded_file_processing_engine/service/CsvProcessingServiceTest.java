package com.assignment2.multithreaded_file_processing_engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import com.assignment2.multithreaded_file_processing_engine.entity.CsvJob;
import com.assignment2.multithreaded_file_processing_engine.entity.JobState;
import com.assignment2.multithreaded_file_processing_engine.repository.CsvJobRepository;
import com.assignment2.multithreaded_file_processing_engine.repository.ImportedUserRepository;

class CsvProcessingServiceTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void returnsExistingJobIdWhenSameFileUploadedTwice() throws Exception {
        Map<String, CsvJob> jobsByHash = new HashMap<>();
        CsvJob existingJob = CsvJob.queued("hash", "users.csv");
        existingJob.setId("existing-job");
        existingJob.setStatus(JobState.JobStatus.COMPLETED.name());

        CsvJobRepository jobRepository = csvJobRepositoryStub(
            jobsByHash,
            Map.of(existingJob.getId(), existingJob),
            Optional.of(existingJob)
        );
        ImportedUserRepository importedUserRepository = importedUserRepositoryStub();
        SseEmitterRegistry emitterRegistry = new SseEmitterRegistry();

        CsvProcessingService service = new CsvProcessingService(
            executor,
            emitterRegistry,
            jobRepository,
            importedUserRepository
        );
        ReflectionTestUtils.setField(service, "chunkSize", 2);

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "users.csv",
            "text/csv",
            "name,email\nAlice,alice@example.com\n".getBytes()
        );

        String jobId = service.submitJob(file);

        assertEquals("existing-job", jobId);
    }

    @Test
    void getJobStateFallsBackToPersistedJob() {
        CsvJob persisted = CsvJob.queued("hash", "users.csv");
        persisted.setId("job-42");
        persisted.setStatus(JobState.JobStatus.PROCESSING.name());
        persisted.setTotalRows(1000);
        persisted.setProcessed(250);
        persisted.setFailed(3);

        CsvJobRepository jobRepository = csvJobRepositoryStub(
            new HashMap<>(),
            Map.of("job-42", persisted),
            Optional.empty()
        );
        ImportedUserRepository importedUserRepository = importedUserRepositoryStub();
        SseEmitterRegistry emitterRegistry = new SseEmitterRegistry();

        CsvProcessingService service = new CsvProcessingService(
            executor,
            emitterRegistry,
            jobRepository,
            importedUserRepository
        );
        ReflectionTestUtils.setField(service, "chunkSize", 2);

        Optional<JobState> state = service.getJobState("job-42");

        assertTrue(state.isPresent());
        assertEquals(250, state.get().getProcessedRows().get());
        assertEquals(3, state.get().getFailedRows().get());
        assertEquals(25, state.get().getProgressPercent());
    }

    private CsvJobRepository csvJobRepositoryStub(Map<String, CsvJob> byHash,
                                                  Map<String, CsvJob> byId,
                                                  Optional<CsvJob> anyHashResult) {
        return (CsvJobRepository) Proxy.newProxyInstance(
            CsvJobRepository.class.getClassLoader(),
            new Class<?>[]{CsvJobRepository.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "findByFileHash" -> anyHashResult.isPresent()
                    ? anyHashResult
                    : Optional.ofNullable(byHash.get(args[0]));
                case "findById" -> Optional.ofNullable(byId.get(args[0]));
                case "save" -> {
                    CsvJob job = (CsvJob) args[0];
                    byId.put(job.getId(), job);
                    byHash.put(job.getFileHash(), job);
                    yield job;
                }
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "CsvJobRepositoryStub";
                default -> null;
            }
        );
    }

    private ImportedUserRepository importedUserRepositoryStub() {
        return (ImportedUserRepository) Proxy.newProxyInstance(
            ImportedUserRepository.class.getClassLoader(),
            new Class<?>[]{ImportedUserRepository.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "existsByRowFingerprint" -> false;
                case "save" -> args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "ImportedUserRepositoryStub";
                default -> null;
            }
        );
    }
}
