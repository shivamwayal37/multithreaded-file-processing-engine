package com.assignment2.multithreaded_file_processing_engine.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;

// SseEmitterRegistry.java
@Component
@Slf4j
public class SseEmitterRegistry {

    // jobId → emitter (one emitter per job per client)
    private final ConcurrentHashMap<String, List<SseEmitter>> emitters =
        new ConcurrentHashMap<>();

    public SseEmitter register(String jobId) {
        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L); // 5min timeout

        emitters.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        // Clean up on complete/timeout/error
        Runnable cleanup = () -> {
            List<SseEmitter> list = emitters.get(jobId);
            if (list != null) list.remove(emitter);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        return emitter;
    }

    public void sendUpdate(String jobId, Map<String, Object> data) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null || list.isEmpty()) {
            return;
        }
        List<SseEmitter> dead = new ArrayList<>();

        for (SseEmitter emitter : list) {
            try {
                send(emitter, data);
            } catch (IOException e) {
                dead.add(emitter); // client disconnected
            }
        }
        if (!dead.isEmpty()) {
            list.removeAll(dead);
            if (list.isEmpty()) {
                emitters.remove(jobId, list);
            }
        }
    }

    public void send(SseEmitter emitter, Map<String, Object> data) throws IOException {
        emitter.send(SseEmitter.event()
            .name("progress")
            .data(data, MediaType.APPLICATION_JSON));
    }

    public void complete(String jobId) {
        List<SseEmitter> list = emitters.remove(jobId);
        if (list != null) list.forEach(SseEmitter::complete);
    }
}
