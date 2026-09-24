package com.minecolonies.kingdoms.colony;

import com.minecolonies.kingdoms.colony.decision.StrategicDecision;
import com.minecolonies.kingdoms.colony.need.ColonyNeed;
import com.minecolonies.kingdoms.economy.ColonyEconomyState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;

public final class NPCColonyData
{
    private final UUID id;
    private final Integer mineColoniesColonyId;
    private final ResourceLocation dimension;
    private final long createdAt;
    private BlockPos center = BlockPos.ZERO;
    private String name;
    private UUID factionId;
    private UUID kingdomId;
    private SimulationMode simulationMode = SimulationMode.ABSTRACT;
    private boolean playerManaged;
    private ColonyKind kind;
    private int population;
    private int workers;
    private int soldiers;
    private int housingCapacity;
    private int storageCapacity;
    private long lastSimulationGameTime;
    private long lastStrategicUpdate;
    private long lastEconomyUpdate;
    private ColonyEconomyState economy = new ColonyEconomyState();
    private List<ColonyNeed> needs = List.of();
    private StrategicDecision lastDecision = StrategicDecision.none(0L);

    public NPCColonyData(
        final UUID id,
        final int mineColoniesColonyId,
        final ResourceLocation dimension,
        final String name,
        final UUID factionId)
    {
        this(id, mineColoniesColonyId, dimension, BlockPos.ZERO, name, factionId, 0L);
    }

    public NPCColonyData(
        final UUID id,
        final int mineColoniesColonyId,
        final ResourceLocation dimension,
        final BlockPos center,
        final String name,
        final UUID factionId)
    {
        this(id, mineColoniesColonyId, dimension, center, name, factionId, 0L);
    }

    public NPCColonyData(
        final UUID id,
        final int mineColoniesColonyId,
        final ResourceLocation dimension,
        final BlockPos center,
        final String name,
        final UUID factionId,
        final long createdAt)
    {
        if (mineColoniesColonyId <= 0)
        {
            throw new IllegalArgumentException("MineColonies colony id must be positive");
        }
        this.id = Objects.requireNonNull(id, "id");
        this.mineColoniesColonyId = mineColoniesColonyId;
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.center = Objects.requireNonNull(center, "center").immutable();
        this.name = requireName(name);
        this.factionId = Objects.requireNonNull(factionId, "factionId");
        this.createdAt = requireGameTime(createdAt);
        this.kind = ColonyKind.PLAYER_PHYSICAL;
    }

