package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractRules;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractStatus;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.persistence.PersistenceTestAccess;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.minecolonies.kingdoms.bandit.BanditFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Phase 8.1 bandit camps: creation, placement rules, threat, clearing, breaking up, cooldown, restart, recruitment. */
class CampTest
{
    private static final long EVALUATION = 1_200L;

    private static final class Wallet implements ContractService.InventoryPort
    {
        int emeralds;
        @Override public List<ContractRules.Slot> slots(final EconomicResource resource) { return List.of(); }
        @Override public Runnable remove(final List<ContractRules.Removal> removals) { return () -> { }; }
        @Override public void give(final int amount) { emeralds += amount; }
    }

    private static RoadRecord road(final KingdomsSavedData data) { return data.roads().get(ROAD).orElseThrow(); }

    /** Runs {@code count} threat evaluations starting at {@code from}, keeping the road's threat pinned at {@code threat}. */
    private static long evaluate(final KingdomsSavedData data, final long from, final int count, final double threat, final BanditSettings settings)
    {
        long time = from;
        for (int index = 0; index < count; index++)
        {
            data.bandits().threatFor(ROAD).setThreat(threat);
            ThreatEvaluator.evaluate(data, value -> 1_000.0D, anchors(data), time, settings, CONTRACTS);
            time += EVALUATION;
        }
        return time;
    }

    private static BanditCamp forceCamp(final KingdomsSavedData data, final long gameTime)
    {
        return CampService.establish(data, road(data), anchors(data), gameTime, SETTINGS, true).orElseThrow();
    }

    private static BanditEncounter fight(final KingdomsSavedData data, final BanditCamp camp)
    {
        return data.bandits().encounter(camp.encounterId()).orElseThrow();
    }

    // ------------------------------------------------------------------------------------------------ creation

    @Test
    void campsAppearOnlyAfterSustainedThreatAndOnlyOncePerRoad()
    {
        final KingdomsSavedData data = world();
        final int needed = SETTINGS.camps().pressureEvaluations();
        long time = evaluate(data, 0L, needed - 1, 60.0D, SETTINGS);
        assertTrue(data.bandits().camps().isEmpty(), "a short spike never settles bandits");
        evaluate(data, time, 1, 30.0D, SETTINGS);
        assertEquals(0, data.bandits().threatFor(ROAD).campPressure(), "a quiet evaluation resets the pressure");
        time = evaluate(data, time + EVALUATION, needed, 60.0D, SETTINGS);
        assertEquals(1, data.bandits().camps().size(), "sustained danger settles one camp");
        final BanditCamp camp = data.bandits().camps().iterator().next();
        assertEquals(CampPlanner.campId(ROAD, 1), camp.id(), "stable ID from the road and the camp ordinal");
        final BanditEncounter fight = fight(data, camp);
        assertEquals(BanditEncounter.Kind.CAMP, fight.kind());
        assertEquals(BanditEncounter.Status.ACTIVE, fight.status());
        assertEquals(camp.position(), fight.position());
        assertEquals(ThreatRules.campStrength(60.0D - SETTINGS.threatStep(), SETTINGS.camps()), camp.strength(),
            "strength follows the threat after the evaluation's step");
        evaluate(data, time, needed * 3, 60.0D, SETTINGS);
        assertEquals(1, data.bandits().camps().size(), "never a second camp beside the same road");
        assertTrue(data.bandits().threatFor(ROAD).lastContributors().camp() > 0.0D, "an active camp raises its road's threat target");
        assertEquals(SETTINGS.camps().threatContribution(), data.bandits().threatFor(ROAD).lastContributors().camp());
    }

