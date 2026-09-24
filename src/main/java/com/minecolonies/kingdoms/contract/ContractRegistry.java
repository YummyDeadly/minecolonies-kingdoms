package com.minecolonies.kingdoms.contract;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted contracts (schema 11) plus generation bookkeeping: the per-settlement sequence (stable contract IDs) and
 * next generation time, the per-settlement-and-resource cooldown after a contract closes, and the per-faction
 * treasury accrual time. Open contracts are bounded per settlement and per player; closed ones by retention and count.
 */
public final class ContractRegistry
{
    /**
     * Layout version of this compound: 2 = schema 11 lifecycle, objective snapshot, reservation at acceptance;
     * 3 = objective kinds and targets (Phase 8). Format 2 records read as DELIVERY contracts.
     */
    public static final int FORMAT = 3;

    private final Map<UUID, Contract> contracts = new LinkedHashMap<>();
    private final Map<UUID, Integer> offerSequence = new HashMap<>();
    private final Map<UUID, Long> nextOfferAt = new HashMap<>();
    private final Map<UUID, Long> treasuryAccruedAt = new HashMap<>();
    private final Map<String, Long> resourceCooldownUntil = new HashMap<>();

    public Collection<Contract> contracts() { return Collections.unmodifiableCollection(contracts.values()); }
    public Optional<Contract> get(final UUID id) { return Optional.ofNullable(contracts.get(id)); }
    public void put(final Contract contract) { contracts.put(contract.id(), contract); }

    public List<Contract> offers(final UUID settlementId)
    {
        return contracts.values().stream().filter(value -> value.settlementId().equals(settlementId)
            && value.status() == ContractStatus.OFFERED).toList();
    }

    /** OFFERED or ACCEPTED contracts of one settlement. */
    public List<Contract> open(final UUID settlementId)
    {
        return contracts.values().stream().filter(value -> value.settlementId().equals(settlementId)
            && value.status().open()).toList();
    }

    /** Open contracts (any settlement) whose objective targets a bandit encounter. */
    public List<Contract> targeting(final UUID encounterId)
    {
        return contracts.values().stream().filter(value -> value.status().open()
            && encounterId.equals(value.objective().targetEncounter())).toList();
    }

    /** Contracts a player has accepted and not yet finished. */
    public List<Contract> active(final UUID playerId)
    {
        return contracts.values().stream().filter(value -> value.status() == ContractStatus.ACCEPTED
            && playerId.equals(value.holder())).toList();
    }

    /** Completed contracts whose reward could not be issued yet (paid on the player's next contract action). */
    public List<Contract> pendingRewards(final UUID playerId)
    {
        return contracts.values().stream().filter(value -> value.rewardPending() && playerId.equals(value.holder())).toList();
    }

    public long resourceCooldownUntil(final UUID settlementId, final com.minecolonies.kingdoms.economy.EconomicResource resource)
    {
        return resourceCooldownUntil.getOrDefault(cooldownKey(settlementId, resource), Long.MIN_VALUE);
    }

    void setResourceCooldown(final UUID settlementId, final com.minecolonies.kingdoms.economy.EconomicResource resource, final long until)
    {
        resourceCooldownUntil.merge(cooldownKey(settlementId, resource), until, Math::max);
    }

    private static String cooldownKey(final UUID settlementId, final com.minecolonies.kingdoms.economy.EconomicResource resource)
    {
        return settlementId + ":" + resource.name();
    }

    public List<Contract> all(final ContractStatus status)
    {
        return contracts.values().stream().filter(value -> value.status() == status).toList();
    }

    int nextSequence(final UUID settlementId)
    {
        return offerSequence.merge(settlementId, 1, Integer::sum);
    }

    public long nextOfferAt(final UUID settlementId) { return nextOfferAt.getOrDefault(settlementId, Long.MIN_VALUE); }
    void setNextOfferAt(final UUID settlementId, final long gameTime) { nextOfferAt.put(settlementId, gameTime); }

    public Optional<Long> treasuryAccruedAt(final UUID factionId) { return Optional.ofNullable(treasuryAccruedAt.get(factionId)); }
    void setTreasuryAccruedAt(final UUID factionId, final long gameTime) { treasuryAccruedAt.put(factionId, gameTime); }

