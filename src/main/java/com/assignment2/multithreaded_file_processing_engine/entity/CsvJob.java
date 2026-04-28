package com.assignment2.multithreaded_file_processing_engine.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "csv_jobs")
@Getter
@Setter
public class CsvJob {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "file_hash", length = 64, nullable = false, unique = true)
    private String fileHash;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(length = 20, nullable = false)
    private String status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(nullable = false)
    private int processed;

    @Column(nullable = false)
    private int failed;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static CsvJob queued(String fileHash, String originalFilename) {
        CsvJob job = new CsvJob();
        job.id = UUID.randomUUID().toString();
        job.fileHash = fileHash;
        job.originalFilename = originalFilename;
        job.status = JobState.JobStatus.QUEUED.name();
        return job;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
