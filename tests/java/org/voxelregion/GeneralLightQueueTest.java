/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.BitSet;
import org.voxelregion.lighting.GeneralChunkLighting;

/** Analytic light fields that force full-capacity and wrapped queue growth across work steps. */
public final class GeneralLightQueueTest {
    private static final Field QUEUE = field("queue"), HEAD = field("head");
    private static long checked;

    public static void main(String[] args) {
        if (!GeneralLightQueueTest.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        maximumCapacity(); wrappedGrowth();
        System.out.println("GeneralLightQueueTest passed; analytic layer values=" + checked);
    }

    private static void maximumCapacity() {
        int width = 48, height = 32, depth = 48, cells = width * height * depth;
        byte[] emission = new byte[cells]; Arrays.fill(emission,(byte)1);
        var session = new GeneralChunkLighting.Session(new GeneralChunkLighting.Snapshot(
                width,height,depth,new byte[cells],emission,null,new BitSet(),new BitSet()));
        assert capacity(session) == 65536 && cells == 73728;
        boolean grewToLimit = false;
        int maximumQueued = 0;
        while (!session.isComplete()) {
            var progress = session.step(257);
            assert progress.work() <= 257;
            maximumQueued = Math.max(maximumQueued,progress.queued());
            if (!session.isComplete()) {
                assert capacity(session) <= cells;
                grewToLimit |= capacity(session) == cells;
            }
        }
        assert grewToLimit && maximumQueued > 65536;
        var result = session.result();
        for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            assert result.block(x,y,z) == 1 && result.sky(x,y,z) == 0;
            checked += 2;
        }
    }

    private static void wrappedGrowth() {
        int width = 64, height = 64, depth = 64, cells = width * height * depth;
        byte[] emission = new byte[cells];
        for (int y = 0; y < height; y += 4) for (int z = 0; z < depth; z += 4) for (int x = 0; x < width; x += 4)
            emission[x + width * (z + depth * y)] = 15;
        var session = new GeneralChunkLighting.Session(new GeneralChunkLighting.Snapshot(
                width,height,depth,new byte[cells],emission,null,new BitSet(),new BitSet()));
        while (true) {
            var progress = session.step(4096);
            if (progress.stage() == GeneralChunkLighting.Stage.BLOCK) break;
            assert !session.isComplete();
        }
        assert capacity(session) == 65536;
        boolean grewWrapped = false;
        while (!session.isComplete()) {
            int previousCapacity = capacity(session), previousHead = head(session);
            var progress = session.step(1);
            assert progress.work() <= 1;
            if (!session.isComplete() && capacity(session) > previousCapacity) {
                assert previousHead > 0 : "This fixture must grow a wrapped ring, not its initial fill";
                grewWrapped = true;
            }
        }
        assert grewWrapped : "The lattice frontier must exceed the initial queue";
        var result = session.result();
        // In unobstructed air, distance to the nearest lattice source is separable by axis.
        for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) {
            int distance = nearest(x,width) + nearest(y,height) + nearest(z,depth);
            assert result.block(x,y,z) == Math.max(0,15 - distance) : "Lost frontier cell " + x + "," + y + "," + z;
            assert result.sky(x,y,z) == 0;
            checked += 2;
        }
    }

    private static int nearest(int coordinate, int length) {
        int lower = coordinate / 4 * 4, upper = lower + 4;
        return upper < length ? Math.min(coordinate - lower,upper - coordinate) : coordinate - lower;
    }
    private static int capacity(GeneralChunkLighting.Session session) {
        try { int[] queue = (int[])QUEUE.get(session); return queue == null ? 0 : queue.length; }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }
    private static int head(GeneralChunkLighting.Session session) {
        try { return HEAD.getInt(session); }
        catch (IllegalAccessException failure) { throw new AssertionError(failure); }
    }
    private static Field field(String name) {
        try { Field field = GeneralChunkLighting.Session.class.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private GeneralLightQueueTest() { }
}
