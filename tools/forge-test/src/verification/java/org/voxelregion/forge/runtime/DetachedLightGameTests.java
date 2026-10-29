/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.DetachedLightTasks;
import org.voxelregion.lighting.ClearedChunkLighting;

@GameTestHolder(RuntimeBridge.PERSISTENCE_NAMESPACE)
@PrefixGameTestTemplate(false)
public final class DetachedLightGameTests {
    @GameTest(template = "empty", batch = "vre_detached_light", timeoutTicks = 10000)
    public static void detachedCalculationWaitsForOwnerInstallationAndKeepsLease(GameTestHelper helper) {
        var ownerWork = new AtomicReference<Runnable>();
        var workerObserved = new AtomicBoolean();
        var releases = new AtomicInteger();
        var owner = Thread.currentThread();
        var future = DetachedLightTasks.submit(snapshot(), () -> {
            if (Thread.currentThread().getName().startsWith("VRE-detached-light-")) workerObserved.set(true);
            return true;
        }, ownerWork::set, solved -> {
            helper.assertTrue(Thread.currentThread() == owner, "Installation belongs to the supplied owner executor");
            helper.assertTrue(solved.sky(8, 8, 8) == 15 && solved.block(8, 8, 8) == 0,
                    "Detached immutable result is the expected sky/block image");
            return true;
        }, releases::incrementAndGet);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(ownerWork.get() != null, "Waiting for detached calculation");
        }).thenExecute(() -> {
            helper.assertTrue(workerObserved.get(), "Calculation ran on a dedicated worker");
            helper.assertTrue(!future.isDone() && releases.get() == 0, "A calculated image is not an installed image");
            ownerWork.getAndSet(null).run();
            helper.assertTrue(future.join() && releases.get() == 1, "Installation acknowledges and releases exactly once");
        }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_detached_light_stale", timeoutTicks = 10000)
    public static void staleAndStoppedResultsCannotInstallOrLeakReservations(GameTestHelper helper) {
        var ownerWork = new AtomicReference<Runnable>();
        var valid = new AtomicBoolean(true);
        var installed = new AtomicInteger();
        var releases = new AtomicInteger();
        var future = DetachedLightTasks.submit(snapshot(), valid::get, ownerWork::set,
                solved -> { installed.incrementAndGet(); return true; }, releases::incrementAndGet);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(ownerWork.get() != null, "Waiting for detached result");
        }).thenExecute(() -> {
            valid.set(false);
            ownerWork.getAndSet(null).run();
            helper.assertTrue(!future.join() && installed.get() == 0 && releases.get() == 1,
                    "A changed revision rejects a computed image and releases its reservation");
        }).thenSucceed();
    }

    @GameTest(template = "empty", batch = "vre_detached_light_stop", timeoutTicks = 10000)
    public static void shutdownDiscardsQueuedInstallationAndNewServerCanRestartWorkers(GameTestHelper helper) {
        var ownerWork = new AtomicReference<Runnable>();
        var installed = new AtomicInteger();
        var releases = new AtomicInteger();
        var future = DetachedLightTasks.submit(snapshot(), () -> true, ownerWork::set,
                solved -> { installed.incrementAndGet(); return true; }, releases::incrementAndGet);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(ownerWork.get() != null, "Waiting for queued installation");
        }).thenExecute(() -> {
            DetachedLightTasks.stop();
            helper.assertTrue(!future.join() && releases.get() == 1, "Stop releases a finished computation without waiting for owner queue");
            ownerWork.getAndSet(null).run();
            helper.assertTrue(installed.get() == 0 && releases.get() == 1, "Late native callback cannot install or release twice");
            var restarted = DetachedLightTasks.submit(snapshot(), () -> true, Runnable::run, solved -> true, releases::incrementAndGet);
            helper.assertTrue(restarted != future, "A subsequent server uses a new worker generation");
        }).thenWaitUntil(() -> helper.assertTrue(releases.get() == 2, "New worker generation finished"))
                .thenSucceed();
    }

    private static ClearedChunkLighting.Snapshot snapshot() {
        byte[][] block = new byte[6][], sky = new byte[6][];
        BitSet[] open = new BitSet[6];
        for (var face : ClearedChunkLighting.Face.values()) {
            int index = face.ordinal(), size = ClearedChunkLighting.faceSize(16, face);
            block[index] = new byte[size]; sky[index] = new byte[size]; open[index] = new BitSet(size);
            open[index].set(0, size);
        }
        java.util.Arrays.fill(sky[ClearedChunkLighting.Face.UP.ordinal()], (byte) 15);
        BitSet direct = new BitSet(256); direct.set(0, 256);
        return new ClearedChunkLighting.Snapshot(16, new BitSet(), block, sky, open, direct);
    }
    private DetachedLightGameTests() {}
}
