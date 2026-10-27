// SPDX-License-Identifier: MPL-2.0
package org.voxelregion;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.voxelregion.api.CommitGuard;
import org.voxelregion.geometry.VoxelMask;
import org.voxelregion.journal.*;
import org.voxelregion.planner.SectionPlan;
import org.voxelregion.planner.WriteKind;
import org.voxelregion.scheduler.*;
import org.voxelregion.spatial.SectionKey;

public final class RuntimeTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing action) throws Exception {
        checks++;
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    public static void main(String[] args) throws Exception {
        budget(); scheduling(); recovery(); journal();
        System.out.println("RuntimeTest PASS: " + checks + " checks");
    }
    private static void budget() throws Exception {
        AtomicLong time = new AtomicLong(Long.MAX_VALUE - 5);
        TickBudget budget = new TickBudget(new TickBudget.Limits(20, 10, 3, 5), time::get);
        check(budget.trySpend(5, 2, 4), "initial debit");
        check(!budget.trySpend(1, 2, 0) && budget.workSpent() == 5, "atomic multidimensional reject");
        check(!budget.trySpend(Long.MAX_VALUE, 0, 0), "overflow safe debit");
        time.addAndGet(15); check(!budget.timeExpired(), "nanoTime wrap handled");
        time.addAndGet(10); check(budget.timeExpired(), "time bound");
        rejects(() -> budget.trySpend(-1, 0, 0));
        ResourceGate gate = new ResourceGate(new ResourceGate.Limits(2, 1, 64));
        var first = gate.tryAcquire(1, 1, 48);
        check(first != null && gate.tryAcquire(1, 0, 17) == null, "memory backpressure precedes allocation");
        var second = gate.tryAcquire(1, 0, 16);
        check(second != null && gate.tryAcquire(1, 0, 0) == null, "shared load bound");
        first.close(); first.close(); second.close();
        check(gate.usage().equals(new ResourceGate.Usage(0, 0, 0)), "leases idempotent");
        rejects(() -> gate.tryAcquire(-1, 0, 0));
    }
    private static void scheduling() throws Exception {
        AtomicLong clock = new AtomicLong();
        RegionScheduler scheduler = new RegionScheduler(20, clock::get);
        rejects(() -> scheduler.tick(null));
        int[] work = new int[10]; List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int index = i; UUID id = UUID.randomUUID(); ids.add(id);
            scheduler.submit(id, new RegionScheduler.Job() {
                public RegionScheduler.Step step(TickBudget budget) {
                    if (!budget.trySpend(1, 0, 0)) return RegionScheduler.Step.WAITING;
                    return ++work[index] == 20 ? RegionScheduler.Step.COMPLETE : RegionScheduler.Step.PROGRESSED;
                }
                public void cancel() {}
            });
        }
        for (int tick = 0; tick < 20; tick++) {
            var report = scheduler.tick(new TickBudget.Limits(100, 20, 0, 0));
            check(report.work() <= 20 && Arrays.stream(work).max().orElseThrow() - Arrays.stream(work).min().orElseThrow() <= 1,
                    "ten operations share budget and remain fair");
        }
        check(Arrays.stream(work).sum() == 200, "all operations complete");
        for (UUID id : ids) check(scheduler.status(id).state() == RegionScheduler.State.COMPLETE, "terminal job");
        UUID waiting = UUID.randomUUID(); int[] calls = {0};
        scheduler.submit(waiting, new RegionScheduler.Job() {
            public RegionScheduler.Step step(TickBudget b) { calls[0]++; return RegionScheduler.Step.WAITING; }
            public void cancel() { calls[0] += 100; }
        });
        scheduler.tick(new TickBudget.Limits(100, 1000, 0, 0));
        check(calls[0] == 1, "waiting queue does not busy spin"); scheduler.cancel(waiting);
        check(calls[0] == 101 && scheduler.status(waiting).state() == RegionScheduler.State.CANCELLED, "cancel cleanup");
        UUID failure = UUID.randomUUID(); boolean[] cleaned = {false};
        scheduler.submit(failure, new RegionScheduler.Job() {
            public RegionScheduler.Step step(TickBudget b) { throw new IllegalStateException("backend outcome unknown"); }
            public void cancel() { cleaned[0] = true; }
        });
        scheduler.tick(new TickBudget.Limits(100, 1000, 0, 0));
        check(cleaned[0] && scheduler.status(failure).state() == RegionScheduler.State.FAILED, "no automatic retry of failed commit");
        rejects(() -> scheduler.resume(failure));
        Throwable[] offThread = {null}; Thread thread = new Thread(() -> {
            try { scheduler.status(failure); } catch (Throwable e) { offThread[0] = e; }
        }); thread.start(); thread.join();
        check(offThread[0] instanceof IllegalStateException, "world thread ownership");
    }
    private static void recovery() throws Exception {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        ChunkReceipts receipts = new ChunkReceipts(2).record(a, ChunkReceipts.Outcome.APPLIED)
                .record(b, ChunkReceipts.Outcome.APPLIED);
        // New player state is not replayed through operation A after operation B.
        check(receipts.get(a) != null && receipts.get(b) != null, "A-B-A replay protection");
        check(RecoveryPolicy.decide(false, receipts.get(a), false, true) == RecoveryPolicy.Decision.ALREADY_FINISHED,
                "receipt dominates replay");
        check(RecoveryPolicy.decide(false, null, false, true) == RecoveryPolicy.Decision.PAUSE_UNCERTAIN,
                "missing receipt does not prove untouched world");
        check(RecoveryPolicy.decide(false, null, true, false) == RecoveryPolicy.Decision.SKIP_PERMISSION, "new claim protects");
        check(RecoveryPolicy.decide(true, null, true, true) == RecoveryPolicy.Decision.STOP_TERMINAL, "terminal no revisit");
        ChunkReceipts denied = new ChunkReceipts(2).record(a, ChunkReceipts.Outcome.SKIPPED_PERMISSION);
        check(RecoveryPolicy.decide(false, denied.get(a), true, true) == RecoveryPolicy.Decision.ALREADY_FINISHED,
                "unclaim cannot revive old denied task");
        rejects(() -> receipts.record(UUID.randomUUID(), ChunkReceipts.Outcome.APPLIED));
        rejects(() -> receipts.record(a, ChunkReceipts.Outcome.CANCELLED));
        rejects(() -> receipts.archive(a, false));
        SectionPlan plan = new SectionPlan(new SectionKey(0, 0, 0), 7, 9, WriteKind.WHOLE_AIR, VoxelMask.ALL);
        check(CommitGuard.check(a, receipts, plan, 99, 99, true) == CommitGuard.Decision.ALREADY_APPLIED, "do not replan applied");
        check(CommitGuard.check(a, new ChunkReceipts(2), plan, 7, 10, true) == CommitGuard.Decision.REPLAN, "protection revision");
        check(CommitGuard.check(a, new ChunkReceipts(2), plan, 8, 9, true) == CommitGuard.Decision.REPLAN, "player changed section");
        check(CommitGuard.check(a, new ChunkReceipts(2), plan, 7, 9, false) == CommitGuard.Decision.SKIP_PERMISSION, "commit permission");
    }
    private static void journal() throws Exception {
        Path parent = Path.of("build", "test-work"); Files.createDirectories(parent);
        Path directory = Files.createTempDirectory(parent, "journal-"); UUID id = UUID.randomUUID();
        byte[] data = {1, 2, 3}; OperationIntent intent = new OperationIntent(id, "test:dimension", "actor", data);
        data[0] = 8; check(intent.payload()[0] == 1, "immutable intent");
        try (FileOperationJournal journal = new FileOperationJournal(directory)) {
            journal.prepare(intent); rejects(() -> journal.prepare(intent));
            journal.transition(id, FileOperationJournal.State.ACTIVE, "before world mutation");
            journal.transition(id, FileOperationJournal.State.PAUSED, "save outcome uncertain");
            check(journal.read(id).state() == FileOperationJournal.State.PAUSED, "forced transition round trip");
            check(journal.operations().equals(List.of(id)), "operation enumeration");
            rejects(() -> { try (var other = new FileOperationJournal(directory)) { throw new AssertionError("lock missing"); } });
        }
        try (FileOperationJournal journal = new FileOperationJournal(directory)) {
            check(journal.read(id).sequence() == 2, "restart retains sequence");
            journal.transition(id, FileOperationJournal.State.CANCELLED, "admin cancellation");
            rejects(() -> journal.transition(id, FileOperationJournal.State.ACTIVE, "unsafe revival"));
        }
        // Crash injection: append a partial next frame. Reader must not silently retry.
        Files.write(directory.resolve(id + ".vre"), new byte[]{0, 1}, StandardOpenOption.APPEND);
        try (FileOperationJournal journal = new FileOperationJournal(directory)) { rejects(() -> journal.read(id)); }
        UUID corrupt = UUID.randomUUID();
        try (FileOperationJournal journal = new FileOperationJournal(directory)) { journal.prepare(new OperationIntent(corrupt, "x", "", new byte[0])); }
        Path path = directory.resolve(corrupt + ".vre"); byte[] bytes = Files.readAllBytes(path); bytes[10] ^= 1; Files.write(path, bytes);
        try (FileOperationJournal journal = new FileOperationJournal(directory)) { rejects(() -> journal.read(corrupt)); }
        UUID unicode = UUID.randomUUID();
        String longText = "\u6750".repeat(4096);
        try (FileOperationJournal journal = new FileOperationJournal(directory)) {
            journal.prepare(new OperationIntent(unicode, longText, longText, new byte[OperationIntent.MAX_PAYLOAD]));
            journal.transition(unicode, FileOperationJournal.State.ACTIVE, longText);
            check(journal.read(unicode).reason().equals(longText), "maximum UTF-8 metadata stays readable");
        }
    }
}