    private NPCColonyData(
        final UUID id,
        final Integer mineColoniesColonyId,
        final ResourceLocation dimension,
        final BlockPos center,
        final String name,
        final UUID factionId,
        final long createdAt,
        final ColonyKind kind)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.mineColoniesColonyId = mineColoniesColonyId;
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.center = Objects.requireNonNull(center, "center").immutable();
        this.name = requireName(name);
        this.factionId = Objects.requireNonNull(factionId, "factionId");
        this.createdAt = requireGameTime(createdAt);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.playerManaged = kind == ColonyKind.PLAYER_PHYSICAL;
    }

    public static NPCColonyData createStrategicNpc(
        final UUID id,
        final ResourceLocation dimension,
        final BlockPos center,
        final String name,
        final UUID factionId,
        final long createdAt)
    {
        return new NPCColonyData(
            id,
            null,
            dimension,
            center,
            name,
            factionId,
            createdAt,
            ColonyKind.NPC_ABSTRACT);
    }

    public UUID id()
    {
        return id;
    }

    public OptionalInt mineColoniesColonyId()
    {
        return mineColoniesColonyId == null ? OptionalInt.empty() : OptionalInt.of(mineColoniesColonyId);
    }

    public ColonyKind kind()
    {
        return kind;
    }

    public void setKind(final ColonyKind kind)
    {
        final ColonyKind checked = Objects.requireNonNull(kind, "kind");
        if (checked.hasPhysicalMineColoniesColony() != (mineColoniesColonyId != null))
        {
            throw new IllegalArgumentException("Colony kind must match its MineColonies association");
        }
        this.kind = checked;
        this.playerManaged = checked == ColonyKind.PLAYER_PHYSICAL;
    }

    public long createdAt()
    {
        return createdAt;
    }

    public ResourceLocation dimension()
    {
        return dimension;
    }

    public BlockPos center()
    {
        return center;
    }

    public void setCenter(final BlockPos center)
    {
        this.center = Objects.requireNonNull(center, "center").immutable();
    }

    public String name()
    {
        return name;
    }

    public void rename(final String name)
    {
        this.name = requireName(name);
    }

    public UUID factionId()
    {
        return factionId;
    }

    public void setFactionId(final UUID factionId)
    {
        this.factionId = Objects.requireNonNull(factionId, "factionId");
    }

    public UUID kingdomId()
    {
        return kingdomId;
    }

    public void setKingdomId(final UUID kingdomId)
    {
        this.kingdomId = kingdomId;
    }

    public SimulationMode simulationMode()
    {
        return simulationMode;
    }

    public void setSimulationMode(final SimulationMode simulationMode)
    {
        this.simulationMode = Objects.requireNonNull(simulationMode, "simulationMode");
    }

    public boolean playerManaged()
    {
        return playerManaged;
    }

    public void setPlayerManaged(final boolean playerManaged)
    {
        this.playerManaged = playerManaged;
        if (mineColoniesColonyId != null)
        {
            this.kind = playerManaged ? ColonyKind.PLAYER_PHYSICAL : ColonyKind.NPC_PHYSICAL;
        }
    }

    public int population()
    {
        return population;
    }

    public int workers()
    {
        return workers;
    }

    public int soldiers()
    {
        return soldiers;
    }

    public void updatePopulation(final int population, final int workers, final int soldiers)
    {
        if (population < 0 || workers < 0 || soldiers < 0 || workers + soldiers > population)
        {
            throw new IllegalArgumentException("Invalid population aggregate");
        }
        this.population = population;
        this.workers = workers;
        this.soldiers = soldiers;
    }

    public int housingCapacity()
    {
        return housingCapacity;
    }

    public int storageCapacity()
    {
        return storageCapacity;
    }

    public void updateCapacities(final int housingCapacity, final int storageCapacity)
    {
        if (housingCapacity < 0 || storageCapacity < 0)
        {
            throw new IllegalArgumentException("Capacities must be non-negative");
        }
        this.housingCapacity = housingCapacity;
        this.storageCapacity = storageCapacity;
    }

    public long lastSimulationGameTime()
    {
        return lastSimulationGameTime;
    }

    public void setLastSimulationGameTime(final long gameTime)
    {
        if (gameTime < 0L)
        {
            throw new IllegalArgumentException("Game time must be non-negative");
        }
        this.lastSimulationGameTime = gameTime;
    }

    public long lastStrategicUpdate()
    {
        return lastStrategicUpdate;
    }

    public void setLastStrategicUpdate(final long gameTime)
    {
        this.lastStrategicUpdate = requireGameTime(gameTime);
    }

    public long lastEconomyUpdate()
    {
        return lastEconomyUpdate;
    }

    public void setLastEconomyUpdate(final long gameTime)
    {
        this.lastEconomyUpdate = requireGameTime(gameTime);
    }

    public ColonyEconomyState economy()
    {
        return economy;
    }

    public List<ColonyNeed> needs()
    {
        return Collections.unmodifiableList(needs);
    }

    public void replaceNeeds(final List<ColonyNeed> needs)
    {
        this.needs = List.copyOf(Objects.requireNonNull(needs, "needs"));
    }

    public StrategicDecision lastDecision()
    {
        return lastDecision;
    }

    public void setLastDecision(final StrategicDecision decision)
    {
        this.lastDecision = Objects.requireNonNull(decision, "decision");
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        if (mineColoniesColonyId != null)
        {
            tag.putInt("mineColoniesId", mineColoniesColonyId);
        }
        tag.putString("dimension", dimension.toString());
        tag.putLong("createdAt", createdAt);
        tag.putString("kind", kind.name());
        tag.putLong("center", center.asLong());
        tag.putString("name", name);
        tag.putUUID("faction", factionId);
        if (kingdomId != null)
        {
            tag.putUUID("kingdom", kingdomId);
        }
        tag.putString("simulationMode", simulationMode.name());
        tag.putBoolean("playerManaged", playerManaged);
        tag.putInt("population", population);
        tag.putInt("workers", workers);
        tag.putInt("soldiers", soldiers);
        tag.putInt("housingCapacity", housingCapacity);
        tag.putInt("storageCapacity", storageCapacity);
        tag.putLong("lastSimulationGameTime", lastSimulationGameTime);
        tag.putLong("lastStrategicUpdate", lastStrategicUpdate);
        tag.putLong("lastEconomyUpdate", lastEconomyUpdate);
        tag.put("economy", economy.save());
        final ListTag needTags = new ListTag();
        needs.forEach(need -> needTags.add(need.save()));
        tag.put("needs", needTags);
        tag.put("lastDecision", lastDecision.save());
        return tag;
    }

    public static NPCColonyData load(final CompoundTag tag)
    {
        final ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        if (dimension == null)
        {
            throw new IllegalArgumentException("Invalid colony dimension: " + tag.getString("dimension"));
        }
        final String kindName = tag.getString("kind");
        final ColonyKind kind = kindName.isEmpty()
            ? (tag.getBoolean("playerManaged") ? ColonyKind.PLAYER_PHYSICAL : ColonyKind.NPC_PHYSICAL)
            : ColonyKind.valueOf(kindName);
        final Integer mineColoniesId = tag.contains("mineColoniesId", Tag.TAG_INT)
            ? tag.getInt("mineColoniesId")
            : null;
        final NPCColonyData data = new NPCColonyData(
            tag.getUUID("id"),
            mineColoniesId,
            dimension,
            tag.contains("center") ? BlockPos.of(tag.getLong("center")) : BlockPos.ZERO,
            tag.getString("name"),
            tag.getUUID("faction"),
            Math.max(0L, tag.getLong("createdAt")),
            kind);
        data.kingdomId = tag.hasUUID("kingdom") ? tag.getUUID("kingdom") : null;
        final String mode = tag.getString("simulationMode");
        data.simulationMode = mode.isEmpty() ? SimulationMode.ABSTRACT : SimulationMode.valueOf(mode);
        data.playerManaged = kind == ColonyKind.PLAYER_PHYSICAL;
        data.updatePopulation(tag.getInt("population"), tag.getInt("workers"), tag.getInt("soldiers"));
        data.updateCapacities(tag.getInt("housingCapacity"), tag.getInt("storageCapacity"));
        data.setLastSimulationGameTime(tag.getLong("lastSimulationGameTime"));
        data.setLastStrategicUpdate(tag.getLong("lastStrategicUpdate"));
        data.setLastEconomyUpdate(tag.getLong("lastEconomyUpdate"));
        if (tag.contains("economy", Tag.TAG_COMPOUND))
        {
            data.economy = ColonyEconomyState.load(tag.getCompound("economy"));
        }
        final List<ColonyNeed> loadedNeeds = new ArrayList<>();
        tag.getList("needs", Tag.TAG_COMPOUND).forEach(value -> loadedNeeds.add(ColonyNeed.load((CompoundTag) value)));
        data.needs = List.copyOf(loadedNeeds);
        if (tag.contains("lastDecision", Tag.TAG_COMPOUND))
        {
            data.lastDecision = StrategicDecision.load(tag.getCompound("lastDecision"));
        }
        return data;
    }

    private static long requireGameTime(final long gameTime)
    {
        if (gameTime < 0L)
        {
            throw new IllegalArgumentException("Game time must be non-negative");
        }
        return gameTime;
    }

    private static String requireName(final String value)
    {
        final String trimmed = Objects.requireNonNull(value, "name").trim();
        if (trimmed.isEmpty())
        {
            throw new IllegalArgumentException("Name must not be blank");
        }
        return trimmed;
    }
}
