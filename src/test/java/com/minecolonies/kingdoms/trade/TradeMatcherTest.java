package com.minecolonies.kingdoms.trade;

import com.minecolonies.kingdoms.economy.EconomicResource;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeMatcherTest
{
    private static final ResourceLocation OVERWORLD = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
    private static final UUID EXPORTER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID IMPORTER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FACTION_A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID FACTION_B = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private final TradeMatcher matcher = new TradeMatcher();
    private final TradePermissionPolicy allowAll = (offer, demand, lookup) -> true;

    @Test
    void surplusAndDeficitProduceRouteCandidateUsingSmallerDemand()
    {
        final List<TradeMatchCandidate> result = match(
            List.of(offer(EXPORTER, OVERWORLD, BlockPos.ZERO, 500L)),
            List.of(demand(IMPORTER, OVERWORLD, new BlockPos(100, 0, 0), 300L)),
            1_000.0D,
            1L,
            1_000L);

        assertEquals(1, result.size());
        assertEquals(300L, result.getFirst().amount());
        assertEquals(EconomicResource.FOOD, result.getFirst().resource());
    }

    @Test
    void exporterCapacityLimitsShipmentAmount()
    {
        final List<TradeMatchCandidate> result = match(
            List.of(offer(EXPORTER, OVERWORLD, BlockPos.ZERO, 100L)),
            List.of(demand(IMPORTER, OVERWORLD, new BlockPos(100, 0, 0), 500L)),
            1_000.0D,
            1L,
            1_000L);

        assertEquals(100L, result.getFirst().amount());
    }

    @Test
    void noDemandProducesNoRoute()
    {
        assertTrue(match(
            List.of(offer(EXPORTER, OVERWORLD, BlockPos.ZERO, 500L)),
            List.of(),
            1_000.0D,
            1L,
            1_000L).isEmpty());
    }

    @Test
    void crossDimensionTradeIsRejected()
    {
        final ResourceLocation nether = ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");
        assertTrue(match(
            List.of(offer(EXPORTER, OVERWORLD, BlockPos.ZERO, 500L)),
            List.of(demand(IMPORTER, nether, BlockPos.ZERO, 300L)),
            1_000.0D,
            1L,
            1_000L).isEmpty());
    }

    @Test
    void excessiveDistanceIsRejected()
    {
        assertTrue(match(
            List.of(offer(EXPORTER, OVERWORLD, BlockPos.ZERO, 500L)),
            List.of(demand(IMPORTER, OVERWORLD, new BlockPos(2_000, 0, 0), 300L)),
            1_000.0D,
            1L,
            1_000L).isEmpty());
    }

    @Test
    void insertionOrderDoesNotChangeMatchingResult()
    {
        final UUID secondExporter = UUID.fromString("00000000-0000-0000-0000-000000000003");
        final UUID secondImporter = UUID.fromString("00000000-0000-0000-0000-000000000004");
        final List<TradeOffer> offers = new ArrayList<>(List.of(
            offer(EXPORTER, OVERWORLD, BlockPos.ZERO, 500L),
            offer(secondExporter, OVERWORLD, new BlockPos(500, 0, 0), 500L)));
        final List<TradeDemand> demands = new ArrayList<>(List.of(
            demand(IMPORTER, OVERWORLD, new BlockPos(100, 0, 0), 300L),
            demand(secondImporter, OVERWORLD, new BlockPos(400, 0, 0), 300L)));
        final List<TradeMatchCandidate> expected = match(offers, demands, 1_000.0D, 1L, 1_000L);

        java.util.Collections.reverse(offers);
        java.util.Collections.reverse(demands);

        assertEquals(expected, match(offers, demands, 1_000.0D, 1L, 1_000L));
    }

    private List<TradeMatchCandidate> match(
        final List<TradeOffer> offers,
        final List<TradeDemand> demands,
        final double distance,
        final long minimum,
        final long maximum)
    {
        return matcher.match(offers, demands, allowAll, id -> Optional.empty(), distance, minimum, maximum);
    }

    private static TradeOffer offer(
        final UUID id,
        final ResourceLocation dimension,
        final BlockPos center,
        final long amount)
    {
        return new TradeOffer(id, FACTION_A, dimension, center, EconomicResource.FOOD, amount, 0);
    }

    private static TradeDemand demand(
        final UUID id,
        final ResourceLocation dimension,
        final BlockPos center,
        final long amount)
    {
        return new TradeDemand(id, FACTION_B, dimension, center, EconomicResource.FOOD, amount, 0);
    }
}
