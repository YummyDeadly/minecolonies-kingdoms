package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.colony.decision.StrategicDecision;
import com.minecolonies.kingdoms.caravan.CaravanLedger;
import com.minecolonies.kingdoms.economy.ColonyEconomyState;
import com.minecolonies.kingdoms.trade.TradeLedger;
import com.minecolonies.kingdoms.world.settlement.growth.SettlementBuildingType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class KingdomsDataMigrator
{
    private KingdomsDataMigrator()
    {
    }

    public static CompoundTag migrate(final CompoundTag input, final int targetVersion)
    {
        final CompoundTag migrated = input.copy();
        int version = migrated.contains("dataVersion", Tag.TAG_INT) ? migrated.getInt("dataVersion") : 1;
        if (version > targetVersion)
        {
            throw new IllegalStateException("Save schema " + version + " is newer than supported schema " + targetVersion);
        }
        while (version < targetVersion)
        {
            version = switch (version)
            {
                case 1 -> migrateV1ToV2(migrated);
                case 2 -> migrateV2ToV3(migrated);
                case 3 -> migrateV3ToV4(migrated);
                case 4 -> migrateV4ToV5(migrated);
                case 5 -> migrateV5ToV6(migrated);
                case 6 -> migrateV6ToV7(migrated);
                case 7 -> migrateV7ToV8(migrated);
                case 8 -> migrateV8ToV9(migrated);
                case 9 -> migrateV9ToV10(migrated);
                case 10 -> migrateV10ToV11(migrated);
                case 11 -> migrateV11ToV12(migrated);
                case 12 -> migrateV12ToV13(migrated);
                default -> throw new IllegalStateException("No migration path from schema " + version);
            };
        }
        migrated.putInt("dataVersion", version);
        return migrated;
    }

    private static int migrateV1ToV2(final CompoundTag root)
    {
        final ListTag colonies = root.getList("colonies", Tag.TAG_COMPOUND);
        colonies.forEach(value -> {
            final CompoundTag colony = (CompoundTag) value;
            if (!colony.contains("economy", Tag.TAG_COMPOUND))
            {
                colony.put("economy", new ColonyEconomyState().save());
            }
            if (!colony.contains("needs", Tag.TAG_LIST))
            {
                colony.put("needs", new ListTag());
            }
            if (!colony.contains("lastDecision", Tag.TAG_COMPOUND))
            {
                colony.put("lastDecision", StrategicDecision.none(0L).save());
            }
        });
        return 2;
    }

    private static int migrateV2ToV3(final CompoundTag root)
    {
        root.getList("colonies", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag colony = (CompoundTag) value;
            if (!colony.contains("createdAt", Tag.TAG_LONG))
            {
                colony.putLong("createdAt", 0L);
            }
            if (!colony.contains("kind", Tag.TAG_STRING))
            {
                colony.putString("kind", colony.getBoolean("playerManaged")
                    ? "PLAYER_PHYSICAL"
                    : "NPC_PHYSICAL");
            }
        });
        if (!root.contains("trade", Tag.TAG_COMPOUND))
        {
            root.put("trade", new TradeLedger().save());
        }
        return 3;
    }

    private static int migrateV3ToV4(final CompoundTag root)
    {
        final CompoundTag trade = root.getCompound("trade");
        trade.getList("shipments", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag shipment = (CompoundTag) value;
            final long departure = shipment.getLong("departureAt");
            final long arrival = shipment.getLong("arrivalAt");
            if (!shipment.contains("travelDurationTicks", Tag.TAG_LONG))
            {
                shipment.putLong("travelDurationTicks", Math.max(1L, arrival - departure));
            }
            if (!shipment.contains("progressUpdatedAt", Tag.TAG_LONG))
            {
                shipment.putLong("progressUpdatedAt", departure);
            }
            if (!shipment.contains("progress", Tag.TAG_DOUBLE))
            {
                shipment.putDouble("progress", "DELIVERED".equals(shipment.getString("status")) ? 1.0D : 0.0D);
            }
        });
        if (!root.contains("caravans", Tag.TAG_COMPOUND))
        {
            root.put("caravans", new CaravanLedger().save());
        }
        return 4;
    }

    private static int migrateV4ToV5(final CompoundTag root)
    {
        if (!root.contains("settlements", Tag.TAG_COMPOUND)) root.put("settlements", new CompoundTag());
        if (!root.contains("roads", Tag.TAG_COMPOUND)) root.put("roads", new CompoundTag());
        return 5;
    }

    private static int migrateV5ToV6(final CompoundTag root)
    {
        if (!root.contains("growth", Tag.TAG_COMPOUND)) root.put("growth", new CompoundTag());
        return 6;
    }

    private static int migrateV6ToV7(final CompoundTag root)
    {
        final CompoundTag growth = root.getCompound("growth");
        if (!growth.contains("layouts", Tag.TAG_LIST)) growth.put("layouts", new ListTag());
        growth.getList("buildings", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag building = (CompoundTag) value;
            if (!building.contains("structureSource", Tag.TAG_STRING))
            {
                building.putString("structureSource", "legacy_procedural");
                building.putString("structureId", "legacy");
                building.putString("styleFamily", "legacy");
                building.putBoolean("mirrored", false);
                final BlockPos anchor = BlockPos.of(building.getLong("anchor"));
                final SettlementBuildingType type = SettlementBuildingType.valueOf(building.getString("type"));
                building.putLong("entrance", anchor.offset(0, 0, type.halfDepth() + 1).asLong());
                building.putInt("footprintMinX", anchor.getX() - type.halfWidth());
                building.putInt("footprintMinZ", anchor.getZ() - type.halfDepth());
                building.putInt("footprintMaxX", anchor.getX() + type.halfWidth());
                building.putInt("footprintMaxZ", anchor.getZ() + type.halfDepth());
            }
        });
        root.getCompound("roads").getList("records", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag road = (CompoundTag) value;
            if (!road.contains("physicalGeometry", Tag.TAG_BYTE)) road.putBoolean("physicalGeometry", true);
        });
        return 7;
    }

    /** Phase 6.7: adds the empty citizen roster registry; no existing identity or progress changes. */
    private static int migrateV8ToV9(final CompoundTag root)
    {
        if (!root.contains("citizens", Tag.TAG_COMPOUND)) root.put("citizens", new CompoundTag());
        return 9;
    }

    /**
     * Phase 7: adds the empty contract registry and diplomacy state. Reputation and relations already existed on
     * factions (unused until now) and are kept as they are.
     */
    private static int migrateV9ToV10(final CompoundTag root)
    {
        if (!root.contains("contracts", Tag.TAG_COMPOUND)) root.put("contracts", new CompoundTag());
        if (!root.contains("diplomacy", Tag.TAG_COMPOUND)) root.put("diplomacy", new CompoundTag());
        return 10;
    }

    /**
     * Phase 7 final model. Schema 10 was written by the preliminary Phase 7 build; this step converts it:
     * <ul>
     *   <li>player standings move from each faction's ambiguous {@code reputation} map (only ever written with player
     *       UUIDs, by that build) into the separate {@code reputation} registry, and the faction field is removed;</li>
     *   <li>contracts get the explicit lifecycle (ACTIVE -> ACCEPTED, ABANDONED -> CANCELLED, WITHDRAWN -> EXPIRED or
     *       CANCELLED), an objective snapshot, and reservation-at-acceptance money: rewards that the old build had
     *       escrowed for mere offers go back to the treasury, accepted contracts keep theirs as the reservation,
     *       completed ones are marked paid;</li>
     *   <li>existing relation values are kept and logged once as MIGRATED events; the old stance history is dropped.</li>
     * </ul>
     */
    private static int migrateV10ToV11(final CompoundTag root)
    {
        final ListTag factions = root.getList("factions", Tag.TAG_COMPOUND);
        final java.util.Map<java.util.UUID, CompoundTag> factionById = new java.util.LinkedHashMap<>();
        for (int index = 0; index < factions.size(); index++)
        {
            final CompoundTag faction = factions.getCompound(index);
            if (faction.hasUUID("id")) factionById.put(faction.getUUID("id"), faction);
        }

        // 1. player reputation -> separate registry
        final java.util.Map<java.util.UUID, ListTag> byPlayer = new java.util.LinkedHashMap<>();
        final ListTag reputationEvents = new ListTag();
        for (final CompoundTag faction : factionById.values())
        {
            final ListTag entries = faction.getList("reputation", Tag.TAG_COMPOUND);
            for (int index = 0; index < entries.size(); index++)
            {
                final CompoundTag entry = entries.getCompound(index);
                if (!entry.hasUUID("id") || entry.getInt("value") == 0) continue;
                final int value = Math.max(-100, Math.min(100, entry.getInt("value")));
                final CompoundTag record = new CompoundTag();
                record.putUUID("faction", faction.getUUID("id"));
                record.putInt("value", value);
                byPlayer.computeIfAbsent(entry.getUUID("id"), key -> new ListTag()).add(record);
                if (reputationEvents.size() < 256)
                {
                    final CompoundTag event = new CompoundTag();
                    event.putLong("time", 0L);
                    event.putUUID("player", entry.getUUID("id"));
                    event.putUUID("faction", faction.getUUID("id"));
                    event.putInt("delta", value);
                    event.putInt("after", value);
                    event.putString("cause", "MIGRATED");
                    reputationEvents.add(event);
                }
            }
            faction.remove("reputation");
        }
        if (!root.contains("reputation", Tag.TAG_COMPOUND))
        {
            final CompoundTag reputation = new CompoundTag();
            final ListTag players = new ListTag();
            byPlayer.forEach((player, records) -> {
                final CompoundTag entry = new CompoundTag();
                entry.putUUID("player", player);
                entry.put("factions", records);
                players.add(entry);
            });
            reputation.put("players", players);
            reputation.put("events", reputationEvents);
            root.put("reputation", reputation);
        }

        // 2. contracts -> explicit lifecycle and reservation at acceptance
        final CompoundTag contracts = root.getCompound("contracts");
        if (contracts.getInt("format") < 2)
        {
            final ListTag converted = new ListTag();
            final ListTag old = contracts.getList("contracts", Tag.TAG_COMPOUND);
            for (int index = 0; index < old.size(); index++)
            {
                final CompoundTag contract = convertPreliminaryContract(old.getCompound(index), factionById);
                if (contract != null) converted.add(contract);
            }
            contracts.put("contracts", converted);
            if (!contracts.contains("cooldowns", Tag.TAG_LIST)) contracts.put("cooldowns", new ListTag());
            contracts.putInt("format", 2);
            root.put("contracts", contracts);
        }

        // 3. relations: keep the values, log each pair once
        final CompoundTag diplomacy = root.getCompound("diplomacy");
        diplomacy.remove("events");
        if (!diplomacy.contains("relationEvents", Tag.TAG_LIST))
        {
            final ListTag relationEvents = new ListTag();
            final java.util.Set<String> seen = new java.util.HashSet<>();
            for (final CompoundTag faction : factionById.values())
            {
                final ListTag relations = faction.getList("relations", Tag.TAG_COMPOUND);
                for (int index = 0; index < relations.size() && relationEvents.size() < 256; index++)
                {
                    final CompoundTag relation = relations.getCompound(index);
                    if (!relation.hasUUID("id")) continue;
                    final java.util.UUID a = faction.getUUID("id");
                    final java.util.UUID b = relation.getUUID("id");
                    final java.util.UUID first = a.compareTo(b) <= 0 ? a : b;
                    final java.util.UUID second = first == a ? b : a;
                    if (!seen.add(first + ":" + second)) continue;
                    final CompoundTag event = new CompoundTag();
                    event.putLong("time", Math.max(0L, diplomacy.getLong("lastEvaluatedAt")));
                    event.putUUID("first", first);
                    event.putUUID("second", second);
                    event.putInt("before", relation.getInt("value"));
                    event.putInt("after", relation.getInt("value"));
                    event.putString("cause", "MIGRATED");
                    event.putLong("evidence", 0L);
                    relationEvents.add(event);
                }
            }
            diplomacy.put("relationEvents", relationEvents);
        }
        root.put("diplomacy", diplomacy);
        return 11;
    }

    private static CompoundTag convertPreliminaryContract(final CompoundTag old, final java.util.Map<java.util.UUID, CompoundTag> factions)
    {
        if (!old.hasUUID("id") || !old.hasUUID("settlement") || !old.hasUUID("faction")) return null;
        final String status = old.getString("status");
        final boolean hasHolder = old.hasUUID("holder");
        final int reward = Math.max(1, old.getInt("reward"));
        final CompoundTag faction = factions.get(old.getUUID("faction"));
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", old.getUUID("id"));
        tag.putInt("sequence", 0);
        tag.putUUID("settlement", old.getUUID("settlement"));
        tag.putUUID("faction", old.getUUID("faction"));
        tag.putString("resource", old.getString("resource"));
        tag.putLong("amount", Math.max(1L, old.getLong("amount")));
        tag.putString("severity", old.contains("severity") ? old.getString("severity") : "MEDIUM");
        tag.putDouble("needCurrent", 0.0D);
        tag.putDouble("needTarget", 0.0D);
        tag.putInt("baseReward", reward);
        tag.putInt("reputationReward", Math.max(0, old.getInt("reputation")));
        final long offeredAt = old.getLong("createdAt");
        tag.putLong("offeredAt", offeredAt);
        tag.putLong("offerExpiresAt", Math.max(offeredAt + 1L, old.getLong("offerExpiresAt")));
        if (hasHolder)
        {
            tag.putUUID("holder", old.getUUID("holder"));
            tag.putString("tier", "NEUTRAL");
        }
        tag.putLong("acceptedAt", old.getLong("acceptedAt"));
        tag.putLong("deadline", old.getLong("deadline"));
        tag.putInt("agreedReward", hasHolder ? reward : 0);
        tag.putLong("delivered", Math.max(0L, old.getLong("delivered")));
        tag.putInt("deliveries", old.getLong("delivered") > 0L ? 1 : 0);
        final long closedAt = old.getLong("closedAt");
        tag.putLong("closedAt", closedAt);
        tag.putLong("rewardIssuedAt", -1L);
        switch (status)
        {
            case "OFFERED" ->
            {
                // the old build escrowed offers; offers now reserve nothing
                if (faction != null) faction.putLong("treasury", faction.getLong("treasury") + reward);
                tag.putString("status", "OFFERED");
            }
            case "ACTIVE" ->
            {
                if (!hasHolder) return null;
                tag.putString("status", "ACCEPTED");
                tag.putInt("reservedReward", reward);
            }
            case "COMPLETED" ->
            {
                if (!hasHolder) return null;
                tag.putString("status", "COMPLETED");
                tag.putString("closeReason", "COMPLETED");
                tag.putBoolean("reputationApplied", true);
                tag.putBoolean("rewardIssued", true);
                tag.putLong("rewardIssuedAt", closedAt);
            }
            case "FAILED" ->
            {
                if (!hasHolder) return null;
                tag.putString("status", "FAILED");
                tag.putString("closeReason", "DEADLINE_MISSED");
                tag.putBoolean("reputationApplied", true);
            }
            case "ABANDONED" ->
            {
                tag.putString("status", "CANCELLED");
                tag.putString("closeReason", "PLAYER_ABANDONED");
                tag.putBoolean("reputationApplied", true);
            }
            case "WITHDRAWN" ->
            {
                tag.putString("status", hasHolder ? "CANCELLED" : "EXPIRED");
                tag.putString("closeReason", hasHolder ? "SETTLEMENT_REMOVED" : "OFFER_EXPIRED");
                tag.putBoolean("reputationApplied", true);
            }
            default -> { return null; }
        }
        return tag;
    }

    /**
     * Phase 8: adds the empty bandit registry. Shipments, contracts (format 2 records read as DELIVERY), reputation,
     * relations, settlements, roads, and citizens are unchanged; shipment bandit-loss fields default to "no loss".
     */
    private static int migrateV11ToV12(final CompoundTag root)
    {
        if (!root.contains("bandits", Tag.TAG_COMPOUND)) root.put("bandits", new CompoundTag());
        return 12;
    }

    /**
     * Phase 8.1 bandit camps: an empty camp list. Road threat records gain camp pressure, cooldown, and a camp
     * contributor, which read as zero/none when absent; nothing else changes.
     */
    private static int migrateV12ToV13(final CompoundTag root)
    {
        if (!root.contains("bandits", Tag.TAG_COMPOUND)) root.put("bandits", new CompoundTag());
        final CompoundTag bandits = root.getCompound("bandits");
        if (!bandits.contains("camps", Tag.TAG_LIST)) bandits.put("camps", new ListTag());
        return 13;
    }

    private static int migrateV7ToV8(final CompoundTag root)
    {
        final CompoundTag growth = root.getCompound("growth");
        growth.getList("buildings", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag building = (CompoundTag) value;
            if (!building.contains("origin", Tag.TAG_STRING))
                building.putString("origin", "legacy_procedural".equals(building.getString("structureSource"))
                    ? "LEGACY" : "GROWTH");
        });
        growth.getList("layouts", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag layout = (CompoundTag) value;
            if (!layout.contains("template", Tag.TAG_STRING)) layout.putString("template", "");
        });
        return 8;
    }
}
