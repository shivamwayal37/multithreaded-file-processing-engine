package com.assignment2.multithreaded_file_processing_engine.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ThreadPoolConfig {

    @Value("${csv.processor.thread-pool-size:0}")
    private int configuredThreadPoolSize;

    @Bean("csvProcessorExecutor")
    public ExecutorService csvProcessorExecutor() {
        int coreCount = configuredThreadPoolSize > 0
            ? configuredThreadPoolSize
            : Runtime.getRuntime().availableProcessors();
        return new ThreadPoolExecutor(
            coreCount,          // corePoolSize
            coreCount * 2,      // maxPoolSize
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),   // bounded queue — backpressure
            new ThreadFactoryBuilder()
                .setNameFormat("csv-worker-%d")
                .build(),
            new ThreadPoolExecutor.CallerRunsPolicy()  // don't drop tasks, slow the caller
        );
    }
}
