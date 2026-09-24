package com.minecolonies.kingdoms.war;

import com.minecolonies.kingdoms.KingdomsMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Persisted war state (schema 15): wars, field armies, battles, and per-pair bookkeeping (hostility streaks, truces,
 * war ordinals). {@link WarService} writes wars, {@link CampaignService} armies and battles; physical soldiers are
 * never persisted. Finished records are kept for diagnostics and history within fixed bounds; an open record, and a
 * finished battle whose army is still in the field, is never pruned.
 */
public final class WarRegistry
{
    public static final int MAX_ENDED_WARS = 32;
    public static final int MAX_ENDED_ARMIES = 64;
    public static final int MAX_ENDED_BATTLES = 64;

    private final Map<UUID, WarRecord> wars = new LinkedHashMap<>();
    private final Map<UUID, ArmyRecord> armies = new LinkedHashMap<>();
    private final Map<UUID, BattleRecord> battles = new LinkedHashMap<>();
    private WarState state = new WarState();
    private long lastEvaluatedAt = -1L;

    public Collection<WarRecord> wars() { return Collections.unmodifiableCollection(wars.values()); }
    public Optional<WarRecord> war(final UUID id) { return Optional.ofNullable(wars.get(id)); }
    public Collection<ArmyRecord> armies() { return Collections.unmodifiableCollection(armies.values()); }
    public Optional<ArmyRecord> army(final UUID id) { return Optional.ofNullable(armies.get(id)); }
    public Collection<BattleRecord> battles() { return Collections.unmodifiableCollection(battles.values()); }
    public Optional<BattleRecord> battle(final UUID id) { return Optional.ofNullable(battles.get(id)); }
    public WarState state() { return state; }
    public long lastEvaluatedAt() { return lastEvaluatedAt; }

    /** The open battle at a settlement, if it is besieged right now. */
    public Optional<BattleRecord> openBattleAt(final UUID settlementId)
    {
        return battles.values().stream().filter(battle -> battle.open() && battle.settlementId().equals(settlementId)).findFirst();
    }

    void putWar(final WarRecord war) { wars.put(war.id(), war); }
    void putArmy(final ArmyRecord army) { armies.put(army.id(), army); }
    void putBattle(final BattleRecord battle) { battles.put(battle.id(), battle); }
    void setLastEvaluatedAt(final long gameTime) { lastEvaluatedAt = gameTime; }

    /** Drops the oldest finished records beyond the bounds; returns how many were dropped. */
    int prune()
    {
        int dropped = prune(wars, war -> !war.open(), WarRecord::endedAt, MAX_ENDED_WARS);
        dropped += prune(armies, army -> !army.open(), ArmyRecord::disbandedAt, MAX_ENDED_ARMIES);
        dropped += prune(battles, battle -> !battle.open() && !(armies.get(battle.armyId()) instanceof ArmyRecord army && army.open()),
            BattleRecord::resolvedAt, MAX_ENDED_BATTLES);
        return dropped;
    }

    private static <T> int prune(final Map<UUID, T> records, final java.util.function.Predicate<T> finished, final Function<T, Long> endedAt,
        final int limit)
    {
        final List<Map.Entry<UUID, T>> done = new ArrayList<>(records.entrySet().stream().filter(entry -> finished.test(entry.getValue())).toList());
        if (done.size() <= limit) return 0;
        done.sort(Comparator.comparing((Map.Entry<UUID, T> entry) -> endedAt.apply(entry.getValue())).thenComparing(Map.Entry::getKey));
        final int excess = done.size() - limit;
        for (int index = 0; index < excess; index++) records.remove(done.get(index).getKey());
        return excess;
    }

    // ------------------------------------------------------------------------------------------------ persistence

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        tag.put("wars", list(wars.values(), WarRecord::save));
        tag.put("armies", list(armies.values(), ArmyRecord::save));
        tag.put("battles", list(battles.values(), BattleRecord::save));
        tag.put("state", state.save());
        tag.putLong("lastEvaluatedAt", lastEvaluatedAt);
        return tag;
    }

    public static WarRegistry load(final CompoundTag tag)
    {
        final WarRegistry registry = new WarRegistry();
        read(tag.getList("wars", Tag.TAG_COMPOUND), WarRecord::load, war -> registry.wars.put(war.id(), war), "war");
        read(tag.getList("armies", Tag.TAG_COMPOUND), ArmyRecord::load, army -> registry.armies.put(army.id(), army), "army");
        read(tag.getList("battles", Tag.TAG_COMPOUND), BattleRecord::load, battle -> registry.battles.put(battle.id(), battle), "battle");
        registry.state = WarState.load(tag.getCompound("state"));
        registry.lastEvaluatedAt = tag.contains("lastEvaluatedAt") ? tag.getLong("lastEvaluatedAt") : -1L;
        return registry;
    }

    private static <T> ListTag list(final Collection<T> values, final Function<T, CompoundTag> save)
    {
        final ListTag list = new ListTag();
        values.forEach(value -> list.add(save.apply(value)));
        return list;
    }

    private static <T> void read(final ListTag list, final Function<CompoundTag, T> load, final java.util.function.Consumer<T> into,
        final String what)
    {
        list.forEach(value -> {
            try
            {
                into.accept(load.apply((CompoundTag) value));
            }
            catch (RuntimeException exception)
            {
                KingdomsMod.LOGGER.error("Dropping unreadable {} {}", what, value, exception);
            }
        });
    }
}
