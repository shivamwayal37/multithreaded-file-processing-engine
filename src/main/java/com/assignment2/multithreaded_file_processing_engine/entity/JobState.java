package com.assignment2.multithreaded_file_processing_engine.entity;

import java.util.concurrent.atomic.AtomicInteger;

import lombok.Data;

@Data
public class JobState {
    private final String jobId;
    private final AtomicInteger totalRows = new AtomicInteger(0);
    private final AtomicInteger processedRows = new AtomicInteger(0);
    private final AtomicInteger failedRows    = new AtomicInteger(0);
    private volatile JobStatus status = JobStatus.QUEUED;
    private volatile String errorMessage;

    public JobState(String jobId, int totalRows) {
        this.jobId = jobId;
        this.totalRows.set(totalRows);
    }

    public int getTotalRows() {
        return totalRows.get();
    }

    public void setTotalRows(int totalRows) {
        this.totalRows.set(totalRows);
    }

    public int getProgressPercent() {
        if (totalRows.get() == 0) return 0;
        return (processedRows.get() * 100) / totalRows.get();
    }

    public enum JobStatus { QUEUED, PROCESSING, COMPLETED, FAILED }
}
