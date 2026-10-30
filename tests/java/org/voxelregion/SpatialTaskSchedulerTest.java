// SPDX-License-Identifier: MPL-2.0
package org.voxelregion;

import org.voxelregion.lighting.scheduler.SpatialTaskScheduler;
import org.voxelregion.lighting.scheduler.SpatialTaskScheduler.ChunkRect;
import org.voxelregion.lighting.scheduler.SpatialTaskScheduler.Footprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Ordering is checked independently from the implementation's chunk-token dependency graph. */
public final class SpatialTaskSchedulerTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        overlappingAndIndependent();
        readWriteOrdering();
        rejectionAndFailure();
        stopAndCancel();
        signedCoordinatesAndCapacity();
        directExecutorChain();
        concurrentOracle();
        concurrentSubmitters();
        System.out.println("SpatialTaskSchedulerTest passed (" + checks + " checks)");
    }

    private static void overlappingAndIndependent() throws Exception {
        var scheduler = scheduler(Runnable::run, 2, 20);
        List<String> started = new ArrayList<>();
        CompletableFuture<Void> first = new CompletableFuture<>();
        CompletableFuture<Void> second = new CompletableFuture<>();
        CompletableFuture<Void> distant = new CompletableFuture<>();
        var a = scheduler.submitChunk(0, 0, 2, w -> { started.add("a"); return first; });
        var b = scheduler.submitChunk(4, 0, 2, w -> { started.add("b"); return second; });
        var c = scheduler.submitChunk(20, 0, 2, w -> { started.add("c"); return distant; });
        check(started.equals(List.of("a", "c")), "Overlap must wait; distant work must run");
        check(a.sequence() < b.sequence() && b.sequence() < c.sequence(), "Unique generations");
        var fence = scheduler.fence(ChunkRect.around(0, 0, 2)).toCompletableFuture();
        a.completion().toCompletableFuture().cancel(false);
        check(!b.completion().toCompletableFuture().isDone(), "Consumer cancellation cannot release lease");
        first.complete(null);
        check(started.equals(List.of("a", "c", "b")), "Next conflicting generation started after publication");
        check(!fence.isDone(), "Fence includes queued generation");
        second.complete(null);
        await(fence);
        check(!c.completion().toCompletableFuture().isDone(), "Regional fence does not wait for distant work");
        distant.complete(null);
        await(scheduler.closeAsync());
        check(scheduler.statistics().trackedColumns() == 0, "Column tokens reclaimed");
        check(scheduler.statistics().peakRunning() == 2, "Independent jobs overlap");
    }

    private static void readWriteOrdering() throws Exception {
        var scheduler = scheduler(Runnable::run, 3, 20);
        ChunkRect one = ChunkRect.around(0, 0, 0);
        List<String> starts = new ArrayList<>();
        CompletableFuture<Void> readA = new CompletableFuture<>(), readB = new CompletableFuture<>();
        CompletableFuture<Void> write = new CompletableFuture<>(), after = new CompletableFuture<>();
        scheduler.submit(new Footprint(one, null), w -> { starts.add("ra"); return readA; });
        scheduler.submit(new Footprint(one, null), w -> { starts.add("rb"); return readB; });
        scheduler.submit(new Footprint(one, one), w -> { starts.add("w"); return write; });
        scheduler.submit(new Footprint(one, null), w -> { starts.add("r2"); return after; });
        check(starts.equals(List.of("ra", "rb")), "Read/read can overlap");
        readA.complete(null);
        check(starts.size() == 2, "Write waits for all readers");
        readB.complete(null);
        check(starts.equals(List.of("ra", "rb", "w")), "Write acquires after readers");
        write.complete(null);
        check(starts.equals(List.of("ra", "rb", "w", "r2")), "Later read cannot pass writer");
        after.complete(null);
        await(scheduler.closeAsync());

        var mixed = scheduler(Runnable::run, 2, 10);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        ChunkRect other = ChunkRect.around(1, 0, 0);
        mixed.submit(new Footprint(other, one), w -> gate);
        AtomicBoolean ran = new AtomicBoolean();
        mixed.submit(new Footprint(one, other), w -> { ran.set(true); return completed(); });
        check(!ran.get(), "Cross read/write domains conflict");
        gate.complete(null);
        check(ran.get(), "Cross domain dependency releases");
        await(mixed.closeAsync());
    }

    private static void rejectionAndFailure() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger created = new AtomicInteger();
        var scheduler = new SpatialTaskScheduler<Integer>(executor, 1, 10, i -> created.incrementAndGet());
        CompletableFuture<Void> first = new CompletableFuture<>();
        var a = scheduler.submitChunk(0, 0, 2, w -> first);
        AtomicInteger nextWorker = new AtomicInteger();
        var b = scheduler.submitChunk(0, 0, 2, w -> { nextWorker.set(w); return completed(); });
        var fence = scheduler.fence(ChunkRect.around(0, 0, 0));
        executor.runNext();
        first.completeExceptionally(new IllegalStateException("publication failure"));
        executor.runNext();
        expectFailure(a.completion(), IllegalStateException.class);
        await(b.completion());
        expectFailure(fence, IllegalStateException.class);
        check(created.get() == 2 && nextWorker.get() == 2, "Failed engine context is not reused");
        expectFailure(scheduler.closeAsync(), IllegalStateException.class);
        check(scheduler.statistics().outstanding() == 0, "Failure settles accepted work");

        var rejecting = scheduler(command -> { throw new RejectedExecutionException("executor stopped"); }, 1, 2);
        var rejected = rejecting.submitChunk(0, 0, 0, w -> completed());
        expectFailure(rejected.completion(), RejectedExecutionException.class);
        check(rejecting.statistics().trackedColumns() == 0, "Executor rejection releases reservation");
        expectFailure(rejecting.closeAsync(), RejectedExecutionException.class);

        AtomicInteger attempts = new AtomicInteger();
        var factoryFailure = new SpatialTaskScheduler<Integer>(Runnable::run, 1, 4, i -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalArgumentException("factory failure");
            return i;
        });
        expectFailure(factoryFailure.submitChunk(0, 0, 0, w -> completed()).completion(), IllegalArgumentException.class);
        await(factoryFailure.submitChunk(0, 0, 0, w -> completed()).completion());
        expectFailure(factoryFailure.submitChunk(0, 0, 0, w -> null).completion(), NullPointerException.class);
        expectFailure(factoryFailure.submitChunk(0, 0, 0, w -> { throw new IllegalStateException("work failure"); }).completion(), IllegalStateException.class);
        check(factoryFailure.statistics().outstanding() == 0, "Factory/null/synchronous failures settle");
    }

    private static void stopAndCancel() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        var scheduler = scheduler(executor, 2, 10);
        CompletableFuture<Void> active = new CompletableFuture<>();
        var a = scheduler.submitChunk(0, 0, 2, w -> active);
        var queued = scheduler.submitChunk(0, 0, 2, w -> { throw new AssertionError("Cancelled work ran"); });
        var dispatched = scheduler.submitChunk(20, 0, 2, w -> { throw new AssertionError("Cancelled dispatch ran"); });
        executor.runNext();
        var closed = scheduler.closeAsync(true).toCompletableFuture();
        check(!closed.isDone(), "Stop waits for active publication");
        expectFailure(queued.completion(), java.util.concurrent.CancellationException.class);
        expectFailure(dispatched.completion(), java.util.concurrent.CancellationException.class);
        executor.runNext();
        check(!a.completion().toCompletableFuture().isDone(), "Stop never cancels running writer");
        expectThrown(() -> scheduler.submitChunk(0, 0, 0, w -> completed()), RejectedExecutionException.class);
        active.complete(null);
        await(closed);
        await(a.completion());
        check(scheduler.statistics().cancelled() == 2 && scheduler.statistics().failed() == 0, "Explicit pending cancellations counted separately");
        check(scheduler.statistics().trackedColumns() == 0, "Stop releases all tokens");

        var drain = scheduler(Runnable::run, 1, 2);
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicBoolean ran = new AtomicBoolean();
        drain.submitChunk(0, 0, 0, w -> gate);
        drain.submitChunk(0, 0, 0, w -> { ran.set(true); return completed(); });
        var graceful = drain.closeAsync().toCompletableFuture();
        check(!graceful.isDone(), "Graceful close is asynchronous");
        gate.complete(null);
        await(graceful);
        check(ran.get(), "Graceful close drains queued work");
    }

    private static void signedCoordinatesAndCapacity() throws Exception {
        var scheduler = scheduler(Runnable::run, 2, 2);
        CompletableFuture<Void> a = new CompletableFuture<>(), b = new CompletableFuture<>();
        scheduler.submitChunk(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, w -> a);
        scheduler.submitChunk(Integer.MAX_VALUE, Integer.MIN_VALUE, 0, w -> b);
        check(scheduler.statistics().running() == 2, "Signed chunk keys do not alias");
        expectThrown(() -> scheduler.submitChunk(0, 0, 0, w -> completed()), RejectedExecutionException.class);
        check(scheduler.statistics().submitted() == 2, "Capacity rejection does not accept a generation");
        expectThrown(() -> ChunkRect.around(Integer.MAX_VALUE, 0, 1), ArithmeticException.class);
        expectThrown(() -> scheduler.fence(new ChunkRect(Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE)), IllegalArgumentException.class);
        var fence = scheduler.fence(ChunkRect.around(Integer.MIN_VALUE, Integer.MAX_VALUE, 0)).toCompletableFuture();
        a.complete(null);
        await(fence);
        check(!b.isDone(), "Extreme-coordinate fence does not include opposite corner");
        b.complete(null);
        await(scheduler.closeAsync());
    }

    private static void directExecutorChain() throws Exception {
        int count = 20_000;
        var scheduler = scheduler(Runnable::run, 1, count + 1);
        CompletableFuture<Void> first = new CompletableFuture<>();
        scheduler.submitChunk(-5, -5, 0, w -> first);
        AtomicInteger executed = new AtomicInteger();
        for (int i = 0; i < count; i++) {
            int expected = i;
            scheduler.submitChunk(-5, -5, 0, w -> {
                if (executed.getAndIncrement() != expected) throw new AssertionError("Generation reordered");
                return completed();
            });
        }
        var done = scheduler.closeAsync();
        first.complete(null);
        await(done);
        check(executed.get() == count, "Direct executor dependency chain has bounded stack");
        check(scheduler.statistics().trackedColumns() == 0, "Long chain reclaims all tokens");
    }

    private static void concurrentOracle() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Object guard = new Object();
            List<Observation> earlier = new ArrayList<>();
            AtomicInteger executions = new AtomicInteger();
            var scheduler = new SpatialTaskScheduler<AtomicBoolean>(pool, 3, 4_000, i -> new AtomicBoolean());
            Random random = new Random(0x5350415449414cL);
            List<SpatialTaskScheduler.Receipt> receipts = new ArrayList<>();
            for (int i = 0; i < 2_000; i++) {
                int x = random.nextInt(17) - 8, z = random.nextInt(17) - 8;
                ChunkRect read = ChunkRect.around(x, z, 2);
                ChunkRect write = random.nextInt(3) == 0 ? null : ChunkRect.around(x, z, random.nextInt(2));
                Observation observation = new Observation(read, write);
                List<Observation> preceding;
                synchronized (guard) {
                    preceding = new ArrayList<>(earlier);
                    earlier.add(observation);
                }
                receipts.add(scheduler.submit(new Footprint(read, write), worker -> {
                    if (!worker.compareAndSet(false, true)) throw new AssertionError("Worker context shared concurrently");
                    try {
                        synchronized (guard) {
                            for (Observation previous : preceding) {
                                if (conflict(previous, observation) && !previous.finished) {
                                    throw new AssertionError("Conflicting predecessor not finished");
                                }
                            }
                            observation.active = true;
                        }
                        Thread.yield();
                        synchronized (guard) {
                            observation.active = false;
                            observation.finished = true;
                        }
                        executions.incrementAndGet();
                    } finally { worker.set(false); }
                    return completed();
                }));
            }
            await(scheduler.closeAsync());
            for (var receipt : receipts) await(receipt.completion());
            check(executions.get() == 2_000, "Concurrent spatial oracle executes every generation");
            check(scheduler.statistics().running() == 0 && scheduler.statistics().trackedColumns() == 0,
                    "Concurrent completion reclaims reservations");
            check(scheduler.statistics().peakRunning() <= 3, "Worker capacity never exceeded");
        } finally {
            pool.shutdown();
            check(pool.awaitTermination(10, TimeUnit.SECONDS), "External executor can terminate after drain");
        }
    }

    private static boolean conflict(Observation a, Observation b) {
        return overlap(a.write, b.read) || overlap(a.write, b.write) || overlap(a.read, b.write);
    }

    private static void concurrentSubmitters() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(3);
        ExecutorService submitters = Executors.newFixedThreadPool(4);
        try {
            Object guard = new Object();
            List<Observation> active = new ArrayList<>();
            AtomicInteger finished = new AtomicInteger();
            var scheduler = new SpatialTaskScheduler<AtomicBoolean>(workers, 3, 1_000, i -> new AtomicBoolean());
            List<CompletableFuture<Void>> producers = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                int seed = producer;
                producers.add(CompletableFuture.runAsync(() -> {
                    Random random = new Random(seed + 12345L);
                    for (int i = 0; i < 200; i++) {
                        ChunkRect area = ChunkRect.around(random.nextInt(24), random.nextInt(24), 2);
                        Observation observation = new Observation(area, area);
                        scheduler.submit(Footprint.exclusive(area), worker -> {
                            if (!worker.compareAndSet(false, true)) throw new AssertionError("Worker lease overlaps");
                            try {
                                synchronized (guard) {
                                    for (Observation other : active) {
                                        if (conflict(other, observation)) throw new AssertionError("Concurrent writer footprints overlap");
                                    }
                                    active.add(observation);
                                }
                                Thread.yield();
                                synchronized (guard) { active.remove(observation); }
                                finished.incrementAndGet();
                            } finally { worker.set(false); }
                            return completed();
                        });
                    }
                }, submitters));
            }
            await(CompletableFuture.allOf(producers.toArray(CompletableFuture<?>[]::new)));
            await(scheduler.closeAsync());
            check(finished.get() == 800, "Concurrent submitters lose no tasks");
            check(scheduler.statistics().trackedColumns() == 0, "Concurrent submission cleanup");
        } finally {
            submitters.shutdown();
            workers.shutdown();
            check(submitters.awaitTermination(10, TimeUnit.SECONDS), "Submitters terminate");
            check(workers.awaitTermination(10, TimeUnit.SECONDS), "Workers terminate");
        }
    }

    private static boolean overlap(ChunkRect a, ChunkRect b) {
        return a != null && b != null && a.minX() <= b.maxX() && b.minX() <= a.maxX()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ();
    }

    private static final class Observation {
        final ChunkRect read, write;
        boolean active, finished;
        Observation(ChunkRect read, ChunkRect write) { this.read = read; this.write = write; }
    }

    private static SpatialTaskScheduler<Integer> scheduler(Executor executor, int workers, int capacity) {
        return new SpatialTaskScheduler<>(executor, workers, capacity, i -> i);
    }

    private static CompletableFuture<Void> completed() { return CompletableFuture.completedFuture(null); }
    private static void await(CompletionStage<Void> stage) throws Exception {
        stage.toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static void expectFailure(CompletionStage<Void> stage, Class<? extends Throwable> expected) {
        try { stage.toCompletableFuture().join(); throw new AssertionError("Expected " + expected.getName()); }
        catch (CompletionException | java.util.concurrent.CancellationException failure) {
            Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
            check(expected.isInstance(cause), "Expected " + expected.getSimpleName() + ", got " + cause);
        }
    }

    private static void expectThrown(Runnable action, Class<? extends Throwable> expected) {
        try { action.run(); throw new AssertionError("Expected " + expected.getName()); }
        catch (Throwable failure) {
            check(expected.isInstance(failure), "Expected " + expected.getSimpleName() + ", got " + failure);
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.addLast(task); }
        void runNext() {
            Runnable task = tasks.pollFirst();
            if (task == null) throw new AssertionError("No queued executor task");
            task.run();
        }
    }
}
