package com.minecolonies.kingdoms.citizen;

import com.minecolonies.kingdoms.world.settlement.layout.LocalStreetKind;
import com.minecolonies.kingdoms.world.settlement.layout.LocalStreetPoint;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlan;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementLayoutPlanner;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetNetwork;
import com.minecolonies.kingdoms.world.settlement.layout.SettlementStreetSegment;
import com.minecolonies.kingdoms.world.settlement.structure.StructureFootprint;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CitizenRepresentationTest
{
    private static final CitizenSettings SETTINGS = CitizenSettings.defaults();

    @Test
    void physicalBudgetNeverExceedsPopulationRosterOrCaps()
    {
        assertEquals(0, CitizenBudget.target(0, 10, 12));
        assertEquals(1, CitizenBudget.target(1, 10, 12));
        assertEquals(5, CitizenBudget.target(5, 10, 12));
        assertEquals(9, CitizenBudget.target(20, 16, 12));
        assertEquals(12, CitizenBudget.target(100, 16, 12), "per-settlement cap");
        assertEquals(4, CitizenBudget.target(100, 4, 12), "roster size bounds it");
        for (int population = 0; population < 300; population++)
            assertTrue(CitizenBudget.target(population, 16, 12) <= population);
        assertEquals(2, CitizenBudget.spawnAllowance(10, 0, 0, 0, SETTINGS), "gradual: spawnsPerCycle");
        assertEquals(0, CitizenBudget.spawnAllowance(10, 0, SETTINGS.maxGlobal(), 0, SETTINGS), "global cap");
        assertEquals(0, CitizenBudget.spawnAllowance(10, 0, 0, SETTINGS.maxPerPlayer(), SETTINGS), "per-player cap");
        assertEquals(1, CitizenBudget.spawnAllowance(10, 9, 0, 0, SETTINGS));
        assertEquals(0, CitizenBudget.spawnAllowance(10, 12, 0, 0, SETTINGS));
    }

    @Test
    void activationUsesHysteresisBetweenTheRadii()
    {
        assertTrue(CitizenBudget.active(false, 60, SETTINGS));
        assertFalse(CitizenBudget.active(false, 80, SETTINGS), "inactive settlement needs the inner radius");
        assertTrue(CitizenBudget.active(true, 80, SETTINGS), "active settlement stays active inside the outer radius");
        assertFalse(CitizenBudget.active(true, 100, SETTINGS));
        assertFalse(CitizenBudget.active(true, Double.POSITIVE_INFINITY, SETTINGS));
    }

    /** Flat synthetic world: floor at y=63, air above, optional holes/water/walls, loaded only for x < 100. */
    private static SafeSpawnFinder.BlockView world(final Set<Long> solidAboveGround, final Set<Long> noFloor)
    {
        return new SafeSpawnFinder.BlockView()
        {
            @Override public boolean loaded(final int x, final int z) { return x < 100; }
            @Override public boolean floor(final int x, final int y, final int z)
            {
                return y == 63 && !noFloor.contains(BlockPos.asLong(x, 0, z));
            }
            @Override public boolean open(final int x, final int y, final int z)
            {
                return y > 63 && !solidAboveGround.contains(BlockPos.asLong(x, 0, z));
            }
        };
    }

    @Test
    void safeSpawnAvoidsWallsHolesFootprintsAndUnloadedChunks()
    {
        final Set<Long> walls = new HashSet<>();
        walls.add(BlockPos.asLong(10, 0, 10));
        final Set<Long> holes = new HashSet<>();
        holes.add(BlockPos.asLong(11, 0, 10));
        final var view = world(walls, holes);
        final var found = SafeSpawnFinder.find(new BlockPos(10, 64, 10), 3, 3, view, List.of()).orElseThrow();
        assertEquals(64, found.getY());
        assertNotEquals(new BlockPos(10, 64, 10), found);
        assertNotEquals(new BlockPos(11, 64, 10), found);
        final var excluded = SafeSpawnFinder.find(new BlockPos(10, 64, 10), 3, 3, world(Set.of(), Set.of()),
            List.of(new StructureFootprint(8, 8, 12, 12)));
        assertTrue(excluded.isPresent());
        assertFalse(new StructureFootprint(8, 8, 12, 12).contains(excluded.get().getX(), excluded.get().getZ()),
            "never inside a building footprint");
        assertTrue(SafeSpawnFinder.find(new BlockPos(150, 64, 10), 4, 3, world(Set.of(), Set.of()), List.of()).isEmpty(),
            "unloaded chunks are never used");
        final Set<Long> everything = new HashSet<>();
        for (int x = -10; x <= 30; x++) for (int z = -10; z <= 30; z++) everything.add(BlockPos.asLong(x, 0, z));
        assertTrue(SafeSpawnFinder.find(new BlockPos(10, 64, 10), 3, 3, world(everything, Set.of()), List.of()).isEmpty(),
            "no safe position: do not spawn this cycle");
    }

    private static SettlementLayoutPlan layout()
    {
        final UUID settlement = UUID.nameUUIDFromBytes("router".getBytes());
        final SettlementStreetNetwork streets = new SettlementStreetNetwork();
        streets.put(SettlementStreetSegment.withGeometry(UUID.nameUUIDFromBytes("plaza".getBytes()), List.of(
            new LocalStreetPoint(new BlockPos(0, 64, 0), LocalStreetKind.GROUND),
            new LocalStreetPoint(new BlockPos(1, 64, 0), LocalStreetKind.GROUND)), 3, SettlementLayoutPlanner.PLAZA_PURPOSE));
        final List<LocalStreetPoint> root = new ArrayList<>();
        for (int x = 4; x <= 30; x++) root.add(new LocalStreetPoint(new BlockPos(x, 64, 0), LocalStreetKind.GROUND));
        streets.put(SettlementStreetSegment.withGeometry(UUID.nameUUIDFromBytes("root".getBytes()), root, 2,
            SettlementLayoutPlanner.ROOT_PURPOSE));
        final List<LocalStreetPoint> branch = new ArrayList<>();
        for (int z = 20; z >= 0; z--) branch.add(new LocalStreetPoint(new BlockPos(20, 64, z), LocalStreetKind.GROUND));
        streets.put(SettlementStreetSegment.withGeometry(UUID.nameUUIDFromBytes("branch".getBytes()), branch, 1,
            "building-frontage"));
        return new SettlementLayoutPlan(settlement, "style", streets);
    }

    @Test
    void routesFollowTheLocalStreetNetworkAndReachThePlaza()
    {
        final StreetRouter router = new StreetRouter(layout());
        assertTrue(router.size() > 40 && router.size() <= StreetRouter.MAX_NODES);
        final BlockPos door = new BlockPos(20, 64, 21);
        final BlockPos plaza = new BlockPos(0, 64, 0);
        final List<BlockPos> route = router.route(door, plaza);
        assertEquals(plaza, route.getLast());
        assertTrue(route.size() >= 3, "several street waypoints, not one straight line");
        for (final BlockPos waypoint : route.subList(0, route.size() - 1))
            assertTrue(waypoint.getX() == 20 || waypoint.getZ() == 0, () -> "waypoint off the street: " + waypoint);
        assertEquals(route, router.route(door, plaza), "deterministic (and cached)");
        final BlockPos far = new BlockPos(500, 64, 500);
        assertEquals(List.of(far), router.route(door, far), "too far from any street: direct fallback");
    }

    @Test
    void stuckRepresentativesSkipWaypointsThenGiveUpSafely()
    {
        final StuckTracker tracker = new StuckTracker(100);
        assertEquals(StuckTracker.Verdict.MOVING, tracker.update(10.0D, 0));
        assertEquals(StuckTracker.Verdict.MOVING, tracker.update(8.0D, 50));
        assertEquals(StuckTracker.Verdict.MOVING, tracker.update(8.0D, 120));
        assertEquals(StuckTracker.Verdict.SKIP_WAYPOINT, tracker.update(8.0D, 151));
        tracker.update(8.0D, 200);
        assertEquals(StuckTracker.Verdict.SKIP_WAYPOINT, tracker.update(8.0D, 301));
        tracker.update(8.0D, 400);
        assertEquals(StuckTracker.Verdict.GIVE_UP, tracker.update(8.0D, 501));
        tracker.arrived();
        assertEquals(0, tracker.skips());
    }

    @Test
    void scheduleSendsWorkersToWorkByDayAndEveryoneHomeAtNight()
    {
        assertEquals(CitizenActivity.WORK, CitizenSchedule.activity(CitizenRole.FARMER, true, true, 4000, 0));
        assertEquals(CitizenActivity.HOME, CitizenSchedule.activity(CitizenRole.FARMER, true, true, 18000, 0));
        assertEquals(CitizenActivity.HOME, CitizenSchedule.activity(CitizenRole.RESIDENT, false, true, 18000, 7));
        assertEquals(CitizenActivity.HOME, CitizenSchedule.activity(CitizenRole.RESIDENT, false, false, 18000, 7),
            "representatives without a house also leave the streets at night (their shelter is the plaza)");
        assertEquals(CitizenActivity.SOCIALIZE, CitizenSchedule.activity(CitizenRole.RESIDENT, false, false, 12300, 0));
        assertEquals(CitizenActivity.WORK, CitizenSchedule.activity(CitizenRole.GUARD, true, true, 18000, 0), "guards keep watch");
        final CitizenActivity evening = CitizenSchedule.activity(CitizenRole.SMITH, true, true, 10000, 0);
        assertTrue(evening == CitizenActivity.VISIT_MARKET || evening == CitizenActivity.SOCIALIZE);
        assertEquals(CitizenActivity.RETURN_HOME, CitizenSchedule.activity(CitizenRole.SMITH, true, true, 12300, 0));
        for (long time = 0; time < 24000; time += 250)
            for (final CitizenRole role : CitizenRole.values())
                assertEquals(CitizenSchedule.activity(role, true, true, time, 42), CitizenSchedule.activity(role, true, true, time, 42));
        assertEquals(CitizenSchedule.Destination.WORK, CitizenSchedule.destination(CitizenActivity.GO_TO_WORK));
        assertEquals(CitizenSchedule.Destination.HOME, CitizenSchedule.destination(CitizenActivity.RETURN_HOME));
    }

    @Test
    void appearanceIsDeterministicAndFollowsStyleRoleAndGender()
    {
        final String key = CitizenAppearance.textureKey("Medieval Birch", CitizenRole.FARMER, true, 1234L);
        assertEquals(key, CitizenAppearance.textureKey("Medieval Birch", CitizenRole.FARMER, true, 1234L));
        assertTrue(key.startsWith("medieval/farmerfemale1_"), key);
        assertTrue(CitizenAppearance.textureKey("Caledonia", CitizenRole.SMITH, false, 9L).startsWith("nordic/blacksmithmale1_"));
        assertTrue(CitizenAppearance.textureKey("Some Pack", CitizenRole.RESIDENT, false, 9L).startsWith("default/citizenmale"));
        assertEquals(CitizenNames.name(UUID.nameUUIDFromBytes("x".getBytes()), false),
            CitizenNames.name(UUID.nameUUIDFromBytes("x".getBytes()), false));
        assertTrue(CitizenAppearance.humanoidLayout(128, 64));
        assertTrue(CitizenAppearance.humanoidLayout(256, 128), "HD resource packs");
        assertFalse(CitizenAppearance.humanoidLayout(128, 128), "MineColonies dress models");
        assertFalse(CitizenAppearance.humanoidLayout(64, 32), "legacy skins");
    }

    /** Every key the game can generate must resolve to an installed humanoid-layout MineColonies texture. */
    @Test
    void everyGeneratedTextureKeyResolvesToAHumanoidMineColoniesTexture() throws java.io.IOException
    {
        final ClassLoader loader = getClass().getClassLoader();
        org.junit.jupiter.api.Assumptions.assumeTrue(
            loader.getResource("assets/minecolonies/textures/entity/citizen/default/citizenmale1_a.png") != null,
            "MineColonies resources are not on the test classpath");
        final List<String> bad = new ArrayList<>();
        final String[] styles = {"Medieval Oak", "Nordic", "Asian", "Athens", "Nether", "Modern", "Undead", "Something else"};
        for (final String style : styles)
            for (final CitizenRole role : CitizenRole.values())
                for (final boolean female : new boolean[] {false, true})
                    for (long seed = 0; seed < 64; seed++)
                    {
                        final String key = CitizenAppearance.textureKey(style, role, female, seed << 5 | seed << 11);
                        if (humanoid(loader, key)) continue;
                        if (!humanoid(loader, "default/" + key.substring(key.indexOf('/') + 1))) bad.add(key);
                    }
        assertTrue(bad.isEmpty(), () -> "keys without a humanoid texture (even in default/): " + bad.stream().distinct().toList());
    }

    private static boolean humanoid(final ClassLoader loader, final String key) throws java.io.IOException
    {
        try (var stream = loader.getResourceAsStream("assets/minecolonies/textures/entity/citizen/" + key + ".png"))
        {
            if (stream == null) return false;
            final java.nio.ByteBuffer header = java.nio.ByteBuffer.wrap(stream.readNBytes(24));
            return CitizenAppearance.humanoidLayout(header.getInt(16), header.getInt(20));
        }
    }
}
