package com.minecolonies.kingdoms.persistence;

import com.minecolonies.kingdoms.colony.NPCColonyData;
import com.minecolonies.kingdoms.caravan.CaravanLedger;
import com.minecolonies.kingdoms.faction.Faction;
import com.minecolonies.kingdoms.kingdom.Kingdom;
import com.minecolonies.kingdoms.trade.TradeLedger;
import com.minecolonies.kingdoms.world.road.RoadNetwork;
import com.minecolonies.kingdoms.world.settlement.SettlementRegistry;
import com.minecolonies.kingdoms.world.settlement.growth.GrowthRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class KingdomsSavedData extends SavedData
{
    public static final String DATA_NAME = "minecolonies_kingdoms";
    public static final int DATA_VERSION = 12;
    public static final SavedData.Factory<KingdomsSavedData> FACTORY =
        new SavedData.Factory<>(KingdomsSavedData::new, KingdomsSavedData::load);

    private final Map<UUID, Faction> factions = new LinkedHashMap<>();
    private final Map<UUID, Kingdom> kingdoms = new LinkedHashMap<>();
    private final Map<UUID, NPCColonyData> colonies = new LinkedHashMap<>();
    private TradeLedger tradeLedger = new TradeLedger();
    private CaravanLedger caravanLedger = new CaravanLedger();
    private SettlementRegistry settlements = new SettlementRegistry();
    private RoadNetwork roads = new RoadNetwork();
    private GrowthRegistry growth = new GrowthRegistry();
    private com.minecolonies.kingdoms.citizen.CitizenRegistry citizens = new com.minecolonies.kingdoms.citizen.CitizenRegistry();
    private com.minecolonies.kingdoms.contract.ContractRegistry contracts = new com.minecolonies.kingdoms.contract.ContractRegistry();
    private com.minecolonies.kingdoms.diplomacy.DiplomacyState diplomacy = new com.minecolonies.kingdoms.diplomacy.DiplomacyState();
    private com.minecolonies.kingdoms.diplomacy.ReputationRegistry reputation = new com.minecolonies.kingdoms.diplomacy.ReputationRegistry();
    private com.minecolonies.kingdoms.bandit.BanditRegistry bandits = new com.minecolonies.kingdoms.bandit.BanditRegistry();

    public static KingdomsSavedData get(final ServerLevel level)
    {
        final ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Collection<Faction> factions()
    {
        return Collections.unmodifiableCollection(factions.values());
    }

    public Collection<Kingdom> kingdoms()
    {
        return Collections.unmodifiableCollection(kingdoms.values());
    }

    public Collection<NPCColonyData> colonies()
    {
        return Collections.unmodifiableCollection(colonies.values());
    }

    public TradeLedger tradeLedger()
    {
        return tradeLedger;
    }

    public CaravanLedger caravanLedger()
    {
        return caravanLedger;
    }

    public SettlementRegistry settlements()
    {
        return settlements;
    }

    public RoadNetwork roads()
    {
        return roads;
    }

    public GrowthRegistry growth()
    {
        return growth;
    }

    /** Bounded representative rosters (schema 9); never the population itself. */
    public com.minecolonies.kingdoms.citizen.CitizenRegistry citizens()
    {
        return citizens;
    }

    /** Road threat, bandit encounters, and shipment assessments (schema 12). */
    public com.minecolonies.kingdoms.bandit.BanditRegistry bandits()
    {
        return bandits;
    }

    /** Player reputation per faction (schema 11); the only store of player standing. */
    public com.minecolonies.kingdoms.diplomacy.ReputationRegistry reputation()
    {
        return reputation;
    }

    /** Settlement contracts, generation bookkeeping, and treasury accrual times (schema 11 format). */
    public com.minecolonies.kingdoms.contract.ContractRegistry contracts()
    {
        return contracts;
    }

    /** Diplomacy evaluation time and relation audit log (schema 11); relation values live on factions. */
    public com.minecolonies.kingdoms.diplomacy.DiplomacyState diplomacy()
    {
        return diplomacy;
    }

    public Optional<Faction> faction(final UUID id)
    {
        return Optional.ofNullable(factions.get(id));
    }

    public Optional<Kingdom> kingdom(final UUID id)
    {
        return Optional.ofNullable(kingdoms.get(id));
    }

    public Optional<NPCColonyData> colony(final UUID id)
    {
        return Optional.ofNullable(colonies.get(id));
    }

    public Optional<NPCColonyData> colony(final ResourceLocation dimension, final int mineColoniesId)
    {
        return colonies.values().stream()
            .filter(colony -> colony.mineColoniesColonyId().isPresent()
                && colony.mineColoniesColonyId().getAsInt() == mineColoniesId
                && colony.dimension().equals(dimension))
            .findFirst();
    }

    public Optional<NPCColonyData> colonyByMineColoniesId(final int mineColoniesId)
    {
        return colonies.values().stream()
            .filter(colony -> colony.mineColoniesColonyId().isPresent()
                && colony.mineColoniesColonyId().getAsInt() == mineColoniesId)
            .sorted(Comparator.comparing(colony -> colony.dimension().toString()))
            .findFirst();
    }

    public void putFaction(final Faction faction)
    {
        factions.put(faction.id(), faction);
        setDirty();
    }

    public void putKingdom(final Kingdom kingdom)
    {
        kingdoms.put(kingdom.id(), kingdom);
        setDirty();
    }

    public void putColony(final NPCColonyData colony)
    {
        colonies.put(colony.id(), colony);
        setDirty();
    }

    public Optional<NPCColonyData> removeColony(final UUID id)
    {
        final NPCColonyData removed = colonies.remove(id);
        if (removed != null)
        {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }

    public Optional<Faction> removeFaction(final UUID id)
    {
        final Faction removed = factions.remove(id);
        if (removed != null)
        {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }

    public void markChanged()
    {
        setDirty();
    }

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries)
    {
        tag.putInt("dataVersion", DATA_VERSION);
        tag.put("factions", writeList(factions.values().stream().map(Faction::save).toList()));
        tag.put("kingdoms", writeList(kingdoms.values().stream().map(Kingdom::save).toList()));
        tag.put("colonies", writeList(colonies.values().stream().map(NPCColonyData::save).toList()));
        tag.put("trade", tradeLedger.save());
        tag.put("caravans", caravanLedger.save());
        tag.put("settlements", settlements.save());
        tag.put("roads", roads.save());
        tag.put("growth", growth.save());
        tag.put("citizens", citizens.save());
        tag.put("contracts", contracts.save());
        tag.put("diplomacy", diplomacy.save());
        tag.put("reputation", reputation.save());
        tag.put("bandits", bandits.save());
        return tag;
    }

    static KingdomsSavedData load(final CompoundTag tag, final HolderLookup.Provider registries)
    {
        final CompoundTag migrated = KingdomsDataMigrator.migrate(tag, DATA_VERSION);
        final KingdomsSavedData data = new KingdomsSavedData();
        migrated.getList("factions", Tag.TAG_COMPOUND).forEach(value -> {
            final Faction faction = Faction.load((CompoundTag) value);
            data.factions.put(faction.id(), faction);
        });
        migrated.getList("kingdoms", Tag.TAG_COMPOUND).forEach(value -> {
            final Kingdom kingdom = Kingdom.load((CompoundTag) value);
            data.kingdoms.put(kingdom.id(), kingdom);
        });
        migrated.getList("colonies", Tag.TAG_COMPOUND).forEach(value -> {
            final NPCColonyData colony = NPCColonyData.load((CompoundTag) value);
            data.colonies.put(colony.id(), colony);
        });
        data.tradeLedger = TradeLedger.load(migrated.getCompound("trade"));
        data.caravanLedger = CaravanLedger.load(migrated.getCompound("caravans"));
        data.settlements = SettlementRegistry.load(migrated.getCompound("settlements"));
        data.roads = RoadNetwork.load(migrated.getCompound("roads"));
        data.growth = GrowthRegistry.load(migrated.getCompound("growth"));
        data.citizens = com.minecolonies.kingdoms.citizen.CitizenRegistry.load(migrated.getCompound("citizens"));
        data.contracts = com.minecolonies.kingdoms.contract.ContractRegistry.load(migrated.getCompound("contracts"));
        data.diplomacy = com.minecolonies.kingdoms.diplomacy.DiplomacyState.load(migrated.getCompound("diplomacy"));
        data.reputation = com.minecolonies.kingdoms.diplomacy.ReputationRegistry.load(migrated.getCompound("reputation"));
        data.bandits = com.minecolonies.kingdoms.bandit.BanditRegistry.load(migrated.getCompound("bandits"));
        return data;
    }

    private static ListTag writeList(final Collection<CompoundTag> values)
    {
        final ListTag list = new ListTag();
        list.addAll(values);
        return list;
    }
}
