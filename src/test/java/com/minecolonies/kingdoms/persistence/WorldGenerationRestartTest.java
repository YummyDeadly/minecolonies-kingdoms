package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.world.road.RoadRecord;
import com.minecolonies.kingdoms.world.road.RoadStatus;
import com.minecolonies.kingdoms.world.road.RoadType;
import com.minecolonies.kingdoms.world.settlement.SettlementPhysicalState;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import com.minecolonies.kingdoms.world.settlement.SettlementRegion;
import com.minecolonies.kingdoms.world.settlement.SettlementType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorldGenerationRestartTest
{
    @Test
    void settlementRoadIdsAndGenerationMarkersSurviveRestartWithoutDuplicates()
    {
        final ResourceLocation dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        final UUID firstId = UUID.nameUUIDFromBytes("first".getBytes());
        final UUID secondId = UUID.nameUUIDFromBytes("second".getBytes());
        final SettlementRecord first = settlement(firstId, dimension, 0);
        final SettlementRecord second = settlement(secondId, dimension, 256);
        first.markChunkGenerated(12L);
        final RoadRecord road = new RoadRecord(UUID.nameUUIDFromBytes("road".getBytes()), firstId, secondId, dimension,
            RoadType.DIRT, List.of(first.gate(), second.gate()), RoadStatus.GENERATING, 3);
        road.markGenerated(99L);
        final KingdomsSavedData data = new KingdomsSavedData();
        data.settlements().put(first); data.settlements().put(second); data.roads().put(road);
        final KingdomsSavedData loaded = KingdomsSavedData.load(data.save(new CompoundTag(), null), null);
        assertEquals(2, loaded.settlements().records().size());
        assertEquals(1, loaded.roads().roads().size());
        assertEquals(firstId, loaded.settlements().get(firstId).orElseThrow().id());
        assertFalse(loaded.roads().get(road.id()).orElseThrow().needsGeneration(99L));
        assertFalse(loaded.settlements().put(first));
        assertFalse(loaded.roads().put(road));
    }

    private static SettlementRecord settlement(final UUID id, final ResourceLocation dimension, final int x)
    {
        return new SettlementRecord(id, "Restart " + x, SettlementType.VILLAGE, dimension, new BlockPos(x, 64, 0), 0,
            new BlockPos(x, 64, 8), UUID.nameUUIDFromBytes(("f" + x).getBytes()), 12,
            SettlementPhysicalState.GENERATING, new SettlementRegion(dimension, x / 512, 0), null, 0);
    }
}