    @Test
    void capsAndSwitchesStopNewCamps()
    {
        final CampSettings d = CampSettings.defaults();
        final BanditSettings capped = SETTINGS.withCamps(new CampSettings(true, d.threshold(), d.pressureEvaluations(), 0, d.minStrength(),
            d.maxStrength(), d.lifetimeTicks(), d.respawnCooldownTicks(), d.threatContribution(), d.recruitIntervalTicks(), d.structures(),
            d.maxInhabitedTicks()));
        final KingdomsSavedData full = world();
        evaluate(full, 0L, 10, 80.0D, capped);
        assertTrue(full.bandits().camps().isEmpty(), "the global camp cap holds");
        final KingdomsSavedData off = world();
        evaluate(off, 0L, 10, 80.0D, SETTINGS.withCamps(CampSettings.disabled()));
        assertTrue(off.bandits().camps().isEmpty(), "camps can be switched off");
        final KingdomsSavedData suppressed = world();
        suppressed.bandits().threatFor(ROAD).cleared(0L, 1_000_000L, 0L);
        evaluate(suppressed, 0L, 10, 80.0D, SETTINGS);
        assertTrue(suppressed.bandits().camps().isEmpty(), "no camp while the road is suppressed");
        assertTrue(CampService.establish(suppressed, road(suppressed), anchors(suppressed), 0L, SETTINGS, true).isPresent(),
            "the operator test ignores suppression");
        assertTrue(CampService.establish(suppressed, road(suppressed), anchors(suppressed), 0L, SETTINGS, true).isEmpty(),
            "but never adds a second camp beside the road");
    }

    @Test
    void campSitesKeepAwayFromTownsBridgesAndTheCarriageway()
    {
        final KingdomsSavedData data = world();
        final List<Vec3> anchors = anchors(data);
        final List<BlockPos> sites = CampPlanner.sites(road(data), anchors, SETTINGS.settlementExclusionRadius(), List.of(), 42L);
        assertFalse(sites.isEmpty());
        assertTrue(sites.size() <= CampPlanner.MAX_SITES);
        assertEquals(sites, CampPlanner.sites(road(data), anchors, SETTINGS.settlementExclusionRadius(), List.of(), 42L), "deterministic");
        for (final BlockPos site : sites)
        {
            assertTrue(EncounterPlanner.outsideSettlements(Vec3.atCenterOf(site), anchors,
                SETTINGS.settlementExclusionRadius() + CampPlanner.SETTLEMENT_MARGIN), "outside town zones plus a margin: " + site);
            assertFalse(site.getX() >= 880 - 64 && site.getX() <= 976 + 64, "away from the bridge: " + site);
            assertEquals(CampPlanner.OFFSET, Math.abs(site.getZ()), "beside the road, off the carriageway: " + site);
        }
        final BlockPos other = sites.getFirst();
        final List<BlockPos> spaced = CampPlanner.sites(road(data), anchors, SETTINGS.settlementExclusionRadius(), List.of(other), 42L);
        assertTrue(spaced.stream().allMatch(site -> CampPlanner.horizontalDistanceSqr(site, other)
            >= (double) CampPlanner.CAMP_SPACING * CampPlanner.CAMP_SPACING), "camps keep their distance from each other");
        assertTrue(CampPlanner.sites(road(data), anchors, 5_000, List.of(), 42L).isEmpty(), "no remote stretch, no camp");
    }

    // ------------------------------------------------------------------------------------------------ clearing and ending

    @Test
    void clearingACampPaysOnceSuppressesTheRoadAndStartsTheCooldown()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final BanditEncounter fight = fight(data, camp);
        SecurityContracts.post(data, 10L, SETTINGS, CONTRACTS);
        final Contract clear = data.contracts().targeting(fight.id()).getFirst();
        assertEquals(Contract.Kind.CLEAR_CAMP, clear.kind());
        assertEquals(SecurityContracts.campReward(camp.strength()), clear.objective().baseReward());
        assertTrue(ContractService.accept(data, PLAYER, clear, 20L, CONTRACTS).isEmpty());

