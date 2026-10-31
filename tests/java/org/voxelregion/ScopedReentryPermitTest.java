/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.voxelregion.runtime.ScopedReentryPermit;

/** Uses the same permit class as the generation hook, including nested native scheduling. */
public final class ScopedReentryPermitTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        exactIdentityAndSingleConsumption(); recursiveDependencies(); nestedScopes(); exceptionCleanup();
        returnedFutureDoesNotExtendScope(); ownerThreadConfinement(); invalidInputs();
        System.out.println("ScopedReentryPermitTest passed; checks=" + checks);
    }

    private static void exactIdentityAndSingleConsumption() {
        var guard = new ScopedReentryPermit<Object, Object>();
        Object holder = new String("holder"), sameHolderValue = new String("holder");
        Object stage = new String("FEATURES"), sameStageValue = new String("FEATURES");
        check(!guard.consume(holder, stage), "Ordinary entry is not bypassed");
        Object result = guard.call(holder, stage, () -> {
            check(!guard.consume(sameHolderValue, stage), "Equal-valued different holder still hooked");
            check(!guard.consume(holder, sameStageValue), "Equal-valued different stage still hooked");
            check(guard.consume(holder, stage), "Exact original reentry consumes permit");
            check(!guard.consume(holder, stage), "Same call can consume only once");
            check(!guard.consume(sameHolderValue, sameStageValue), "Neighbor remains hooked after consume");
            return holder;
        });
        check(result == holder, "Original return value preserved");
        check(!guard.consume(holder, stage), "No permit survives normal return");
        guard.call(holder, stage, () -> {
            check(guard.consume(holder, stage), "Later independent fallback has its own permit");
            return null;
        });
    }

    private static void recursiveDependencies() {
        var guard = new ScopedReentryPermit<Object, Object>();
        Object existing = new Object(), fresh = new Object(), features = new Object(), parent = new Object();
        guard.call(existing, features, () -> {
            check(guard.consume(existing, features), "Existing target enters original native scheduler");
            // Native scheduleChunkGeneration synchronously calls getChunkRangeFuture.
            // Its getOrScheduleFuture calls must still enter cleared admission.
            for (int iteration = 0; iteration < 8; iteration++) {
                check(!guard.consume(fresh, features), "Fresh neighbor admission remains active");
                check(!guard.consume(existing, parent), "Different parent stage remains active");
                check(!guard.consume(existing, features), "Recursive same stage cannot reuse consumed bypass");
            }
            return null;
        });
    }

    private static void nestedScopes() {
        var guard = new ScopedReentryPermit<Object, Object>();
        Object outer = new Object(), inner = new Object(), stage = new Object();
        guard.call(outer, stage, () -> {
            check(guard.consume(outer, stage), "Outer scope consumed");
            guard.call(inner, stage, () -> {
                check(!guard.consume(outer, stage), "Inner cannot borrow outer permit");
                check(guard.consume(inner, stage), "Nested fallback consumes its own permit");
                check(!guard.consume(inner, stage), "Nested permit is single-use");
                return null;
            });
            check(!guard.consume(outer, stage), "Nested return does not reset consumed parent");
            check(!guard.consume(inner, stage), "Nested permit gone on return");
            return null;
        });
        guard.call(outer, stage, () -> {
            guard.call(inner, stage, () -> {
                check(guard.consume(inner, stage), "Inner may run before outer original reentry");
                return null;
            });
            check(guard.consume(outer, stage), "An unconsumed parent is restored intact");
            return null;
        });
        nestedSamePair(guard, outer, stage, 24);
        check(!guard.consume(outer, stage), "Deep nesting fully unwinds");
    }

    private static void nestedSamePair(ScopedReentryPermit<Object, Object> guard, Object holder, Object stage, int depth) {
        guard.call(holder, stage, () -> {
            check(guard.consume(holder, stage), "Each same-pair nested scope has one permit");
            if (depth > 0) nestedSamePair(guard, holder, stage, depth - 1);
            check(!guard.consume(holder, stage), "Nested same-pair return cannot revive parent");
            return null;
        });
    }

    private static void exceptionCleanup() {
        var guard = new ScopedReentryPermit<Object, Object>();
        Object holder = new Object(), stage = new Object(), nested = new Object();
        Error fatal = new AssertionError("sentinel");
        try {
            guard.call(holder, stage, () -> { check(guard.consume(holder, stage), "Consume before failure"); throw fatal; });
            throw new AssertionError("Expected original fatal error");
        } catch (Error failure) { check(failure == fatal, "Original Error propagated unchanged"); }
        check(!guard.consume(holder, stage), "Fatal failure clears permit");
        guard.call(holder, stage, () -> {
            try {
                guard.call(nested, stage, () -> { throw fatal; });
                throw new AssertionError("Expected nested failure");
            } catch (Error failure) { check(failure == fatal, "Nested Error propagated unchanged"); }
            check(guard.consume(holder, stage), "Caught nested failure restores unused outer scope");
            check(!guard.consume(nested, stage), "Failed nested permit does not leak");
            return null;
        });
        RuntimeException ordinary = new IllegalStateException("ordinary sentinel");
        try { guard.call(holder, stage, () -> { throw ordinary; }); throw new AssertionError("Expected exception"); }
        catch (RuntimeException failure) { check(failure == ordinary, "Original runtime failure preserved"); }
        check(!guard.consume(holder, stage), "Unconsumed scope also clears on exception");
    }

    private static void returnedFutureDoesNotExtendScope() {
        var guard = new ScopedReentryPermit<Object, Object>();
        Object holder = new Object(), stage = new Object();
        CompletableFuture<Integer> nativeFuture = new CompletableFuture<>();
        CompletableFuture<Integer> result = guard.call(holder, stage, () -> {
            check(guard.consume(holder, stage), "Native entry consumed before returning pending future");
            return nativeFuture;
        });
        check(result == nativeFuture && !result.isDone(), "Future returned without replacing or waiting for it");
        check(!guard.consume(holder, stage), "Pending native future retains no admission bypass");
        CompletableFuture<Boolean> later = result.thenApply(ignored -> guard.consume(holder, stage));
        nativeFuture.complete(7);
        check(!later.join(), "Later same-owner continuation is a normal hooked call");
    }

    private static void ownerThreadConfinement() throws Exception {
        var guard = new ScopedReentryPermit<Object, Object>();
        Object holder = new Object(), stage = new Object();
        AtomicReference<Throwable> consumed = new AtomicReference<>(), called = new AtomicReference<>();
        guard.call(holder, stage, () -> {
            Thread wrong = new Thread(() -> {
                try { guard.consume(holder, stage); } catch (Throwable failure) { consumed.set(failure); }
                try { guard.call(holder, stage, () -> true); } catch (Throwable failure) { called.set(failure); }
            }, "reentry-wrong-owner-test");
            wrong.start();
            try { wrong.join(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            check(consumed.get() instanceof IllegalStateException, "Cross-thread consumption rejected");
            check(called.get() instanceof IllegalStateException, "Cross-thread scope rejected");
            check(guard.consume(holder, stage), "Wrong-thread attempts cannot consume owner's permit");
            return null;
        });
    }

    private static void invalidInputs() {
        var guard = new ScopedReentryPermit<Object, Object>();
        Object holder = new Object(), stage = new Object();
        rejects(() -> guard.call(null, stage, () -> true));
        rejects(() -> guard.call(holder, null, () -> true));
        rejects(() -> guard.call(holder, stage, null));
        check(!guard.consume(holder, stage), "Rejected calls leave no scope");
    }

    private static void rejects(Runnable action) {
        try { action.run(); throw new AssertionError("Invalid input accepted"); }
        catch (NullPointerException expected) { checks++; }
    }
    private static void check(boolean okay, String message) { checks++; if (!okay) throw new AssertionError(message); }
}
