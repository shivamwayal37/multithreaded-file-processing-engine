package com.assignment2.multithreaded_file_processing_engine.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.assignment2.multithreaded_file_processing_engine.entity.ImportedUser;

public interface ImportedUserRepository extends JpaRepository<ImportedUser, Long> {
    boolean existsByRowFingerprint(String rowFingerprint);
}
