package com.minecolonies.kingdoms.bandit;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One bandit camp near a dangerous road: the authoritative strategic record (Phase 8.1). Its fight is a
 * {@link BanditEncounter} of kind {@code CAMP}; physical bandits and the small physical camp only represent it.
 *
 * <pre>
 * ACTIVE --its bandits are defeated--> CLEARED
 *    +----lifetime over / road gone / operator--> DISBANDED
 * </pre>
 *
 * Terminal statuses never change. State changes are package-private and made only by {@link CampService}. The
 * structure state is separate from the status: an ended camp keeps its placed-block list until the blocks are gone.
 */
public final class BanditCamp
{
    public enum Status
    {
        ACTIVE, CLEARED, DISBANDED;

        public boolean terminal() { return this != ACTIVE; }
    }

    /** NONE: not placed (yet); BUILT: placed and recorded; REMOVED: taken down; UNBUILDABLE: no valid site (fight only). */
    public enum Structure { NONE, BUILT, REMOVED, UNBUILDABLE }

    /** A block the camp placed; it is removed later only if the world still holds exactly this block there. */
    public record PlacedBlock(BlockPos position, String blockId)
    {
        public PlacedBlock
        {
            position = Objects.requireNonNull(position, "position").immutable();
            Objects.requireNonNull(blockId, "blockId");
        }
    }

    public static final int MAX_PLACED = 64;

    private final UUID id;
    private final UUID roadId;
    private final int ordinal;
    private final ResourceLocation dimension;
    private final List<BlockPos> sites;
    private final long seed;
    private final int strength;
    private final long createdAt;
    private final long expiresAt;
    private final UUID encounterId;
    private int siteIndex;
    private Status status = Status.ACTIVE;
    private long lastRecruitAt;
    private long endedAt = -1L;
    private BanditEncounter.Cause endCause;
    private Structure structure = Structure.NONE;
    private String structureNote = "";
    private long structureChangedAt = -1L;
    private final List<PlacedBlock> placed = new ArrayList<>();

    BanditCamp(final UUID id, final UUID roadId, final int ordinal, final ResourceLocation dimension, final List<BlockPos> sites,
        final int strength, final long createdAt, final long expiresAt, final UUID encounterId)
    {
        this.id = Objects.requireNonNull(id, "id");
        this.roadId = Objects.requireNonNull(roadId, "roadId");
        this.ordinal = ordinal;
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (sites.isEmpty() || sites.size() > CampPlanner.MAX_SITES) throw new IllegalArgumentException("A camp needs 1-" + CampPlanner.MAX_SITES + " sites");
        this.sites = sites.stream().map(BlockPos::immutable).toList();
        if (strength < 1) throw new IllegalArgumentException("Camp strength must be positive");
        this.seed = EncounterRules.seed(id);
        this.strength = strength;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.encounterId = Objects.requireNonNull(encounterId, "encounterId");
        this.lastRecruitAt = createdAt;
    }

    public UUID id() { return id; }
    public UUID roadId() { return roadId; }
    public int ordinal() { return ordinal; }
    public ResourceLocation dimension() { return dimension; }
    public List<BlockPos> sites() { return sites; }
    public int siteIndex() { return siteIndex; }
    public BlockPos position() { return sites.get(siteIndex); }
    public long seed() { return seed; }
    public int strength() { return strength; }
    public long createdAt() { return createdAt; }
    public long expiresAt() { return expiresAt; }
    public UUID encounterId() { return encounterId; }
    public Status status() { return status; }
    public boolean active() { return status == Status.ACTIVE; }
    public long lastRecruitAt() { return lastRecruitAt; }
    public long endedAt() { return endedAt; }
    public BanditEncounter.Cause endCause() { return endCause; }
    public Structure structure() { return structure; }
    public String structureNote() { return structureNote; }
    public long structureChangedAt() { return structureChangedAt; }
    public List<PlacedBlock> placed() { return List.copyOf(placed); }

    // ------------------------------------------------------------------------------------------------ transitions

    void end(final Status terminal, final BanditEncounter.Cause cause, final long gameTime)
    {
        if (!terminal.terminal()) throw new IllegalArgumentException("Not a terminal status: " + terminal);
        if (status.terminal()) throw new IllegalStateException("Camp " + id + " is already " + status);
        status = terminal;
        endCause = Objects.requireNonNull(cause, "cause");
        endedAt = gameTime;
    }

