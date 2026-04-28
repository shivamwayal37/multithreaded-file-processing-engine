package com.assignment2.multithreaded_file_processing_engine.dto;

public record JobStatusResponse(
    String jobId,
    String status,
    int total,
    int processed,
    int failed,
    int progress,
    String errorMessage
) {
}
