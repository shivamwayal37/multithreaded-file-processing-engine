package com.assignment2.multithreaded_file_processing_engine.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.assignment2.multithreaded_file_processing_engine.dto.JobStatusResponse;
import com.assignment2.multithreaded_file_processing_engine.entity.JobState;
import com.assignment2.multithreaded_file_processing_engine.service.CsvProcessingService;
import com.assignment2.multithreaded_file_processing_engine.service.SseEmitterRegistry;

class CsvJobControllerTest {

    private MockMvc mockMvc;
    private StubCsvProcessingService csvProcessingService;

    @BeforeEach
    void setUp() {
        csvProcessingService = new StubCsvProcessingService();
        mockMvc = MockMvcBuilders
            .standaloneSetup(new CsvJobController(csvProcessingService))
            .build();
    }

    @AfterEach
    void tearDown() {
        csvProcessingService.shutdown();
    }

    @Test
    void uploadReturnsAcceptedJobId() throws Exception {
        csvProcessingService.nextJobId = "job-123";

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "users.csv",
            "text/csv",
            "name,email\nAlice,alice@example.com\n".getBytes()
        );

        mockMvc.perform(multipart("/api/jobs/upload").file(file))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobId").value("job-123"));
    }

    @Test
    void statusReturnsNotFoundWhenJobIsMissing() throws Exception {
        mockMvc.perform(get("/api/jobs/missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void streamRequiresExistingJob() throws Exception {
        JobState state = new JobState("job-123", 100);
        csvProcessingService.states.put("job-123", state);

        mockMvc.perform(get("/api/jobs/job-123/events"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
    }

    @Test
    void statusReturnsProgressPayload() throws Exception {
        JobState state = new JobState("job-123", 100);
        state.getProcessedRows().set(40);
        state.getFailedRows().set(2);
        state.setStatus(JobState.JobStatus.PROCESSING);
        csvProcessingService.states.put("job-123", state);

        mockMvc.perform(get("/api/jobs/job-123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSING"))
            .andExpect(jsonPath("$.processed").value(40))
            .andExpect(jsonPath("$.failed").value(2));
    }

    private static class StubCsvProcessingService extends CsvProcessingService {
        private final Map<String, JobState> states = new HashMap<>();
        private String nextJobId = "job-default";

        StubCsvProcessingService() {
            super(null, new SseEmitterRegistry(), null, null);
        }

        @Override
        public String submitJob(MultipartFile file) throws IOException {
            return nextJobId;
        }

        @Override
        public Optional<JobState> getJobState(String jobId) {
            return Optional.ofNullable(states.get(jobId));
        }

        @Override
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

        @Override
        public SseEmitter register(String jobId) {
            return new SseEmitter();
        }

        void shutdown() {
        }
    }
}
