package dev.ordovicium.potato.render.visibility;

import com.google.gson.JsonObject;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Hardware-aware client terrain compile executor.
 *
 * <p>Minecraft keeps ownership of section tasks, builder packs and visibility.
 * Potato supplies an asynchronous work-stealing pool sized with explicit CPU
 * and memory headroom. The goal is lower tail latency under irregular chunk
 * compile cost, not simply "use every core".</p>
 */
public final class PotatoChunkCompileController {

    private static final Object LOCK = new Object();
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final AtomicInteger ACTIVE_TASKS = new AtomicInteger();
    private static final AtomicInteger PEAK_ACTIVE_TASKS = new AtomicInteger();
    private static final AtomicLong TASK_NANOS_PEAK = new AtomicLong();

    private static final LongAdder SUBMITTED_TASKS = new LongAdder();
    private static final LongAdder COMPLETED_TASKS = new LongAdder();
    private static final LongAdder REJECTED_TO_FALLBACK = new LongAdder();
    private static final LongAdder TASK_NANOS_TOTAL = new LongAdder();
    private static final LongAdder TASK_OVER_8_MS = new LongAdder();
    private static final LongAdder TASK_OVER_16_MS = new LongAdder();
    private static final LongAdder TASK_OVER_33_MS = new LongAdder();
    private static final LongAdder TASK_OVER_100_MS = new LongAdder();
    private static final LongAdder TASK_OVER_250_MS = new LongAdder();

    private static volatile ForkJoinPool executor;
    private static volatile Executor fallbackExecutor;

    private static final boolean DEDICATED_EXECUTOR_ENABLED =
            !Boolean.getBoolean(
                    "potato.chunk.disableDedicatedCompileExecutor"
            );

    private static volatile int vanillaRequestedBuilderPoolSize;
    private static volatile int adjustedBuilderPoolSize;
    private static volatile int configuredCompileWorkers;
    private static volatile int executorThreadCount;

    private PotatoChunkCompileController() {
    }

    public static int adjustBuilderPoolSize(
            int vanillaRequested
    ) {
        int vanilla = Math.max(1, vanillaRequested);
        int processors = Math.max(
                1,
                Runtime.getRuntime().availableProcessors()
        );
        long jvmMaxMiB = Math.max(
                1L,
                Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        );

        int cpuTarget;

        if (processors >= 24) {
            cpuTarget = 10;
        } else if (processors >= 20) {
            cpuTarget = 9;
        } else if (processors >= 16) {
            cpuTarget = 7;
        } else if (processors >= 12) {
            cpuTarget = 5;
        } else if (processors >= 8) {
            cpuTarget = 4;
        } else if (processors >= 6) {
            cpuTarget = 3;
        } else if (processors >= 4) {
            cpuTarget = 2;
        } else {
            cpuTarget = 1;
        }

        /*
         * SectionBufferBuilderPacks are memory-heavy. Work stealing is useful
         * only when enough builders exist, so memory remains an explicit cap.
         */
        int memoryCap;

        if (jvmMaxMiB < 2_048L) {
            memoryCap = 2;
        } else if (jvmMaxMiB < 4_096L) {
            memoryCap = 4;
        } else if (jvmMaxMiB < 6_144L) {
            memoryCap = 6;
        } else if (jvmMaxMiB < 7_168L) {
            memoryCap = 8;
        } else {
            memoryCap = 10;
        }

        int automatic = Math.max(
                1,
                Math.min(cpuTarget, memoryCap)
        );

        int requested = boundedIntegerProperty(
                "potato.chunk.compileWorkers",
                automatic,
                1,
                12
        );

        int spareBuilderPacks =
                processors >= 12
                        ? 2
                        : 1;

        int adjusted =
                Math.max(
                        1,
                        Math.min(
                                vanilla,
                                requested + spareBuilderPacks
                        )
                );

        vanillaRequestedBuilderPoolSize = vanilla;
        adjustedBuilderPoolSize = adjusted;
        configuredCompileWorkers = scientificParallelismCap150b(requested);

        return adjusted;
    }

