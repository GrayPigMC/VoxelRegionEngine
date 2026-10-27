/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.FrozenOcclusionCapture;
import org.voxelregion.propagation.FrozenOcclusion;

/** Tests ownership/accounting, not an assertion about the JVM's actual heap size. */
@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class OcclusionBudgetGameTests {
    @GameTest(template = "empty", batch = "vre_occlusion_cancel_budget", timeoutTicks = 300)
    public static void queuedCancellationDropsPayloadButRunningCancellationKeepsItsLease(GameTestHelper helper) {
        long baseline = OperationOcclusion.estimatedBytes();
        ThreadPoolExecutor executor = executor();
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        var runningLease = new OperationOcclusion.MemoryLease(2 * 1_048_576L);
        var queuedLease = new OperationOcclusion.MemoryLease(3 * 1_048_576L);
        var running = new OperationOcclusion.CompileJob(runningLease, () -> {
            started.countDown();
            awaitUninterruptibly(finish); // Model a noninterruptible filesystem/index operation.
            return compiled();
        });
        var queued = new OperationOcclusion.CompileJob(queuedLease, () -> { queuedRan.set(true); return compiled(); });
        Runnable cleanup = () -> {
            running.cancel(); queued.cancel(); finish.countDown();
            for (Runnable discarded : executor.shutdownNow())
                ((OperationOcclusion.CompileJob) discarded).discard(new CancellationException("Fixture cleanup"));
        };
        cleanupOnCompletion(helper, cleanup);
        running.submit(executor); queued.submit(executor);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(started.getCount() == 0, "First worker owns its payload"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(OperationOcclusion.estimatedBytes() == baseline + 5 * 1_048_576L,
                                "Queued and running immutable payloads share the same global estimate");
                        queued.cancel(); queued.cancel();
                        helper.assertTrue(queued.finished().isDone() && executor.getQueue().isEmpty() && !queuedRan.get()
                                        && OperationOcclusion.estimatedBytes() == baseline + 2 * 1_048_576L,
                                "Removing a queued callable drops its payload and releases its lease once");
                        running.cancel(); running.cancel();
                        helper.assertTrue(!running.finished().isDone() && runningLease.bytes() == 2 * 1_048_576L
                                        && OperationOcclusion.estimatedBytes() == baseline + 2 * 1_048_576L,
                                "Cancellation cannot return memory still owned by a running callable");
                        for (Runnable discarded : executor.shutdownNow())
                            ((OperationOcclusion.CompileJob) discarded).discard(new CancellationException("Fixture shutdown"));
                        helper.assertTrue(!running.finished().isDone() && runningLease.bytes() > 0,
                                "Shutdown is nonblocking and interrupt alone cannot return a live lease");
                    } finally { cleanup.run(); }
                }).thenWaitUntil(() -> helper.assertTrue(running.finished().isDone(), "Actual worker finally has completed"))
                .thenExecute(() -> {
                    helper.assertTrue(OperationOcclusion.estimatedBytes() == baseline && runningLease.bytes() == 0,
                            "Actual callable completion returns all cancelled-worker memory");
                    try { running.take(); throw new AssertionError("Cancelled result was delivered"); }
                    catch (CancellationException expected) { }
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_occlusion_transfer_budget", timeoutTicks = 300)
    public static void completedWorkerKeepsOneLeaseUntilResidentTransferAndRelease(GameTestHelper helper) {
        long baseline = OperationOcclusion.estimatedBytes();
        var lease = new OperationOcclusion.MemoryLease(1_048_576);
        var job = new OperationOcclusion.CompileJob(lease, OcclusionBudgetGameTests::compiled);
        ThreadPoolExecutor executor = executor();
        Runnable cleanup = () -> {
            job.cancel();
            for (Runnable discarded : executor.shutdownNow())
                ((OperationOcclusion.CompileJob) discarded).discard(new CancellationException("Fixture cleanup"));
        };
        cleanupOnCompletion(helper, cleanup); job.submit(executor);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(job.finished().isDone(), "Index worker has completed"))
                .thenExecute(() -> {
                    try {
                        helper.assertTrue(OperationOcclusion.estimatedBytes() == baseline + 1_048_576,
                                "A completed but unconsumed index remains reserved");
                        var result = job.take();
                        helper.assertTrue(OperationOcclusion.estimatedBytes() == baseline + 1_048_576,
                                "Transferring the result does not duplicate or release its lease");
                        lease.shrinkTo(result.snapshot().estimatedBytes());
                        helper.assertTrue(OperationOcclusion.estimatedBytes() == baseline + 524_288,
                                "Resident estimate returns only the unused reservation");
                        try { job.take(); throw new AssertionError("Result was transferred twice"); }
                        catch (IllegalStateException expected) { }
                    } finally { lease.close(); lease.close(); cleanup.run(); }
                    helper.assertTrue(OperationOcclusion.estimatedBytes() == baseline,
                            "Resident release is idempotent and returns the remaining estimate");
                }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_occlusion_failed_budget", timeoutTicks = 100)
    public static void failureNullResultAndRejectedSubmissionNeverLeakReservations(GameTestHelper helper) {
        long baseline = OperationOcclusion.estimatedBytes();
        for (boolean nullResult : new boolean[] { false, true }) {
            var lease = new OperationOcclusion.MemoryLease(524_288);
            var job = new OperationOcclusion.CompileJob(lease, () -> {
                if (nullResult) return null;
                throw new IllegalStateException("Injected immutable index failure");
            });
            job.run();
            helper.assertTrue(job.finished().isDone() && lease.bytes() == 0,
                    "Failed and invalid completed callables return their reservation");
            try { job.take(); throw new AssertionError("Failed result was delivered"); }
            catch (CompletionException expected) { }
            job.cancel();
        }
        ThreadPoolExecutor executor = executor(); executor.shutdown();
        var lease = new OperationOcclusion.MemoryLease(524_288);
        var rejected = new OperationOcclusion.CompileJob(lease, OcclusionBudgetGameTests::compiled);
        rejected.submit(executor);
        helper.assertTrue(rejected.finished().isDone() && lease.bytes() == 0
                        && OperationOcclusion.estimatedBytes() == baseline,
                "Rejected submission releases its detached payload and the same global reservation");
        helper.succeed();
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "vre-lease-fixture"); thread.setDaemon(true); return thread;
        });
    }
    private static OperationOcclusion.Compiled compiled() {
        return new OperationOcclusion.Compiled("0".repeat(64), new FrozenOcclusionCapture.Snapshot(
                FrozenOcclusion.none(), List.of(), Map.of(), 0, 4, 524_288));
    }
    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static void cleanupOnCompletion(GameTestHelper helper, Runnable cleanup) {
        try {
            for (Field field : GameTestHelper.class.getDeclaredFields()) if (field.getType() == GameTestInfo.class) {
                field.setAccessible(true);
                ((GameTestInfo) field.get(helper)).addListener(new GameTestListener() {
                    public void testStructureLoaded(GameTestInfo info) { }
                    public void testPassed(GameTestInfo info) { cleanup.run(); }
                    public void testFailed(GameTestInfo info) { cleanup.run(); }
                });
                return;
            }
            throw new AssertionError("GameTest completion listener unavailable");
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private OcclusionBudgetGameTests() { }
}
