package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterruptibleJobPlatformServiceTest {
    @Test
    void reservesRunningStateBeforeWorkerStartsAndAcceptsImmediateStop() {
        Runnable[] queued = new Runnable[1];
        TestService service = new TestService(new CountDownLatch(0), new CountDownLatch(0));
        service.setTaskExecutor(command -> queued[0] = command);

        assertTrue(service.startDelivery(message -> {}));
        assertTrue(service.isRunning());
        assertFalse(service.startDelivery(message -> {}));

        service.stopDelivery();
        queued[0].run();

        assertFalse(service.executed.get());
        assertFalse(service.isRunning());
    }

    @Test
    void differentPlatformServicesCanRunAtTheSameTimeAndStopIndependently() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        TestService first = new TestService(bothEntered, release);
        TestService second = new TestService(bothEntered, release);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            first.setTaskExecutor(executor);
            second.setTaskExecutor(executor);

            assertTrue(first.startDelivery(message -> {}));
            assertTrue(second.startDelivery(message -> {}));
            assertTrue(bothEntered.await(2, TimeUnit.SECONDS));
            assertTrue(first.isRunning());
            assertTrue(second.isRunning());

            first.stopDelivery();
            release.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }

        assertTrue(first.sawStop.get());
        assertFalse(first.isRunning());
        assertFalse(second.isRunning());
    }

    private static final class TestService extends InterruptibleJobPlatformService {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final AtomicBoolean sawStop = new AtomicBoolean();
        private final AtomicBoolean executed = new AtomicBoolean();

        private TestService(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        protected void doExecuteDelivery(Consumer<JobProgressMessage> progressCallback) {
            executed.set(true);
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                sawStop.set(shouldStop());
            }
        }

        @Override
        public Map<String, Object> getStatus() {
            return Map.of("isRunning", isRunning());
        }

        @Override
        public String getPlatformName() {
            return "test";
        }
    }
}