        fight.defender(PLAYER);
        while (fight.remainingStrength() > 0) fight.banditLost();
        final List<EncounterService.Resolution> settled = EncounterService.resolveDue(data, 500L, SETTINGS, CONTRACTS);
        assertEquals(1, settled.size());
        assertEquals(BanditEncounter.Status.RESOLVED_PLAYER, fight.status());
        assertEquals(BanditCamp.Status.CLEARED, camp.status());
        assertEquals(BanditEncounter.Cause.PLAYER_VICTORY, camp.endCause());
        assertEquals(ContractStatus.COMPLETED, clear.status());
        assertEquals(SecurityContracts.CAMP_REPUTATION, ReputationService.standing(data, PLAYER, clear.factionId()),
            "contract reputation only (no extra defender bonus for the holder)");
        final RoadThreat threat = data.bandits().threatFor(ROAD);
        assertTrue(threat.suppressedAt(501L), "the road is suppressed after the camp falls");
        assertTrue(threat.campCoolingDownAt(501L), "and no camp returns for a while");
        assertTrue(threat.campCoolingDownAt(500L + SETTINGS.camps().respawnCooldownTicks() - 1L));

        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final Wallet wallet = new Wallet();
        assertEquals(clear.agreedReward(), ContractService.claimPendingRewards(restarted, PLAYER, wallet, 600L));
        assertEquals(0, ContractService.claimPendingRewards(restarted, PLAYER, wallet, 601L), "paid exactly once");
        final BanditEncounter reloaded = restarted.bandits().encounter(fight.id()).orElseThrow();
        assertFalse(EncounterService.resolve(restarted, reloaded, new EncounterRules.Decision(EncounterRules.Outcome.BANDITS_DEFEATED, 0, 0),
            BanditEncounter.Cause.PLAYER_VICTORY, List.of(PLAYER), 700L, SETTINGS, CONTRACTS).applied(), "never cleared twice");
        assertEquals(SecurityContracts.CAMP_REPUTATION, ReputationService.standing(restarted, PLAYER, clear.factionId()));

