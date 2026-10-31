/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.voxelregion.lighting.scheduler.LightPublicationCohorts;

public final class LightPublicationCohortsTest {
    private static int checks;
    private static final class Fixture {
        final ArrayDeque<Runnable> owner = new ArrayDeque<>();
        final List<Runnable> markers = new ArrayList<>();
        final List<String> applied = new ArrayList<>();
        final List<List<String>> batches = new ArrayList<>();
        final LightPublicationCohorts<String> cohorts = new LightPublicationCohorts<>(owner::addLast, requests -> {
            List<String> batch = new ArrayList<>();
            for (var request : requests) { batch.add(request.action()); applied.add(request.action()); }
            batches.add(List.copyOf(batch));
            for (var request : requests) request.completion().complete(null);
        });
        CompletableFuture<Void> add(long column, String action) { return cohorts.submit(column, action, markers::add); }
        void flush() { owner.removeFirst().run(); }
    }

    public static void main(String[] args) {
        coalesceAndReverse();
        slowPriorRebuildCannotBeOvertaken();
        failedMarkerSettlesAndAdvances();
        synchronousArrivalThenFailure();
        ownerRejection();
        publisherFailureAndMissingCompletion();
        cancellationDoesNotDropAction();
        closeQueuedAndActive();
        followupDuringPublication();
        System.out.println("LightPublicationCohortsTest passed; checks=" + checks + " scope=pure production cohort state machine, no Minecraft engine or world");
    }

