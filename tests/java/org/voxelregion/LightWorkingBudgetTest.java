/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.voxelregion.lighting.LightWorkingBudget;

public final class LightWorkingBudgetTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var budget = new LightWorkingBudget(100);
        var first = budget.tryAcquire(60);
        check(first != null && budget.statistics().reserved() == 60, "initial reservation");
        check(budget.tryAcquire(41) == null, "concurrent jobs cannot exceed capacity");
        var second = budget.tryAcquire(40);
        check(second != null && budget.statistics().peak() == 100, "exact boundary");
        check(!first.tryIncreaseTo(61), "proof growth must not block behind another lease");
        second.close(); second.close();
        check(budget.statistics().reserved() == 60, "idempotent release");
        check(first.tryIncreaseTo(100), "grow after owner releases other work");
        first.reduceTo(25);
        check(budget.statistics().reserved() == 25, "proved compact field shrinks reservation");
        first.close();
        check(budget.statistics().reserved() == 0, "all work released");
        expect(IllegalArgumentException.class, () -> budget.tryAcquire(0));
        expect(IllegalArgumentException.class, () -> budget.tryAcquire(101));
        expect(IllegalArgumentException.class, () -> first.tryIncreaseTo(10));
        expect(IllegalArgumentException.class, () -> first.reduceTo(1));

        var held = budget.tryAcquire(100);
        var completed = new CompletableFuture<Long>();
        var entered = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            entered.countDown();
            try (var lease = budget.acquire(75)) { completed.complete(lease.bytes()); }
            catch (Throwable failure) { completed.completeExceptionally(failure); }
        }, "light-budget-wait-test");
        waiter.start();
        check(entered.await(2, TimeUnit.SECONDS), "waiter started");
        awaitWaiting(waiter);
        check(!completed.isDone(), "active lease cannot be stolen");
        held.close();
        check(completed.get(2, TimeUnit.SECONDS) == 75, "release wakes pending owner");
        waiter.join(2000);
        check(!waiter.isAlive() && budget.statistics().reserved() == 0, "waiter releases actual work");

        var closing = new LightWorkingBudget(Long.MAX_VALUE);
        var maximum = closing.tryAcquire(Long.MAX_VALUE);
        check(maximum != null && closing.tryAcquire(1) == null, "subtraction prevents overflow");
        var closedWait = new CompletableFuture<Throwable>();
        Thread closed = new Thread(() -> {
            try { closing.acquire(1); closedWait.complete(new AssertionError("acquired after close")); }
            catch (Throwable failure) { closedWait.complete(failure); }
        }, "light-budget-close-test");
        closed.start(); awaitWaiting(closed);
        closing.close();
        check(closedWait.get(2, TimeUnit.SECONDS) instanceof IllegalStateException, "close fails blocked owner");
        check(closing.statistics().reserved() == Long.MAX_VALUE, "close does not uncharge running work");
        maximum.close(); closed.join(2000);
        check(closing.statistics().reserved() == 0, "real finish releases closed budget");
        expect(IllegalStateException.class, () -> closing.tryAcquire(1));
        check(budget.statistics().denied() == 2, "denials include nonblocking grow");
        System.out.println("LightWorkingBudgetTest PASS " + checks + " checks");
    }
    private static void awaitWaiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.sleep(1);
        check(thread.getState() == Thread.State.WAITING, "owner waits without spinning");
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + type); }
        catch (Throwable failure) { check(type.isInstance(failure), "exception type " + type); }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
