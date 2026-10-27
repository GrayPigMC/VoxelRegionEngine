/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.voxelregion;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import org.voxelregion.geometry.Coverage;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.RangeSink;
import org.voxelregion.geometry.Sphere;
import org.voxelregion.geometry.Volume;
import org.voxelregion.spatial.ChunkKey;

/**
 * Optional standalone geometry microbenchmark, not part of the default suite.
 * It reads no world data and measures neither Minecraft writes nor complete
 * policy planning. One warmup and five measured runs are performed per size.
 * Launch with -ea -XX:ActiveProcessorCount=1 and an OS CPU affinity of one.
 */
public final class GeometryBenchmark {
    private static final int BUILD_MIN_Y = -64;
    private static final int BUILD_MAX_Y = 320;
    private static final int RUNS = 5;
    private static final com.sun.management.ThreadMXBean ALLOCATIONS = allocationBean();
    private static volatile long blackhole;

    private GeometryBenchmark() { }

    public static void main(String[] args) {
        if (!GeometryBenchmark.class.desiredAssertionStatus()) throw new IllegalStateException("Use -ea");
        System.out.println("Pure geometry only; center=(0.5,64.5,0.5); Y=[-64,320); warmup=1; measured=5; median");
        System.out.println("Java=" + System.getProperty("java.version")
                + "; availableProcessors=" + Runtime.getRuntime().availableProcessors());
        boolean printSamples = Arrays.asList(args).contains("--samples");
        for (int radius : new int[] {64, 200, 800, 2000}) runRadius(radius, printSamples);
    }