    private static void coalesceAndReverse() {
        for (boolean reverse : new boolean[] { false, true }) {
            Fixture f = new Fixture(); List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < 36; i++) futures.add(f.add(i, "request" + i));
            for (int i = 0; i < 36; i++) {
                Runnable marker = f.markers.get(reverse ? 35 - i : i);
                marker.run(); marker.run();
                check(f.owner.size() == (i == 35 ? 1 : 0), "no premature/duplicate dispatch");
                check(futures.stream().noneMatch(CompletableFuture::isDone), "marker never publishes");
            }
            f.flush();
            check(f.batches.size() == 1 && f.applied.size() == 36, "one complete batch");
            for (int i = 0; i < 36; i++) check(f.applied.get(i).equals("request" + i), "submission order");
            check(futures.stream().allMatch(LightPublicationCohortsTest::success), "all published");
            for (Runnable marker : f.markers) marker.run();
            check(f.owner.isEmpty(), "late duplicate marker cannot repeat publication");
        }
    }

    private static void slowPriorRebuildCannotBeOvertaken() {
        Fixture f = new Fixture();
        var rebuildX = f.add(1, "A:rebuild X");
        var slowY = f.add(2, "A:slow Y");
        f.markers.get(0).run(); // Closes A while Y is still outside the owner lane.
        var fenceX = f.add(1, "B:fence X");
        f.markers.get(2).run();
        check(f.owner.isEmpty(), "ready B cannot pass pending A");
        check(!fenceX.isDone() && !rebuildX.isDone(), "fence not acknowledged before rebuild");
        f.markers.get(1).run();
        check(f.owner.size() == 1, "ready FIFO prefix uses one flush");
        f.flush();
        check(f.applied.equals(List.of("A:rebuild X", "A:slow Y", "B:fence X")), "earlier rebuild before later fence");
        check(success(rebuildX) && success(slowY) && success(fenceX), "all ordered receipts settled");
    }

    private static void failedMarkerSettlesAndAdvances() {
        Fixture f = new Fixture();
        var a = f.add(1, "failed-cohort queued sibling");
        var rejected = f.cohorts.submit(2, "rejected", marker -> { throw new RejectedExecutionException("marker rejected"); });
        var later = f.add(1, "later valid");
        f.markers.get(1).run();
        check(f.owner.isEmpty(), "failed cohort still observes its accepted sibling marker");
        f.markers.get(0).run(); f.flush();
        check(a.isCompletedExceptionally() && rejected.isCompletedExceptionally(), "whole broken cohort fails");
        check(success(later) && f.applied.equals(List.of("later valid")), "failure advances head without applying broken cohort");
        var soleFailure = f.cohorts.submit(3, "sole failure", marker -> { throw new IllegalStateException("rejected"); });
        f.flush(); check(soleFailure.isCompletedExceptionally(), "sole rejected marker cannot stall");
    }

    private static void synchronousArrivalThenFailure() {
        Fixture f = new Fixture();
        var receipt = f.cohorts.submit(0, "must not run", marker -> {
            marker.run(); marker.run();
            check(f.owner.isEmpty(), "synchronous admission cannot schedule under gate");
            throw new IllegalStateException("admission failed after callback");
        });
        f.flush();
        check(receipt.isCompletedExceptionally() && f.applied.isEmpty(), "post-callback admission failure wins");
    }

    private static void ownerRejection() {
        List<Runnable> markers = new ArrayList<>(); int[] invoked = { 0 };
        var c = new LightPublicationCohorts<String>(task -> { throw new RejectedExecutionException("owner stopped"); }, requests -> invoked[0]++);
        var a = c.submit(1, "a", markers::add); var b = c.submit(2, "b", markers::add);
        markers.get(0).run(); markers.get(1).run();
        check(a.isCompletedExceptionally() && b.isCompletedExceptionally(), "rejected owner fails all queued receipts");
        check(c.submit(3, "new", markers::add).isCompletedExceptionally(), "stopped owner rejects new submissions");
        check(invoked[0] == 0 && markers.size() == 2, "owner failure cannot publish or enqueue more markers");
    }

    private static void publisherFailureAndMissingCompletion() {
        for (boolean throwsFailure : new boolean[] { false, true }) {
            ArrayDeque<Runnable> owner = new ArrayDeque<>(); List<Runnable> markers = new ArrayList<>();
            var c = new LightPublicationCohorts<String>(owner::addLast, requests -> {
                if (throwsFailure) throw new IllegalStateException("publisher failed");
            });
            var receipt = c.submit(0, "a", markers::add); markers.get(0).run(); owner.removeFirst().run();
            check(receipt.isCompletedExceptionally(), "publisher failure/missing receipt never becomes success");
            var next = c.submit(0, "b", markers::add); markers.get(1).run(); owner.removeFirst().run();
            check(next.isCompletedExceptionally(), "publisher failure does not leave scheduled stuck");
        }
    }

    private static void cancellationDoesNotDropAction() {
        Fixture f = new Fixture(); var cancelled = f.add(1, "accepted edit");
        cancelled.cancel(false); f.markers.get(0).run(); f.flush();
        check(cancelled.isCancelled(), "caller cancellation preserved");
        check(f.applied.equals(List.of("accepted edit")), "accepted edit still applied after cancellation");
    }

    private static void closeQueuedAndActive() {
        Fixture f = new Fixture(); var queued = f.add(1, "pending"); f.cohorts.close();
        check(queued.isCompletedExceptionally(), "close fails not-started request");
        f.markers.get(0).run(); check(f.owner.isEmpty(), "late arrival after close ignored");
        check(f.add(2, "new").isCompletedExceptionally(), "close rejects new request");

        ArrayDeque<Runnable> owner = new ArrayDeque<>(); List<Runnable> markers = new ArrayList<>();
        @SuppressWarnings("unchecked") LightPublicationCohorts<String>[] holder = new LightPublicationCohorts[1];
        holder[0] = new LightPublicationCohorts<>(owner::addLast, requests -> {
            holder[0].close();
            check(requests.stream().noneMatch(r -> r.completion().isDone()), "close cannot release active publication resources early");
            // Return without publishing; termination must settle after this point.
        });
        var active = holder[0].submit(1, "active", markers::add); markers.get(0).run(); owner.removeFirst().run();
        check(active.isCompletedExceptionally(), "active receipt settles after owner returns");
    }

    private static void followupDuringPublication() {
        ArrayDeque<Runnable> owner = new ArrayDeque<>(); List<Runnable> markers = new ArrayList<>();
        List<String> applied = new ArrayList<>(); List<CompletableFuture<Void>> nested = new ArrayList<>();
        @SuppressWarnings("unchecked") LightPublicationCohorts<String>[] holder = new LightPublicationCohorts[1];
        holder[0] = new LightPublicationCohorts<>(owner::addLast, requests -> {
            for (var r : requests) {
                applied.add(r.action());
                if (r.action().equals("first")) nested.add(holder[0].submit(0, "second", Runnable::run));
                r.completion().complete(null);
            }
        });
        var first = holder[0].submit(0, "first", markers::add); markers.get(0).run(); owner.removeFirst().run();
        check(success(first) && owner.size() == 1 && !nested.get(0).isDone(), "followup retained and queued once");
        owner.removeFirst().run();
        check(success(nested.get(0)) && applied.equals(List.of("first", "second")), "followup published in order");
    }

    private static boolean success(CompletableFuture<?> future) { return future.isDone() && !future.isCompletedExceptionally(); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