    void recruited(final long gameTime) { lastRecruitAt = gameTime; }

    /** Moves the camp to another of its fixed candidate sites (only before anything was placed). */
    void useSite(final int index)
    {
        if (index < 0 || index >= sites.size()) throw new IllegalArgumentException("No site " + index);
        if (structure == Structure.BUILT) throw new IllegalStateException("Camp " + id + " is already built");
        siteIndex = index;
    }

    void built(final List<PlacedBlock> blocks, final long gameTime)
    {
        if (structure == Structure.BUILT) throw new IllegalStateException("Camp " + id + " is already built");
        if (blocks.size() > MAX_PLACED) throw new IllegalArgumentException("Too many camp blocks");
        placed.clear();
        placed.addAll(blocks);
        structure = Structure.BUILT;
        structureNote = "";
        structureChangedAt = gameTime;
    }

    void unbuildable(final String reason, final long gameTime)
    {
        if (structure == Structure.BUILT) throw new IllegalStateException("Camp " + id + " is already built");
        structure = Structure.UNBUILDABLE;
        structureNote = Objects.requireNonNullElse(reason, "");
        structureChangedAt = gameTime;
    }

    void removed(final long gameTime)
    {
        placed.clear();
        structure = Structure.REMOVED;
        structureChangedAt = gameTime;
    }

    // ------------------------------------------------------------------------------------------------ persistence

    CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("road", roadId);
        tag.putInt("ordinal", ordinal);
        tag.putString("dimension", dimension.toString());
        tag.putLongArray("sites", sites.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putInt("siteIndex", siteIndex);
        tag.putInt("strength", strength);
        tag.putLong("createdAt", createdAt);
        tag.putLong("expiresAt", expiresAt);
        tag.putUUID("encounter", encounterId);
        tag.putString("status", status.name());
        tag.putLong("lastRecruitAt", lastRecruitAt);
        tag.putLong("endedAt", endedAt);
        if (endCause != null) tag.putString("endCause", endCause.name());
        tag.putString("structure", structure.name());
        tag.putString("structureNote", structureNote);
        tag.putLong("structureChangedAt", structureChangedAt);
        final ListTag blocks = new ListTag();
        placed.forEach(block -> {
            final CompoundTag entry = new CompoundTag();
            entry.putLong("pos", block.position().asLong());
            entry.putString("block", block.blockId());
            blocks.add(entry);
        });
        tag.put("placed", blocks);
        return tag;
    }

    static BanditCamp load(final CompoundTag tag)
    {
        final List<BlockPos> sites = new ArrayList<>();
        for (final long value : tag.getLongArray("sites")) sites.add(BlockPos.of(value));
        final BanditCamp camp = new BanditCamp(tag.getUUID("id"), tag.getUUID("road"), tag.getInt("ordinal"),
            ResourceLocation.parse(tag.getString("dimension")), sites, Math.max(1, tag.getInt("strength")), tag.getLong("createdAt"),
            tag.getLong("expiresAt"), tag.getUUID("encounter"));
        camp.siteIndex = Math.max(0, Math.min(sites.size() - 1, tag.getInt("siteIndex")));
        camp.status = Status.valueOf(tag.getString("status"));
        camp.lastRecruitAt = tag.getLong("lastRecruitAt");
        camp.endedAt = tag.getLong("endedAt");
        camp.endCause = tag.contains("endCause") ? BanditEncounter.Cause.valueOf(tag.getString("endCause")) : null;
        camp.structure = tag.contains("structure") ? Structure.valueOf(tag.getString("structure")) : Structure.NONE;
        camp.structureNote = tag.getString("structureNote");
        camp.structureChangedAt = tag.getLong("structureChangedAt");
        final ListTag blocks = tag.getList("placed", Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size() && index < MAX_PLACED; index++)
        {
            final CompoundTag entry = blocks.getCompound(index);
            camp.placed.add(new PlacedBlock(BlockPos.of(entry.getLong("pos")), entry.getString("block")));
        }
        if (camp.status.terminal() && camp.endCause == null) throw new IllegalArgumentException("Ended camp " + camp.id + " without cause");
        return camp;
    }
}