        final long cooled = 500L + Math.max(SETTINGS.camps().respawnCooldownTicks(), SETTINGS.suppressionTicks()) + 1L;
        evaluate(restarted, 1_000L, 10, 80.0D, SETTINGS);
        assertEquals(1, restarted.bandits().camps().size(), "no new camp during the cooldown");
        evaluate(restarted, cooled, SETTINGS.camps().pressureEvaluations(), 80.0D, SETTINGS);
        assertEquals(2, restarted.bandits().camps().size(), "a new camp may settle after the cooldown");
        assertTrue(restarted.bandits().camp(CampPlanner.campId(ROAD, 2)).isPresent(), "with the next ordinal and a new ID");
    }

    @Test
    void aCampNobodyClearsBreaksUpAndFailsTheAcceptedContract()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final BanditEncounter fight = fight(data, camp);
        SecurityContracts.post(data, 10L, SETTINGS, CONTRACTS);
        final Contract clear = data.contracts().targeting(fight.id()).getFirst();
        assertTrue(ContractService.accept(data, PLAYER, clear, 20L, CONTRACTS).isEmpty());
        assertTrue(EncounterService.resolveDue(data, camp.expiresAt() - 1L, SETTINGS, CONTRACTS).isEmpty(), "not before its lifetime");
        EncounterService.resolveDue(data, camp.expiresAt(), SETTINGS, CONTRACTS);
        assertEquals(BanditEncounter.Status.EXPIRED, fight.status());
        assertEquals(BanditCamp.Status.DISBANDED, camp.status());
        assertEquals(BanditEncounter.Cause.LIFETIME_OVER, camp.endCause());
        assertEquals(ContractStatus.FAILED, clear.status(), "the camp was not cleared in time");
        assertEquals(-CONTRACTS.failPenalty(), ReputationService.standing(data, PLAYER, clear.factionId()));
        assertTrue(data.bandits().threatFor(ROAD).campCoolingDownAt(camp.expiresAt() + 1L));
        assertTrue(data.bandits().activeCamps().isEmpty());

        final KingdomsSavedData gone = world();
        final BanditCamp orphan = forceCamp(gone, 0L);
        SecurityContracts.post(gone, 10L, SETTINGS, CONTRACTS);
        final Contract offer = gone.contracts().targeting(orphan.encounterId()).getFirst();
        EncounterService.cancel(gone, fight(gone, orphan), BanditEncounter.Cause.ROAD_GONE, 50L, SETTINGS, CONTRACTS);
        assertEquals(BanditCamp.Status.DISBANDED, orphan.status());
        assertEquals(ContractStatus.CANCELLED, offer.status(), "an offer is simply withdrawn");
    }

    // ------------------------------------------------------------------------------------------------ restart and recruitment

    @Test
    void restartKeepsTheCampItsSiteAndItsBlocksAndNeverDuplicatesIt()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final int far = java.util.stream.IntStream.range(0, camp.sites().size())
            .filter(index -> !CampPlanner.withinRelocation(camp.sites().getFirst(), camp.sites().get(index))).findFirst().orElse(-1);
        assertTrue(far > 0, "the fixture offers sites far apart");
        assertFalse(CampService.useSite(data, camp, far), "a camp never moves away from where its contract points");
        assertEquals(camp.sites().getFirst(), fight(data, camp).position());
        assertTrue(CampPlanner.withinRelocation(camp.sites().getFirst(), camp.sites().get(1)), "the twin site across the road is near");
        assertTrue(CampService.useSite(data, camp, 1), "an abstract camp may settle on another candidate site");
        assertEquals(camp.sites().get(1), fight(data, camp).position(), "its fight moves with it");
        CampService.built(data, camp, List.of(new BanditCamp.PlacedBlock(camp.position(), "minecraft:campfire")), 100L);
        assertThrows(IllegalStateException.class, () -> camp.useSite(0), "a built camp never moves");
        fight(data, camp).representation(BanditEncounter.Representation.PHYSICAL);
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final BanditCamp reloaded = restarted.bandits().camp(camp.id()).orElseThrow();
        assertEquals(camp.position(), reloaded.position());
        assertEquals(BanditCamp.Structure.BUILT, reloaded.structure());
        assertEquals(camp.placed(), reloaded.placed(), "the placed-block record survives, so the blocks can be taken down later");
        assertEquals(BanditEncounter.Representation.ABSTRACT, fight(restarted, reloaded).representation(), "no bandit survives a restart");
        assertEquals(camp.position(), fight(restarted, reloaded).position());
        evaluate(restarted, 0L, 10, 80.0D, SETTINGS);
        assertEquals(1, restarted.bandits().camps().size(), "a restart never creates a second camp");
        assertFalse(CampService.useSite(restarted, reloaded, 0), "and never moves a built one");
    }

    @Test
    void unobservedCampsRecruitUpToTheirStrengthButNotWhileSeen()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final BanditEncounter fight = fight(data, camp);
        fight.banditLost();
        fight.banditLost();
        final int wounded = fight.remainingStrength();
        final long interval = SETTINGS.camps().recruitIntervalTicks();
        assertTrue(CampService.recruit(data, interval - 1L, SETTINGS).isEmpty(), "not before the interval");
        fight.representation(BanditEncounter.Representation.PHYSICAL);
        assertTrue(CampService.recruit(data, interval, SETTINGS).isEmpty(), "never while players see the camp");
        fight.representation(BanditEncounter.Representation.ABSTRACT);
        assertTrue(CampService.recruit(data, interval, SETTINGS).isEmpty(), "being seen restarted the clock");
        assertEquals(1, CampService.recruit(data, 2 * interval, SETTINGS).size());
        assertEquals(wounded + 1, fight.remainingStrength());
        assertTrue(CampService.recruit(data, 2 * interval + 10L, SETTINGS).isEmpty(), "one per interval");
        for (long time = 3 * interval; time < 20 * interval; time += interval) CampService.recruit(data, time, SETTINGS);
        assertEquals(camp.strength(), fight.remainingStrength(), "never above the camp's strength");
        while (fight.remainingStrength() > 0) fight.banditLost();
        assertTrue(CampService.recruit(data, 30 * interval, SETTINGS).isEmpty(), "a beaten camp does not rise again");
    }

    // ------------------------------------------------------------------------------------------------ review regressions

    @Test
    void theContractHolderIsAlwaysCreditedEvenBehindACrowdOfHelpers()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final BanditEncounter fight = fight(data, camp);
        SecurityContracts.post(data, 10L, SETTINGS, CONTRACTS);
        final Contract clear = data.contracts().targeting(fight.id()).getFirst();
        assertTrue(ContractService.accept(data, PLAYER, clear, 20L, CONTRACTS).isEmpty());
        for (int index = 0; index < BanditEncounter.MAX_DEFENDERS + 3; index++)
            EncounterService.recordDefender(data, fight, id("helper-" + index));
        assertEquals(BanditEncounter.MAX_DEFENDERS, fight.defenders().size(), "helpers are capped");
        EncounterService.recordDefender(data, fight, PLAYER);
        assertTrue(fight.defenders().contains(PLAYER), "the holder who fights is always credited");
        final KingdomsSavedData restarted = PersistenceTestAccess.reload(data);
        final BanditEncounter reloaded = restarted.bandits().encounter(fight.id()).orElseThrow();
        assertTrue(reloaded.defenders().contains(PLAYER), "and stays credited across a restart");
        while (reloaded.remainingStrength() > 0) reloaded.banditLost();
        EncounterService.resolveDue(restarted, 500L, SETTINGS, CONTRACTS);
        assertEquals(ContractStatus.COMPLETED, restarted.contracts().get(clear.id()).orElseThrow().status(), "and gets paid");
    }

    @Test
    void aCampWhoseFightIsLostOrEndedIsReconciledAndAnOrphanFightIsCancelled()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag(), null);
        final net.minecraft.nbt.ListTag encounters = saved.getCompound("bandits").getList("encounters", 10);
        for (int index = encounters.size() - 1; index >= 0; index--)
            if (encounters.getCompound(index).getUUID("id").equals(camp.encounterId())) encounters.remove(index);
        final KingdomsSavedData broken = PersistenceTestAccess.load(saved);
        assertTrue(broken.bandits().camp(camp.id()).orElseThrow().active(), "a save without the camp's fight still has the camp");
        evaluate(broken, 0L, 1, 10.0D, SETTINGS);
        assertTrue(broken.bandits().activeCamps().isEmpty(), "the next evaluation ends a camp whose fight is gone (and prunes it)");
        assertTrue(broken.bandits().threatFor(ROAD).campCoolingDownAt(1L));
        assertEquals(0.0D, CampService.contribution(broken, ROAD, SETTINGS), "it no longer raises the road's threat");

        final KingdomsSavedData orphaned = world();
        final BanditCamp lost = forceCamp(orphaned, 0L);
        final net.minecraft.nbt.CompoundTag orphanSave = orphaned.save(new net.minecraft.nbt.CompoundTag(), null);
        orphanSave.getCompound("bandits").put("camps", new net.minecraft.nbt.ListTag());
        final KingdomsSavedData withoutCamp = PersistenceTestAccess.load(orphanSave);
        evaluate(withoutCamp, 0L, 1, 10.0D, SETTINGS);
        assertFalse(withoutCamp.bandits().encounter(lost.encounterId()).orElseThrow().open(), "a camp fight without its camp is cancelled");
        assertTrue(CampService.establish(withoutCamp, road(withoutCamp), anchors(withoutCamp), 10L, SETTINGS, true).isPresent(),
            "and the road can have a camp again (a fresh ordinal, never a clash with the old IDs)");

        final KingdomsSavedData operator = world();
        final BanditCamp disbanded = forceCamp(operator, 0L);
        assertTrue(CampService.disband(operator, disbanded, 50L, SETTINGS, CONTRACTS));
        assertEquals(BanditCamp.Status.DISBANDED, disbanded.status());
        assertEquals(BanditEncounter.Status.CANCELLED, fight(operator, disbanded).status());
        assertFalse(CampService.disband(operator, disbanded, 60L, SETTINGS, CONTRACTS), "once");
    }

    @Test
    void aFightThatOutlivesTheCampGetsOneGracePeriodWhenPlayersStepAway()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final BanditEncounter fight = fight(data, camp);
        final long end = fight.expiresAt();
        assertFalse(fight.deferExpiry(end + 10L, 1_200L), "nobody fought: no grace");
        fight.defender(PLAYER);
        assertTrue(fight.deferExpiry(end + 10L, 1_200L));
        assertEquals(end + 1_210L, fight.expiresAt());
        assertFalse(fight.deferExpiry(end + 1_000L, 1_200L), "only once, so a camp cannot be kept alive by visiting it");
        assertTrue(EncounterService.resolveDue(data, end + 1_000L, SETTINGS, CONTRACTS).isEmpty());
        assertEquals(1, EncounterService.resolveDue(data, end + 1_210L, SETTINGS, CONTRACTS).size());
        assertTrue(PersistenceTestAccess.reload(data).bandits().encounter(fight.id()).orElseThrow().expiryDeferred());
    }

    @Test
    void recruitmentStartsAgainOnlyAfterPlayersLeave()
    {
        final KingdomsSavedData data = world();
        final BanditCamp camp = forceCamp(data, 0L);
        final BanditEncounter fight = fight(data, camp);
        fight.banditLost();
        final long interval = SETTINGS.camps().recruitIntervalTicks();
        fight.representation(BanditEncounter.Representation.PHYSICAL);
        CampService.recruit(data, 5L * interval, SETTINGS);
        fight.representation(BanditEncounter.Representation.ABSTRACT);
        assertTrue(CampService.recruit(data, 5L * interval + 20L, SETTINGS).isEmpty(), "no instant recruit when players leave");
        assertEquals(1, CampService.recruit(data, 6L * interval, SETTINGS).size());
    }

    // ------------------------------------------------------------------------------------------------ physical site rules

    /** A flat meadow at y=64: natural ground, open above, loaded, owned, no block entities, no road. */
    private static class Meadow implements CampSite.View
    {
        final Set<Long> water = new HashSet<>();
        final Set<Long> raised = new HashSet<>();
        final Set<Long> obstructed = new HashSet<>();
        final Set<Long> entities = new HashSet<>();
        final Set<Long> roadColumns = new HashSet<>();
        boolean loaded = true;
        boolean owned = true;

        static long key(final int x, final int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

        @Override public boolean ticking(final int x, final int z) { return loaded; }
        @Override public boolean owned(final int chunkX, final int chunkZ) { return owned; }
        @Override public int surface(final int x, final int z) { return raised.contains(key(x, z)) ? 68 : 64; }
        @Override public boolean naturalGround(final int x, final int y, final int z) { return !water.contains(key(x, z)); }
        @Override public boolean replaceable(final int x, final int y, final int z) { return !obstructed.contains(key(x, z)); }
        @Override public boolean blockEntity(final int x, final int y, final int z) { return entities.contains(key(x, z)); }
        @Override public boolean road(final int x, final int z) { return roadColumns.contains(key(x, z)); }
    }

    @Test
    void siteRulesRejectWaterSlopesTreesBuildsBlockEntitiesRoadsAndPlayerLand()
    {
        final BlockPos site = new BlockPos(100, 64, 100);
        assertTrue(CampSite.check(site, new Meadow()).valid(), "a flat meadow beside the road is fine");
        final Meadow unloaded = new Meadow();
        unloaded.loaded = false;
        assertTrue(CampSite.check(site, unloaded).retry(), "unloaded chunks: wait, never load");
        final Meadow players = new Meadow();
        players.owned = false;
        assertFalse(CampSite.check(site, players).valid(), "players' land (long inhabited, not new) is never built on");
        assertFalse(CampSite.check(site, players).retry());
        final Meadow pond = new Meadow();
        pond.water.add(Meadow.key(102, 101));
        assertFalse(CampSite.check(site, pond).valid(), "water or player-made ground");
        final Meadow hill = new Meadow();
        hill.raised.add(Meadow.key(96, 96));
        assertFalse(CampSite.check(site, hill).valid(), "steep ground or a tree trunk");
        final Meadow wood = new Meadow();
        wood.obstructed.add(Meadow.key(104, 104));
        assertFalse(CampSite.check(site, wood).valid(), "anything solid above the ground");
        final Meadow chest = new Meadow();
        chest.entities.add(Meadow.key(105, 95));
        assertFalse(CampSite.check(site, chest).valid(), "a block entity even just outside the footprint");
        final Meadow carriageway = new Meadow();
        carriageway.roadColumns.add(Meadow.key(100, 97));
        assertFalse(CampSite.check(site, carriageway).valid(), "never on a road");
        assertFalse(CampSite.check(site.above(20), new Meadow()).valid(), "never far above or below the road");
    }

    @Test
    void theLayoutIsSmallDeterministicAndHoldsNoItems()
    {
        final List<CampSite.Part> layout = CampSite.layout(7L);
        assertEquals(layout, CampSite.layout(7L));
        assertTrue(layout.size() <= BanditCamp.MAX_PLACED);
        assertTrue(layout.stream().allMatch(part -> Math.abs(part.dx()) <= CampSite.RADIUS && Math.abs(part.dz()) <= CampSite.RADIUS),
            "inside the checked footprint");
        assertTrue(layout.stream().noneMatch(part -> part.blockId().contains("chest") || part.blockId().contains("barrel")),
            "no containers: a camp never hands out loot");
        final List<CampSite.Placement> placements = CampSite.placements(new BlockPos(0, 64, 0), 7L, new Meadow());
        assertEquals(layout.size(), placements.size());
        assertEquals(1, placements.stream().filter(value -> value.blockId().equals("minecraft:campfire")).count());
    }
}
