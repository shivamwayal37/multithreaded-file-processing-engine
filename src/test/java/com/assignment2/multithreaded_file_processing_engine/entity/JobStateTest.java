package com.assignment2.multithreaded_file_processing_engine.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JobStateTest {

    @Test
    void progressPercentUsesAtomicTotals() {
        JobState state = new JobState("job-1", 200);
        state.getProcessedRows().set(50);

        assertEquals(25, state.getProgressPercent());
    }

    @Test
    void totalRowsCanBeUpdatedAfterAsyncCounting() {
        JobState state = new JobState("job-1", 0);
        state.setTotalRows(400);
        state.getProcessedRows().set(100);

        assertEquals(400, state.getTotalRows());
        assertEquals(25, state.getProgressPercent());
    }
}
