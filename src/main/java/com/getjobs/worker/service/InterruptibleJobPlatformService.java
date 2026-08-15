package com.getjobs.worker.service;

import com.getjobs.worker.dto.JobProgressMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Shared lifecycle for platform delivery jobs.
 * Reserves the running state before the HTTP start request returns and makes stop requests interruptible.
 */
public abstract class InterruptibleJobPlatformService implements JobPlatformService {
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicReference<Thread> workerThread = new AtomicReference<>();

    private Executor taskExecutor;

    @Autowired
    void setTaskExecutor(@Qualifier("taskExecutor") Executor taskExecutor) {
        this.taskExecutor = taskExecutor;
    }

    public final boolean startDelivery(Consumer<JobProgressMessage> progressCallback) {
        Objects.requireNonNull(progressCallback, "progressCallback");
        if (!reserve()) {
            return false;
        }

        try {
            taskExecutor.execute(() -> runReserved(progressCallback));
            return true;
        } catch (RuntimeException e) {
            release();
            throw e;
        }
    }

    @Override
    public final void executeDelivery(Consumer<JobProgressMessage> progressCallback) {
        Objects.requireNonNull(progressCallback, "progressCallback");
        if (!reserve()) {
            progressCallback.accept(JobProgressMessage.warning(getPlatformName(), "任务已在运行中"));
            return;
        }
        runReserved(progressCallback);
    }

    private boolean reserve() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        stopRequested.set(false);
        return true;
    }

    private void runReserved(Consumer<JobProgressMessage> progressCallback) {
        Thread current = Thread.currentThread();
        workerThread.set(current);
        try {
            if (!shouldStop()) {
                doExecuteDelivery(progressCallback);
            }
        } finally {
            workerThread.compareAndSet(current, null);
            release();
            Thread.interrupted();
        }
    }

    private void release() {
        stopRequested.set(false);
        running.set(false);
    }

    @Override
    public final void stopDelivery() {
        if (!running.get()) {
            return;
        }
        stopRequested.set(true);
        Thread thread = workerThread.get();
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
    }

    protected final boolean shouldStop() {
        return stopRequested.get();
    }

    @Override
    public final boolean isRunning() {
        return running.get();
    }

    protected abstract void doExecuteDelivery(Consumer<JobProgressMessage> progressCallback);
}
