package com.getjobs.worker.zhilian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhilianPageFailureTest {
    @Test
    void recognizesTargetClosedFailures() {
        RuntimeException error = new RuntimeException("Target page, context or browser has been closed");

        assertTrue(ZhiLian.isPageClosedFailure(error));
        assertFalse(ZhiLian.isPageClosedFailure(new RuntimeException("ordinary timeout")));
    }
}