    public static Executor wrapCompileExecutor(
            Executor vanillaExecutor
    ) {
        if (!DEDICATED_EXECUTOR_ENABLED) {
            return vanillaExecutor;
        }

        ForkJoinPool current = executor;

        if (current != null && !current.isShutdown()) {
            return PotatoChunkCompileController::execute;
        }

        synchronized (LOCK) {
            current = executor;

            if (current == null || current.isShutdown()) {
                fallbackExecutor = vanillaExecutor;

                int workerParallelism =
                        Math.max(
                                1,
                                configuredCompileWorkers > 0
                                        ? configuredCompileWorkers
                                        : defaultCompileWorkers()
                        );

                int builderParallelism =
                        Math.max(
                                1,
                                adjustedBuilderPoolSize > 0
                                        ? adjustedBuilderPoolSize
                                        : workerParallelism
                        );

                executorThreadCount =
                        Math.max(
                                1,
                                Math.min(
                                        12,
                                        Math.min(
                                                workerParallelism,
                                                builderParallelism
                                        )
                                )
                        );

                ForkJoinPool.ForkJoinWorkerThreadFactory factory =
                        pool -> {
                            ForkJoinWorkerThread thread =
                                    ForkJoinPool
                                            .defaultForkJoinWorkerThreadFactory
                                            .newThread(pool);

                            thread.setName(
                                    "Potato-Chunk-Compile-"
                                            + THREAD_SEQUENCE.incrementAndGet()
                            );
                            thread.setDaemon(true);
                            thread.setPriority(
                                    Math.max(
                                            Thread.MIN_PRIORITY,
                                            Thread.NORM_PRIORITY - 1
                                    )
                            );

                            return thread;
                        };

                executor = new ForkJoinPool(
                        executorThreadCount,
                        factory,
                        null,
                        true
                );
            }
        }

        return PotatoChunkCompileController::execute;
    }

    private static void execute(
            Runnable command
    ) {
        if (command == null) {
            return;
        }

        SUBMITTED_TASKS.increment();

        Runnable measured =
                () -> {
                    int active = ACTIVE_TASKS.incrementAndGet();
                    updatePeakActive(active);
                    long started = System.nanoTime();

                    try {
                        command.run();
                    } finally {
                        long elapsed = Math.max(
                                0L,
                                System.nanoTime() - started
                        );

                        TASK_NANOS_TOTAL.add(elapsed);
                        updatePeakNanos(elapsed);
                        recordTailLatency(elapsed);
                        COMPLETED_TASKS.increment();
                        ACTIVE_TASKS.decrementAndGet();
                    }
                };

        ForkJoinPool current = executor;

        if (current == null || current.isShutdown()) {
            executeFallback(measured);
            return;
        }

        try {
            current.execute(measured);
        } catch (RejectedExecutionException rejected) {
            REJECTED_TO_FALLBACK.increment();
            executeFallback(measured);
        }
    }

    private static void recordTailLatency(
            long elapsed
    ) {
        if (elapsed >= 8_000_000L) {
            TASK_OVER_8_MS.increment();
        }
        if (elapsed >= 16_000_000L) {
            TASK_OVER_16_MS.increment();
        }
        if (elapsed >= 33_000_000L) {
            TASK_OVER_33_MS.increment();
        }
        if (elapsed >= 100_000_000L) {
            TASK_OVER_100_MS.increment();
        }
        if (elapsed >= 250_000_000L) {
            TASK_OVER_250_MS.increment();
        }
    }

    private static void executeFallback(
            Runnable command
    ) {
        Executor fallback = fallbackExecutor;

        if (fallback != null) {
            fallback.execute(command);
        } else {
            command.run();
        }
    }

    private static void updatePeakActive(
            int active
    ) {
        int observed;

        do {
            observed = PEAK_ACTIVE_TASKS.get();

            if (active <= observed) {
                return;
            }
        } while (!PEAK_ACTIVE_TASKS.compareAndSet(
                observed,
                active
        ));
    }

    private static void updatePeakNanos(
            long elapsed
    ) {
        long observed;

        do {
            observed = TASK_NANOS_PEAK.get();

            if (elapsed <= observed) {
                return;
            }
        } while (!TASK_NANOS_PEAK.compareAndSet(
                observed,
                elapsed
        ));
    }

