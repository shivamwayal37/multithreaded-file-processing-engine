package com.assignment2.multithreaded_file_processing_engine.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.assignment2.multithreaded_file_processing_engine.dto.JobStatusResponse;
import com.assignment2.multithreaded_file_processing_engine.dto.JobSubmissionResponse;
import com.assignment2.multithreaded_file_processing_engine.service.CsvProcessingService;
import com.opencsv.exceptions.CsvException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class CsvJobController {

    private final CsvProcessingService csvProcessingService;

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<JobSubmissionResponse> upload(@RequestParam("file") MultipartFile file)
        throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is required");
        }
        String jobId = csvProcessingService.submitJob(file);
        return ResponseEntity.accepted().body(new JobSubmissionResponse(jobId));
    }

    @GetMapping("/{jobId}")
    public JobStatusResponse status(@PathVariable String jobId) {
        return csvProcessingService.getJobState(jobId)
            .map(csvProcessingService::toResponse)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));
    }

    @GetMapping(path = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId) {
        if (csvProcessingService.getJobState(jobId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found");
        }
        return csvProcessingService.register(jobId);
    }

    @ExceptionHandler(CsvException.class)
    public ResponseEntity<String> handleCsv(CsvException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
