/* SPDX-License-Identifier: MPL-2.0 */
package org.voxelregion.forge.runtime;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import org.voxelregion.forge.RegionGeometry;
import org.voxelregion.geometry.IntBox;
import org.voxelregion.geometry.Point3;

@GameTestHolder("voxelregion_test")
@PrefixGameTestTemplate(false)
public final class GeometryCodecGameTests {
    @GameTest(template = "empty", batch = "vre_geometry_codec", timeoutTicks = 40)
    public static void persistedGeometryKeepsExactSelections(GameTestHelper helper) {
        RegionGeometry shape = new RegionGeometry.Combined(RegionGeometry.BooleanOperation.DIFFERENCE,
                new RegionGeometry.Combined(RegionGeometry.BooleanOperation.UNION,
                        new RegionGeometry.Ball(Point3.of(-.5, -2, 3.5), 17),
                        new RegionGeometry.Column(Point3.of(8.5, 0, -4), 11, -12, 9)),
                new RegionGeometry.Box(new IntBox(-2, -5, -2, 3, 3, 4)));
        RegionGeometry restored = RegionGeometry.load(shape.save());
        helper.assertTrue(restored.canonical().equals(shape.canonical()), "Intent geometry remains canonical");
        var a = shape.volume(); var b = restored.volume();
        for (int x = -20; x < 20; x++) for (int y = -20; y < 20; y++) for (int z = -20; z < 20; z++)
            helper.assertTrue(a.contains(x, y, z) == b.contains(x, y, z), "Roundtrip must preserve every selected voxel");
        var invalid = new RegionGeometry.Ball(Point3.of(0, 0, 0), 8).save();
        invalid.remove("radius2");
        boolean rejected = false;
        try { RegionGeometry.load(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "Missing geometry must not silently become radius zero");
        helper.succeed();
    }
}
