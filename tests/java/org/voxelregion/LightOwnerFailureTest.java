/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.voxelregion.lighting.scheduler.LightOwnerFailure;

/** Pure failure/receipt protocol. Minecraft mixin dispatch is tested separately in the real pack. */
public final class LightOwnerFailureTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        acknowledgementsAreNotSuccess();
        terminalSettlesAllAndStopsQueuedPost();
        activeFailureDefersCleanup();
        sourceFailureAndCancellationWaitForOwner();
        capturedFatalErrorStopsOwner();
        cleanupFailureCannotStrandSiblings();
        normalCompletionAndCancellationHistory();
        concurrentStopWaitsForActiveOwner();
        System.out.println("LightOwnerFailureTest passed; checks=" + checks
                + " scope=pure failure/ACK/receipt state machine, no Minecraft dispatch");
    }

    private static void acknowledgementsAreNotSuccess() {
        Error error = new OutOfMemoryError("sentinel only, no heap exhaustion");
        AtomicInteger ack = new AtomicInteger();
        check(thrown(() -> LightOwnerFailure.runAcknowledged(() -> { throw error; }, ack::incrementAndGet)) == error,
                "ACK cannot swallow original fatal");
        check(ack.get() == 1, "failure acknowledges once");
        RuntimeException acknowledgementFailure = new RuntimeException("ack failed");
        check(thrown(() -> LightOwnerFailure.runAcknowledged(() -> { throw error; },
                () -> { throw acknowledgementFailure; })) == error, "ACK failure cannot replace original failure");
        check(error.getSuppressed().length == 1, "ACK failure retained as suppressed evidence");
        LightOwnerFailure.runAcknowledged(() -> { }, ack::incrementAndGet);
        check(ack.get() == 2, "success acknowledges once");
    }

    private static void terminalSettlesAllAndStopsQueuedPost() {
        List<String> order = new ArrayList<>();
        LightOwnerFailure owner = new LightOwnerFailure(error -> order.add("invalidated"));
        List<CompletableFuture<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 24; i++) results.add(owner.track(new CompletableFuture<>(), error -> {
            check(order.contains("invalidated"), "invalidate before business receipt releases resources");
            order.add("release");
        }));
        Error fatal = new AssertionError("fatal owner sentinel");
        check(thrown(() -> owner.run(() -> { throw fatal; })) == fatal, "owner rethrows fatal");
        for (var result : results) check(cause(result) == fatal, "every accepted receipt fails with cause");
        owner.run(() -> { throw new AssertionError("success POST executed after terminal failure"); });
        check(owner.failure() == fatal, "first terminal cause retained");
        var late = owner.track(new CompletableFuture<>(), error -> order.add("late release"));
        check(cause(late) == fatal && order.get(order.size() - 1).equals("late release"), "late future fails too");
        owner.fail(new RuntimeException("secondary"));
        check(owner.failure() == fatal, "later failure cannot replace first");
    }

    private static void activeFailureDefersCleanup() {
        AtomicInteger cleanup = new AtomicInteger();
        LightOwnerFailure owner = new LightOwnerFailure(error -> cleanup.incrementAndGet());
        CompletableFuture<Integer> source = new CompletableFuture<>();
        var result = owner.track(source, error -> cleanup.incrementAndGet());
        Error fatal = new AssertionError("deferred terminal");
        check(thrown(() -> owner.run(() -> {
            owner.fail(fatal);
            var late = owner.track(new CompletableFuture<>(), error -> cleanup.incrementAndGet());
            source.complete(1);
            check(!result.isDone() && !late.isDone() && cleanup.get() == 0,
                    "active inputs held despite terminal and later native completion");
        })) == fatal, "latched fatal is rethrown after native task returned");
        check(cause(result) == fatal && cleanup.get() == 3, "cleanup after owner stopped");
    }

    private static void sourceFailureAndCancellationWaitForOwner() {
        for (RuntimeException problem : List.of(new IllegalArgumentException("native source"),
                new CancellationException("native source canceled"))) {
            AtomicInteger cleanup = new AtomicInteger();
            LightOwnerFailure owner = new LightOwnerFailure(error -> {
                throw new AssertionError("ordinary source failure must not stop entire engine");
            });
            CompletableFuture<Integer> source = new CompletableFuture<>();
            var result = owner.track(source, error -> cleanup.incrementAndGet());
            owner.run(() -> {
                source.completeExceptionally(problem);
                check(!result.isDone() && cleanup.get() == 0, "source failure cleanup waits for active POST loop");
            });
            check(cause(result) == problem && cleanup.get() == 1, "source receipt fails after owner exit");
            check(owner.failure() == null, "business failure is not terminal");
            owner.run(() -> cleanup.incrementAndGet());
            check(cleanup.get() == 2, "unrelated later owner task remains usable");
        }
    }

    private static void capturedFatalErrorStopsOwner() {
        AtomicInteger cleanup = new AtomicInteger();
        LightOwnerFailure owner = new LightOwnerFailure(error -> cleanup.incrementAndGet());
        CompletableFuture<Integer> source = new CompletableFuture<>();
        var result = owner.track(source, error -> cleanup.incrementAndGet());
        Error fatal = new LinkageError("captured supplier Error");
        check(thrown(() -> owner.run(() -> {
            source.completeExceptionally(new CompletionException(fatal));
            check(owner.failure() == fatal && !result.isDone(), "captured Error latches but holds inputs");
        })) == fatal, "captured Error escapes outer owner instead of becoming silent future failure");
        check(cause(result) == fatal && cleanup.get() == 2, "captured fatal settles correctly");
    }

    private static void cleanupFailureCannotStrandSiblings() {
        LightOwnerFailure owner = new LightOwnerFailure(error -> { throw new IllegalStateException("cleanup"); });
        Error fatal = new AssertionError("root failure");
        var first = owner.track(new CompletableFuture<>(), error -> { throw new IllegalArgumentException("receipt cleanup"); });
        var second = owner.track(new CompletableFuture<>(), error -> { });
        owner.fail(fatal);
        check(cause(first) == fatal && cause(second) == fatal, "cleanup errors do not strand any receipt");
        check(fatal.getSuppressed().length == 2, "all cleanup errors retained");
    }

    private static void normalCompletionAndCancellationHistory() {
        AtomicInteger cleanup = new AtomicInteger();
        LightOwnerFailure owner = new LightOwnerFailure(error -> { });
        var historical = owner.track(CompletableFuture.completedFuture(42), error -> cleanup.incrementAndGet());
        CompletableFuture<Integer> active = new CompletableFuture<>();
        var canceled = owner.track(active, error -> cleanup.incrementAndGet());
        canceled.cancel(false);
        Error fatal = new AssertionError("later failure");
        owner.fail(fatal);
        check(historical.join() == 42, "terminal cannot revoke completed historical success");
        check(canceled.isCancelled() && cleanup.get() == 1, "canceled receipt does not drop queued task cleanup");
        active.complete(1);
        check(cleanup.get() == 1, "late underlying completion cannot repeat cleanup");
    }

    private static void concurrentStopWaitsForActiveOwner() throws Exception {
        AtomicInteger cleanup = new AtomicInteger();
        LightOwnerFailure owner = new LightOwnerFailure(error -> cleanup.incrementAndGet());
        var result = owner.track(new CompletableFuture<>(), error -> cleanup.incrementAndGet());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> owner.run(() -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test release timeout"); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        }));
        check(entered.await(5, TimeUnit.SECONDS), "owner entered");
        CancellationException closed = new CancellationException("closed while active");
        owner.fail(closed);
        check(!result.isDone() && cleanup.get() == 0, "cross-thread close cannot release active resources");
        release.countDown(); running.get(5, TimeUnit.SECONDS);
        check(cause(result) == closed && cleanup.get() == 2, "close settles after active owner returns");
    }

    private static Throwable thrown(Runnable task) {
        try { task.run(); return null; } catch (Throwable error) { return error; }
    }
    private static Throwable cause(CompletableFuture<?> result) {
        check(result.isDone(), "receipt is settled");
        try { result.join(); return null; }
        catch (CompletionException error) { return error.getCause(); }
        catch (CancellationException error) { return error; }
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