    private static void runRadius(int radius, boolean printSamples) {
        Sphere sphere = Sphere.of(0.5, 64.5, 0.5, radius);
        Counts expected = plan(sphere).counts();
        Sample[] samples = new Sample[RUNS];
        for (int i = 0; i < RUNS; i++) {
            samples[i] = plan(sphere);
            if (!expected.equals(samples[i].counts())) throw new AssertionError("Nondeterministic planning counts");
        }
        long[] nanos = new long[RUNS], bytes = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            nanos[i] = samples[i].nanos();
            bytes[i] = samples[i].allocatedBytes();
        }
        if (printSamples) printSamples("R" + radius + " plan", nanos, bytes);
        System.out.printf(java.util.Locale.ROOT,
                "R%d plan median_ms=%.3f allocated_bytes=%s candidate_voxels=%d ranges=%d%n",
                radius, median(nanos) / 1_000_000.0, allocationLabel(median(bytes)), expected.voxels(), expected.ranges());
        System.out.printf("  regions inside/partial/outside=%d/%d/%d; chunks=%d/%d/%d; sections=%d/%d/%d%n",
                expected.regionInside(), expected.regionPartial(), expected.regionOutside(),
                expected.chunkInside(), expected.chunkPartial(), expected.chunkOutside(),
                expected.sectionInside(), expected.sectionPartial(), expected.sectionOutside());
        System.out.printf("  exact classifications region/chunk/section=%d/%d/%d; whole_voxels=%d boundary_voxels=%d%n",
                expected.regionClassifications(), expected.chunkClassifications(), expected.sectionClassifications(),
                expected.sectionInside() * 4096L, expected.voxels() - expected.sectionInside() * 4096L);
        if (radius == 64) {
            long naiveCount = naive(sphere);
            if (naiveCount != expected.voxels()) throw new AssertionError("R64 naive count mismatch");
            for (int i = 0; i < RUNS; i++) {
                long allocationStart = allocatedBytes();
                long start = System.nanoTime();
                long count = naive(sphere);
                nanos[i] = System.nanoTime() - start;
                bytes[i] = allocationDelta(allocationStart);
                if (count != naiveCount) throw new AssertionError("Nondeterministic naive count");
            }
            if (printSamples) printSamples("R64 naive", nanos, bytes);
            System.out.printf(java.util.Locale.ROOT,
                    "  naive R64 median_ms=%.3f allocated_bytes=%s candidate_voxels=%d (matches hierarchy)%n",
                    median(nanos) / 1_000_000.0, allocationLabel(median(bytes)), naiveCount);
        }
    }

    /** Region -> chunk -> section traversal; no block enumeration in whole sections. */
    private static Sample plan(Volume volume) {
        long allocationStart = allocatedBytes();
        long start = System.nanoTime();
        MutableCounts stats = new MutableCounts();
        RangeSink countRanges = (x, z, minY, maxY) -> {
            stats.voxels += maxY - minY;
            stats.ranges++;
        };
        IntBox bounds = volume.bounds().intersection(new IntBox(IntBox.WORLD_MIN, BUILD_MIN_Y,
                IntBox.WORLD_MIN, IntBox.WORLD_MAX, BUILD_MAX_Y, IntBox.WORLD_MAX));
        if (!bounds.isEmpty()) {
            int minChunkX = Math.floorDiv(bounds.minX(), 16);
            int maxChunkX = Math.floorDiv(bounds.maxX() - 1, 16);
            int minChunkZ = Math.floorDiv(bounds.minZ(), 16);
            int maxChunkZ = Math.floorDiv(bounds.maxZ() - 1, 16);
            int minSectionY = Math.floorDiv(bounds.minY(), 16);
            int maxSectionY = Math.floorDiv(bounds.maxY() - 1, 16);
            for (int rx = Math.floorDiv(minChunkX, 32); rx <= Math.floorDiv(maxChunkX, 32); rx++) {
                for (int rz = Math.floorDiv(minChunkZ, 32); rz <= Math.floorDiv(maxChunkZ, 32); rz++) {
                    int firstX = Math.max(minChunkX, rx * 32);
                    int lastX = Math.min(maxChunkX, rx * 32 + 31);
                    int firstZ = Math.max(minChunkZ, rz * 32);
                    int lastZ = Math.min(maxChunkZ, rz * 32 + 31);
                    Coverage region = volume.classify(new IntBox(firstX * 16, BUILD_MIN_Y, firstZ * 16,
                            (lastX + 1) * 16, BUILD_MAX_Y, (lastZ + 1) * 16));
                    stats.regionClassifications++;
                    switch (region) {
                        case OUTSIDE -> { stats.regionOutside++; continue; }
                        case INSIDE -> stats.regionInside++;
                        case PARTIAL -> stats.regionPartial++;
                    }
                    for (int cx = firstX; cx <= lastX; cx++) {
                        for (int cz = firstZ; cz <= lastZ; cz++) {
                            ChunkKey key = new ChunkKey(cx, cz);
                            Coverage chunk = region;
                            if (region != Coverage.INSIDE) {
                                chunk = volume.classify(key.bounds(BUILD_MIN_Y, BUILD_MAX_Y));
                                stats.chunkClassifications++;
                            }
                            switch (chunk) {
                                case OUTSIDE -> { stats.chunkOutside++; continue; }
                                case INSIDE -> stats.chunkInside++;
                                case PARTIAL -> stats.chunkPartial++;
                            }
                            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                                IntBox section = key.section(sy).bounds();
                                Coverage coverage = chunk;
                                if (chunk != Coverage.INSIDE) {
                                    coverage = volume.classify(section);
                                    stats.sectionClassifications++;
                                }
                                switch (coverage) {
                                    case OUTSIDE -> stats.sectionOutside++;
                                    case INSIDE -> {
                                        stats.sectionInside++;
                                        stats.voxels += 4096L;
                                    }
                                    case PARTIAL -> {
                                        stats.sectionPartial++;
                                        volume.emitRanges(section, countRanges);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Counts counts = stats.freeze();
        blackhole = counts.voxels();
        long nanos = System.nanoTime() - start;
        long allocated = allocationDelta(allocationStart);
        return new Sample(nanos, allocated, counts);
    }

    private static long naive(Volume volume) {
        IntBox box = volume.bounds().intersection(new IntBox(IntBox.WORLD_MIN, BUILD_MIN_Y,
                IntBox.WORLD_MIN, IntBox.WORLD_MAX, BUILD_MAX_Y, IntBox.WORLD_MAX));
        long count = 0;
        for (int x = box.minX(); x < box.maxX(); x++) {
            for (int z = box.minZ(); z < box.maxZ(); z++) {
                for (int y = box.minY(); y < box.maxY(); y++) {
                    if (volume.contains(x, y, z)) count++;
                }
            }
        }
        blackhole = count;
        return count;
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) {
            if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
            return bean;
        }
        return null;
    }

    private static long allocatedBytes() {
        return ALLOCATIONS == null ? -1 : ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static long allocationDelta(long start) {
        long end = allocatedBytes();
        return start < 0 || end < 0 ? -1 : end - start;
    }

    private static String allocationLabel(long bytes) { return bytes < 0 ? "unavailable" : Long.toString(bytes); }

    private static void printSamples(String label, long[] nanos, long[] bytes) {
        for (int i = 0; i < nanos.length; i++) {
            System.out.printf(java.util.Locale.ROOT, "  %s round=%d time_ns=%d allocated_bytes=%s%n",
                    label, i + 1, nanos[i], allocationLabel(bytes[i]));
        }
    }

    private static long median(long[] values) {
        long[] copy = values.clone();
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private record Sample(long nanos, long allocatedBytes, Counts counts) { }

    private record Counts(long regionInside, long regionPartial, long regionOutside,
            long chunkInside, long chunkPartial, long chunkOutside,
            long sectionInside, long sectionPartial, long sectionOutside,
            long regionClassifications, long chunkClassifications, long sectionClassifications,
            long voxels, long ranges) { }

    private static final class MutableCounts {
        long regionInside, regionPartial, regionOutside;
        long chunkInside, chunkPartial, chunkOutside;
        long sectionInside, sectionPartial, sectionOutside;
        long regionClassifications, chunkClassifications, sectionClassifications;
        long voxels, ranges;

        Counts freeze() {
            return new Counts(regionInside, regionPartial, regionOutside,
                    chunkInside, chunkPartial, chunkOutside, sectionInside, sectionPartial, sectionOutside,
                    regionClassifications, chunkClassifications, sectionClassifications, voxels, ranges);
        }
    }
}
