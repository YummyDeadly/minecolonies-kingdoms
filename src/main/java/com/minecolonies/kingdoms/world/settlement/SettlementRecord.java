package com.minecolonies.kingdoms.world.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import com.minecolonies.kingdoms.world.settlement.site.SettlementSiteAnalysis;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

public final class SettlementRecord
{
    private final UUID id;
    private final String name;
    private final SettlementType type;
    private final ResourceLocation dimension;
    private final BlockPos anchor;
    private final int orientation;
    private final BlockPos gate;
    private final UUID factionId;
    private final int initialPopulation;
    private SettlementPhysicalState physicalState;
    private final SettlementRegion creationRegion;
    private final Integer mineColoniesId;
    private final long createdAt;
    private final SettlementSiteAnalysis siteAnalysis;
    private final Set<Long> generatedChunks = new LinkedHashSet<>();

    public SettlementRecord(final UUID id, final String name, final SettlementType type,
        final ResourceLocation dimension, final BlockPos anchor, final int orientation, final BlockPos gate,
        final UUID factionId, final int initialPopulation, final SettlementPhysicalState physicalState,
        final SettlementRegion creationRegion, final Integer mineColoniesId, final long createdAt)
    {
        this(id, name, type, dimension, anchor, orientation, gate, factionId, initialPopulation, physicalState,
            creationRegion, mineColoniesId, createdAt, null);
    }

    public SettlementRecord(final UUID id, final String name, final SettlementType type,
        final ResourceLocation dimension, final BlockPos anchor, final int orientation, final BlockPos gate,
        final UUID factionId, final int initialPopulation, final SettlementPhysicalState physicalState,
        final SettlementRegion creationRegion, final Integer mineColoniesId, final long createdAt,
        final SettlementSiteAnalysis siteAnalysis)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.name = requireName(name);
        this.type = Objects.requireNonNull(type, "type");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.anchor = Objects.requireNonNull(anchor, "anchor").immutable();
        if (orientation % 90 != 0) throw new IllegalArgumentException("Orientation must be a multiple of 90");
        this.orientation = Math.floorMod(orientation, 360);
        this.gate = Objects.requireNonNull(gate, "gate").immutable();
        this.factionId = Objects.requireNonNull(factionId, "factionId");
        if (initialPopulation < 0) throw new IllegalArgumentException("Population must be non-negative");
        this.initialPopulation = initialPopulation;
        this.physicalState = Objects.requireNonNull(physicalState, "physicalState");
        this.creationRegion = Objects.requireNonNull(creationRegion, "creationRegion");
        this.mineColoniesId = mineColoniesId;
        this.createdAt = Math.max(0L, createdAt);
        this.siteAnalysis = siteAnalysis;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public SettlementType type() { return type; }
    public ResourceLocation dimension() { return dimension; }
    public BlockPos anchor() { return anchor; }
    public int orientation() { return orientation; }
    public BlockPos gate() { return gate; }
    public UUID factionId() { return factionId; }
    public int initialPopulation() { return initialPopulation; }
    public SettlementPhysicalState physicalState() { return physicalState; }
    public SettlementRegion creationRegion() { return creationRegion; }
    public OptionalInt mineColoniesId() { return mineColoniesId == null ? OptionalInt.empty() : OptionalInt.of(mineColoniesId); }
    public long createdAt() { return createdAt; }
    public java.util.Optional<SettlementSiteAnalysis> siteAnalysis() { return java.util.Optional.ofNullable(siteAnalysis); }
    public Set<Long> generatedChunks() { return Set.copyOf(generatedChunks); }
    public void physicalState(final SettlementPhysicalState state) { physicalState = Objects.requireNonNull(state); }
    public boolean markChunkGenerated(final long chunk) { return generatedChunks.add(chunk); }
    public boolean isChunkGenerated(final long chunk) { return generatedChunks.contains(chunk); }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("name", name);
        tag.putString("type", type.name());
        tag.putString("dimension", dimension.toString());
        tag.putLong("anchor", anchor.asLong());
        tag.putInt("orientation", orientation);
        tag.putLong("gate", gate.asLong());
        tag.putUUID("faction", factionId);
        tag.putInt("population", initialPopulation);
        tag.putString("physicalState", physicalState.name());
        tag.putInt("regionX", creationRegion.x());
        tag.putInt("regionZ", creationRegion.z());
        if (mineColoniesId != null) tag.putInt("mineColoniesId", mineColoniesId);
        tag.putLong("createdAt", createdAt);
        if (siteAnalysis != null) tag.put("siteAnalysis", siteAnalysis.save());
        final ListTag chunks = new ListTag();
        generatedChunks.forEach(value -> { final CompoundTag entry = new CompoundTag(); entry.putLong("value", value); chunks.add(entry); });
        tag.put("generatedChunks", chunks);
        return tag;
    }

    public static SettlementRecord load(final CompoundTag tag)
    {
        final ResourceLocation dimension = ResourceLocation.parse(tag.getString("dimension"));
        final SettlementRecord record = new SettlementRecord(tag.getUUID("id"), tag.getString("name"),
            SettlementType.valueOf(tag.getString("type")), dimension, BlockPos.of(tag.getLong("anchor")),
            tag.getInt("orientation"), BlockPos.of(tag.getLong("gate")), tag.getUUID("faction"),
            tag.getInt("population"), SettlementPhysicalState.valueOf(tag.getString("physicalState")),
            new SettlementRegion(dimension, tag.getInt("regionX"), tag.getInt("regionZ")),
            tag.contains("mineColoniesId", Tag.TAG_INT) ? tag.getInt("mineColoniesId") : null, tag.getLong("createdAt"),
            tag.contains("siteAnalysis", Tag.TAG_COMPOUND)
                ? SettlementSiteAnalysis.load(tag.getCompound("siteAnalysis")) : null);
        tag.getList("generatedChunks", Tag.TAG_COMPOUND).forEach(value -> record.generatedChunks.add(((CompoundTag) value).getLong("value")));
        return record;
    }

    private static String requireName(final String value)
    {
        final String checked = Objects.requireNonNull(value, "name").trim();
        if (checked.isEmpty()) throw new IllegalArgumentException("Name must not be blank");
        return checked;
    }
}
