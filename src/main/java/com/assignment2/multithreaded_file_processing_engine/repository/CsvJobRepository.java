package com.assignment2.multithreaded_file_processing_engine.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.assignment2.multithreaded_file_processing_engine.entity.CsvJob;

public interface CsvJobRepository extends JpaRepository<CsvJob, String> {
    Optional<CsvJob> findByFileHash(String fileHash);
}