    /** Resolves a full UUID or a unique prefix (at least 6 hex characters) of an open contract. */
    public Optional<Contract> resolve(final String text)
    {
        try { return get(UUID.fromString(text)); }
        catch (IllegalArgumentException ignored) { /* try a prefix */ }
        final String prefix = text.toLowerCase(java.util.Locale.ROOT);
        if (prefix.length() < 6 || !prefix.matches("[0-9a-f-]+")) return Optional.empty();
        final List<Contract> matches = contracts.values().stream()
            .filter(value -> !value.status().terminal() && value.id().toString().startsWith(prefix)).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /**
     * Drops closed contracts older than the retention and keeps at most {@code maxHistory} closed ones, plus
     * bookkeeping for settlements/factions that no longer exist. Returns the number of removed contracts.
     */
    public int prune(final long gameTime, final long retention, final int maxHistory, final Set<UUID> settlements, final Set<UUID> factions)
    {
        final int before = contracts.size();
        resourceCooldownUntil.values().removeIf(until -> until <= gameTime);
        contracts.values().removeIf(value -> value.status().terminal() && !value.rewardPending() && gameTime - value.closedAt() > retention);
        final List<Contract> closed = new ArrayList<>(contracts.values().stream()
            .filter(value -> value.status().terminal() && !value.rewardPending()).toList());
        if (closed.size() > maxHistory)
        {
            closed.sort(Comparator.comparingLong(Contract::closedAt));
            closed.subList(0, closed.size() - maxHistory).forEach(value -> contracts.remove(value.id()));
        }
        offerSequence.keySet().retainAll(settlements);
        nextOfferAt.keySet().retainAll(settlements);
        treasuryAccruedAt.keySet().retainAll(factions);
        return before - contracts.size();
    }

    public CompoundTag save()
    {
        final CompoundTag tag = new CompoundTag();
        final ListTag list = new ListTag();
        contracts.values().forEach(value -> list.add(value.save()));
        tag.put("contracts", list);
        tag.put("sequence", writeMap(offerSequence, (entry, value) -> entry.putInt("value", value)));
        tag.put("nextOffer", writeMap(nextOfferAt, (entry, value) -> entry.putLong("value", value)));
        tag.put("treasury", writeMap(treasuryAccruedAt, (entry, value) -> entry.putLong("value", value)));
        final ListTag cooldowns = new ListTag();
        resourceCooldownUntil.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
            final CompoundTag entry = new CompoundTag();
            entry.putString("key", value.getKey());
            entry.putLong("until", value.getValue());
            cooldowns.add(entry);
        });
        tag.put("cooldowns", cooldowns);
        tag.putInt("format", FORMAT);
        return tag;
    }

    public static ContractRegistry load(final CompoundTag tag)
    {
        final ContractRegistry registry = new ContractRegistry();
        tag.getList("contracts", Tag.TAG_COMPOUND).forEach(value -> {
            try
            {
                final Contract contract = Contract.load((CompoundTag) value);
                registry.contracts.put(contract.id(), contract);
            }
            catch (RuntimeException exception)
            {
                com.minecolonies.kingdoms.KingdomsMod.LOGGER.error("Dropping unreadable contract record {}", value, exception);
            }
        });
        tag.getList("sequence", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            registry.offerSequence.put(entry.getUUID("id"), entry.getInt("value"));
        });
        tag.getList("nextOffer", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            registry.nextOfferAt.put(entry.getUUID("id"), entry.getLong("value"));
        });
        tag.getList("treasury", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            registry.treasuryAccruedAt.put(entry.getUUID("id"), entry.getLong("value"));
        });
        tag.getList("cooldowns", Tag.TAG_COMPOUND).forEach(value -> {
            final CompoundTag entry = (CompoundTag) value;
            registry.resourceCooldownUntil.put(entry.getString("key"), entry.getLong("until"));
        });
        return registry;
    }

    private static <T> ListTag writeMap(final Map<UUID, T> map, final java.util.function.BiConsumer<CompoundTag, T> writer)
    {
        final ListTag list = new ListTag();
        map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(value -> {
            final CompoundTag entry = new CompoundTag();
            entry.putUUID("id", value.getKey());
            writer.accept(entry, value.getValue());
            list.add(entry);
        });
        return list;
    }
}