    public static void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        ForkJoinPool current = executor;
        long completed = COMPLETED_TASKS.sum();

        report.addProperty(
                "potatoChunkCompileControllerInstalled",
                true
        );
        report.addProperty(
                "potatoChunkCompileExecutorMode",
                DEDICATED_EXECUTOR_ENABLED
                        ? "ASYNC_WORK_STEALING_FORK_JOIN_POOL"
                        : "VANILLA_EXECUTOR"
        );
        report.addProperty(
                "potatoChunkCompileVanillaRequestedBuilderPoolSize",
                vanillaRequestedBuilderPoolSize
        );
        report.addProperty(
                "potatoChunkCompileAdjustedBuilderPoolSize",
                adjustedBuilderPoolSize
        );
        report.addProperty(
                "potatoChunkCompileConfiguredWorkerTarget",
                configuredCompileWorkers
        );
        report.addProperty(
                "potatoChunkCompileBuilderPoolPolicy",
                "MEMORY_CAPPED_WORKER_TARGET_PLUS_SMALL_SPARE"
        );
        report.addProperty(
                "potatoChunkCompileExecutorPolicy",
                "ASYNC_MODE_WORK_STEALING_CPU_AND_MEMORY_HEADROOM"
        );
        report.addProperty(
                "potatoChunkCompileExecutorThreadCount",
                executorThreadCount
        );
        report.addProperty(
                "potatoChunkCompileExecutorMatchesWorkerTarget",
                configuredCompileWorkers <= 0
                        || executorThreadCount <= configuredCompileWorkers
        );
        report.addProperty(
                "potatoChunkCompileExecutorActiveTasks",
                ACTIVE_TASKS.get()
        );
        report.addProperty(
                "potatoChunkCompileExecutorPeakActiveTasks",
                PEAK_ACTIVE_TASKS.get()
        );
        report.addProperty(
                "potatoChunkCompileParallelExecutionObserved",
                PEAK_ACTIVE_TASKS.get() >= 2
        );
        report.addProperty(
                "potatoChunkCompileSubmittedTaskCount",
                SUBMITTED_TASKS.sum()
        );
        report.addProperty(
                "potatoChunkCompileCompletedTaskCount",
                completed
        );
        report.addProperty(
                "potatoChunkCompileRejectedToVanillaFallbackCount",
                REJECTED_TO_FALLBACK.sum()
        );
        report.addProperty(
                "potatoChunkCompileTaskAverageMillis",
                completed == 0L
                        ? 0.0
                        : TASK_NANOS_TOTAL.sum()
                        / 1_000_000.0
                        / completed
        );
        report.addProperty(
                "potatoChunkCompileTaskPeakMillis",
                TASK_NANOS_PEAK.get() / 1_000_000.0
        );
        report.addProperty(
                "potatoChunkCompileTaskOver8MillisCount",
                TASK_OVER_8_MS.sum()
        );
        report.addProperty(
                "potatoChunkCompileTaskOver16MillisCount",
                TASK_OVER_16_MS.sum()
        );
        report.addProperty(
                "potatoChunkCompileTaskOver33MillisCount",
                TASK_OVER_33_MS.sum()
        );
        report.addProperty(
                "potatoChunkCompileTaskOver100MillisCount",
                TASK_OVER_100_MS.sum()
        );
        report.addProperty(
                "potatoChunkCompileTaskOver250MillisCount",
                TASK_OVER_250_MS.sum()
        );
        report.addProperty(
                "potatoChunkCompileExecutorQueuedSubmissionCount",
                current == null
                        ? 0
                        : current.getQueuedSubmissionCount()
        );
        report.addProperty(
                "potatoChunkCompileExecutorQueuedTaskEstimate",
                current == null
                        ? 0L
                        : current.getQueuedTaskCount()
        );
        report.addProperty(
                "potatoChunkCompileExecutorPoolSize",
                current == null
                        ? 0
                        : current.getPoolSize()
        );
        report.addProperty(
                "potatoChunkCompileExecutorStealCount",
                current == null
                        ? 0L
                        : current.getStealCount()
        );
        report.addProperty(
                "potatoChunkCompileExecutorAsyncMode",
                true
        );
        report.addProperty(
                "potatoChunkCompileUsesMultipleCpuWorkers",
                executorThreadCount >= 3
        );
        report.addProperty(
                "potatoChunkCompileRenderThreadGlUploadsStillSerialized",
                true
        );
        report.addProperty(
                "potatoChunkCompileWorldSimulationMutation",
                false
        );
    }

    public static void close() {
        ForkJoinPool current;

        synchronized (LOCK) {
            current = executor;
            executor = null;
            fallbackExecutor = null;
        }

        if (current != null) {
            current.shutdownNow();
        }
    }

    private static int defaultCompileWorkers() {
        int processors = Math.max(
                1,
                Runtime.getRuntime().availableProcessors()
        );

        if (processors >= 24) {
            return 10;
        }
        if (processors >= 20) {
            return 9;
        }
        if (processors >= 16) {
            return 7;
        }
        if (processors >= 12) {
            return 5;
        }
        if (processors >= 8) {
            return 4;
        }
        if (processors >= 6) {
            return 3;
        }
        if (processors >= 4) {
            return 2;
        }

        return 1;
    }

    private static int boundedIntegerProperty(
            String property,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        int value = Integer.getInteger(
                property,
                defaultValue
        );

        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }

    /*
     * Patch 150b - scientific processor allotment for work stealing.
     *
     * Preserve the existing ForkJoinPool and its work-stealing behavior, but
     * reserve CPU capacity for Minecraft's integrated server, worldgen,
     * render thread, IO and GC. This targets completion latency on small
     * shared CPUs rather than maximizing compile-worker occupancy.
     *
     * The cap is an upper bound only. It never increases the worker request
     * already selected by Potato's existing adaptive policy.
     */
    private static int scientificParallelismCap150b(
            int requestedWorkers
    ) {
        int processors =
                Math.max(
                        1,
                        Runtime.getRuntime()
                                .availableProcessors()
                );

        int reserve;

        if (processors <= 4) {
            reserve = 2;
        } else if (processors <= 8) {
            reserve = 3;
        } else if (processors <= 12) {
            reserve = 4;
        } else if (processors <= 16) {
            reserve = 4;
        } else if (processors <= 24) {
            reserve = 5;
        } else {
            reserve =
                    Math.max(
                            6,
                            processors / 5
                    );
        }

        int allotment =
                Math.max(
                        1,
                        processors - reserve
                );

        /*
         * Mobile / SMT-heavy low-core-count systems gain little if every
         * logical sibling is occupied by mesh compilation while the server
         * and world generator are competing for the same physical cores.
         */
        if (processors <= 12) {
            /*
             * POTATO_PATCH_151A_ELASTIC_COMPLETION_WORKER
             *
             * 150b's strict processors/2 cap produced excellent frame pacing,
             * but the 6C/12T test still showed visible section-completion holes.
             * Keep the server/render/IO reserve, but allow one additional
             * compile worker of burst headroom on SMT-heavy low-core systems.
             *
             * Examples:
             *  4 logical -> reserve still limits to 2
             *  8 logical -> at most 5
             * 12 logical -> at most 7
             *
             * This remains an upper bound and never exceeds the requested
             * worker count selected by the existing adaptive policy.
             */
            allotment =
                    Math.min(
                            allotment,
                            Math.max(
                                    2,
                                    (processors + 2) / 2
                            )
                    );
        } else if (processors <= 16) {
            /*
             * POTATO_PATCH_158_MONOTONIC_COMPILE_CAP
             * 13-16 logical CPUs must not receive a lower compile ceiling than
             * the <=12 bucket. The current 12-thread low-end test path remains
             * unchanged at its existing maximum of seven workers.
             */
            allotment =
                    Math.min(
                            allotment,
                            7
                    );
        }

        return Math.max(
                1,
                Math.min(
                        requestedWorkers,
                        allotment
                )
        );
    }
}
