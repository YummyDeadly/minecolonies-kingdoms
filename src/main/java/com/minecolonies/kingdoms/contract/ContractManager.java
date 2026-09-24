package com.minecolonies.kingdoms.contract;

import com.minecolonies.kingdoms.KingdomsMod;
import com.minecolonies.kingdoms.citizen.interaction.SettlementCitizenInteractionService;
import com.minecolonies.kingdoms.config.KingdomsConfig;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.integration.minecolonies.economy.MinecraftItemResourceValueProvider;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.settlement.SettlementRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side contract operations for real players: proximity, the player inventory port, messages, pending-reward
 * claims, and the periodic sweep. Every state change goes through {@link ContractService}; this class and the
 * dialogue handler are presentation and I/O only.
 */
public final class ContractManager
{
    private static final ContractManager INSTANCE = new ContractManager();
    private static final int SWEEP_INTERVAL_TICKS = 200;
    private static final MinecraftItemResourceValueProvider VALUES = new MinecraftItemResourceValueProvider();

    private MinecraftServer server;
    private long accepted;
    private long completed;
    private long failed;
    private long abandoned;
    private long delivered;
    private long rolledBack;

    private ContractManager() {}
    public static ContractManager getInstance() { return INSTANCE; }

    public void initialize(final MinecraftServer value)
    {
        server = value;
        accepted = completed = failed = abandoned = delivered = rolledBack = 0L;
        SettlementCitizenInteractionService.getInstance().register(new ContractInteractionHandler());
    }

    public void shutdown() { server = null; }

    public void tick(final MinecraftServer value)
    {
        if (server == null || server != value) return;
        final long gameTime = value.overworld().getGameTime();
        if (gameTime % SWEEP_INTERVAL_TICKS != 0) return;
        try
        {
            final KingdomsSavedData data = KingdomsSavedData.get(value.overworld());
            for (final ContractService.Closure closure : ContractService.sweep(data, gameTime, settings()))
            {
                final Contract contract = closure.contract();
                if (contract.status() == ContractStatus.FAILED) failed++;
                final ServerPlayer holder = contract.holder() == null ? null : value.getPlayerList().getPlayer(contract.holder());
                if (holder == null) continue;
                holder.sendSystemMessage(Component.literal(contract.status() == ContractStatus.FAILED
                    ? "Your contract with " + settlementName(data, contract) + " has expired."
                    : "Your contract with " + settlementName(data, contract) + " was cancelled: the settlement no longer posts contracts.")
                    .withStyle(ChatFormatting.RED));
                if (!closure.reputation().isEmpty()) holder.sendSystemMessage(ContractText.reputation(data, closure.reputation()));
            }
        }
        catch (RuntimeException exception)
        {
            KingdomsMod.LOGGER.error("Contract sweep failed", exception);
        }
    }

    public static ContractSettings settings()
    {
        final var config = KingdomsConfig.SERVER;
        return new ContractSettings(config.contractsEnabled.get(), config.contractsMaxOpenPerSettlement.get(),
            config.contractsMaxActivePerPlayer.get(), config.contractsOfferLifetimeTicks.get(), config.contractsDurationTicks.get(),
            config.contractsOfferRefreshTicks.get(), config.contractsResourceCooldownTicks.get(),
            config.contractsUnacceptedCooldownTicks.get(), config.contractsInteractionRadius.get(), config.reputationFailPenalty.get(),
            config.reputationAbandonPenalty.get(), config.reputationKillPenalty.get(), config.contractsHistoryRetentionTicks.get(),
            config.contractsMaxHistory.get());
    }

    public static long gameTime(final ServerPlayer player)
    {
        return player.getServer().overworld().getGameTime();
    }

    // ------------------------------------------------------------------------------------------------ proximity

    public static boolean near(final ServerPlayer player, final SettlementRecord settlement, final ContractSettings settings)
    {
        if (!player.level().dimension().location().equals(settlement.dimension())) return false;
        final double dx = player.getX() - (settlement.anchor().getX() + 0.5D);
        final double dz = player.getZ() - (settlement.anchor().getZ() + 0.5D);
        return dx * dx + dz * dz <= (double) settings.interactionRadius() * settings.interactionRadius();
    }

    public static Optional<SettlementRecord> nearest(final KingdomsSavedData data, final ServerPlayer player, final ContractSettings settings)
    {
        return data.settlements().records().stream().filter(settlement -> near(player, settlement, settings))
            .min(Comparator.comparingDouble(settlement -> player.distanceToSqr(settlement.anchor().getX() + 0.5D,
                player.getY(), settlement.anchor().getZ() + 0.5D)));
    }

    // ------------------------------------------------------------------------------------------------ player actions

    public Component accept(final ServerPlayer player, final Contract contract)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(player.serverLevel());
        final ContractSettings settings = settings();
        final Optional<Component> tooFar = requireNear(data, player, contract, settings);
        if (tooFar.isPresent()) return tooFar.get();
        final Optional<ContractService.Refusal> refusal = ContractService.accept(data, player.getUUID(), contract, gameTime(player), settings);
        if (refusal.isPresent()) return Component.literal(refusal.get().message()).withStyle(ChatFormatting.RED);
        accepted++;
        KingdomsMod.LOGGER.info("Contract {} ({} {}) for {} accepted by {}; {} emeralds reserved", contract.id(), contract.amount(),
            contract.resource(), settlementName(data, contract), player.getGameProfile().getName(), contract.reservedReward());
        if (contract.kind() != Contract.Kind.DELIVERY)
            return Component.literal("Accepted for " + settlementName(data, contract) + ": " + ContractText.describe(contract) + ", for "
                + contract.agreedReward() + " emeralds. Help win the indicated encounter to complete this contract.").withStyle(ChatFormatting.GREEN);
        return Component.literal("Accepted: deliver " + ContractText.amount(contract.resource(), contract.amount()) + " to "
            + settlementName(data, contract) + " within " + ContractText.duration(contract.deadline() - gameTime(player))
            + " for " + contract.agreedReward() + " emeralds. ").withStyle(ChatFormatting.GREEN)
            .append(ContractText.button("Deliver", ChatFormatting.GOLD, "/kingdoms contract deliver " + contract.id(),
                "Hand over what you carry", true));
    }

    public Component deliver(final ServerPlayer player, final Contract contract)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(player.serverLevel());
        final ContractSettings settings = settings();
        final Optional<Component> tooFar = requireNear(data, player, contract, settings);
        if (tooFar.isPresent()) return tooFar.get();
        final long gameTime = gameTime(player);
        final ContractService.Delivery delivery;
        try
        {
            delivery = ContractService.deliver(data, player.getUUID(), contract, new PlayerInventory(player), gameTime, settings);
        }
        catch (RuntimeException failure)
        {
            rolledBack++;
            KingdomsMod.LOGGER.error("Delivery for contract {} by {} failed and was rolled back", contract.id(),
                player.getGameProfile().getName(), failure);
            return Component.literal("Something went wrong; nothing was taken. Please report this.").withStyle(ChatFormatting.RED);
        }
        if (delivery.refusal().isPresent())
        {
            final MutableComponent text = Component.literal(delivery.refusal().get().message()).withStyle(ChatFormatting.RED);
            if (delivery.refusal().get() == ContractService.Refusal.NOTHING_TO_DELIVER && contract.resource() != null)
                text.append(Component.literal(" They need " + ContractText.amount(contract.resource(), contract.remaining()) + "."));
            if (!delivery.reputation().isEmpty()) text.append(Component.literal("\n")).append(ContractText.reputation(data, delivery.reputation()));
            return text;
        }
        delivered += delivery.units();
        if (!delivery.completed())
            return Component.literal("Delivered " + delivery.credited() + " " + contract.resource().name().toLowerCase(Locale.ROOT)
                + "; still owed: " + ContractText.amount(contract.resource(), contract.remaining()) + ", "
                + ContractText.duration(contract.deadline() - gameTime) + " left.").withStyle(ChatFormatting.YELLOW);
        completed++;
        KingdomsMod.LOGGER.info("Contract {} for {} completed by {}; paid {} emeralds", contract.id(), settlementName(data, contract),
            player.getGameProfile().getName(), delivery.payout());
        final MutableComponent text = Component.literal("Contract fulfilled! " + settlementName(data, contract) + " pays you "
            + delivery.payout() + " emeralds.").withStyle(ChatFormatting.GREEN);
        if (!delivery.reputation().isEmpty()) text.append(Component.literal("\n")).append(ContractText.reputation(data, delivery.reputation()));
        return text;
    }

    public Component abandon(final ServerPlayer player, final Contract contract)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(player.serverLevel());
        final Optional<ContractService.Refusal> refusal = ContractService.checkAbandon(player.getUUID(), contract);
        if (refusal.isPresent()) return Component.literal(refusal.get().message()).withStyle(ChatFormatting.RED);
        final ContractService.Closure closure = ContractService.abandon(data, player.getUUID(), contract, gameTime(player), settings());
        abandoned++;
        final MutableComponent text = Component.literal("You abandoned the contract with " + settlementName(data, contract)
            + ". Already delivered goods are not returned.").withStyle(ChatFormatting.YELLOW);
        if (!closure.reputation().isEmpty()) text.append(Component.literal("\n")).append(ContractText.reputation(data, closure.reputation()));
        return text;
    }

    /** Pays rewards of completed contracts that could not be issued earlier; returns a message, or empty if none. */
    public Optional<Component> claimPending(final ServerPlayer player)
    {
        final KingdomsSavedData data = KingdomsSavedData.get(player.serverLevel());
        if (data.contracts().pendingRewards(player.getUUID()).isEmpty()) return Optional.empty();
        final PlayerInventory inventory = new PlayerInventory(player);
        if (!inventory.canReceive()) return Optional.empty(); // dead or leaving: the reward stays pending
        final int paid = ContractService.claimPendingRewards(data, player.getUUID(), inventory, gameTime(player));
        KingdomsMod.LOGGER.info("Issued {} pending contract emeralds to {}", paid, player.getGameProfile().getName());
        return Optional.of(Component.literal("You receive " + paid + " emeralds still owed to you for completed contracts.")
            .withStyle(ChatFormatting.GREEN));
    }

    public record Stats(long accepted, long completed, long failed, long abandoned, long delivered, long rolledBack) {}
    public Stats stats() { return new Stats(accepted, completed, failed, abandoned, delivered, rolledBack); }

    // ------------------------------------------------------------------------------------------------ internals

    private static Optional<Component> requireNear(final KingdomsSavedData data, final ServerPlayer player, final Contract contract,
        final ContractSettings settings)
    {
        final SettlementRecord settlement = data.settlements().get(contract.settlementId()).orElse(null);
        if (settlement == null) return Optional.of(Component.literal("That settlement no longer exists.").withStyle(ChatFormatting.RED));
        if (near(player, settlement, settings)) return Optional.empty();
        return Optional.of(Component.literal("You must be in " + settlement.name() + " to do that.").withStyle(ChatFormatting.RED));
    }

    /**
     * Strategic units one item is worth for a contract. Enchanted, renamed, or uncommon items (golden apples, ...)
     * and food with side effects (rotten flesh, spider eyes, raw chicken, ...) are never taken.
     */
    public static long unitValue(final ItemStack stack, final EconomicResource resource)
    {
        if (stack.isEmpty() || stack.isEnchanted() || stack.has(DataComponents.CUSTOM_NAME) || stack.getRarity() != Rarity.COMMON) return 0L;
        if (resource == EconomicResource.FOOD)
        {
            final FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null || !food.effects().isEmpty()) return 0L;
        }
        return Math.round(VALUES.values(stack.copyWithCount(1)).getOrDefault(resource, 0.0D));
    }

    static String settlementName(final KingdomsSavedData data, final Contract contract)
    {
        return data.settlements().get(contract.settlementId()).map(SettlementRecord::name)
            .or(() -> data.colony(contract.settlementId()).map(colony -> colony.name())).orElse("?");
    }

    /** The main inventory (not armour or off-hand) of an online player. */
    private static final class PlayerInventory implements ContractService.InventoryPort
    {
        private final ServerPlayer player;

        PlayerInventory(final ServerPlayer player) { this.player = player; }

        @Override
        public List<ContractRules.Slot> slots(final EconomicResource resource)
        {
            final Inventory inventory = player.getInventory();
            final List<ContractRules.Slot> slots = new ArrayList<>();
            for (int index = 0; index < inventory.items.size(); index++)
            {
                final ItemStack stack = inventory.items.get(index);
                final long value = unitValue(stack, resource);
                if (value > 0L) slots.add(new ContractRules.Slot(index, stack.getCount(), value));
            }
            return slots;
        }

        @Override
        public Runnable remove(final List<ContractRules.Removal> removals)
        {
            final Inventory inventory = player.getInventory();
            for (final ContractRules.Removal removal : removals)
                if (removal.index() < 0 || removal.index() >= inventory.items.size()
                    || inventory.items.get(removal.index()).getCount() < removal.count())
                    throw new IllegalStateException("Inventory slot " + removal.index() + " changed");
            final Map<Integer, ItemStack> before = new LinkedHashMap<>();
            for (final ContractRules.Removal removal : removals)
            {
                before.put(removal.index(), inventory.items.get(removal.index()).copy());
                inventory.items.get(removal.index()).shrink(removal.count());
            }
            inventory.setChanged();
            return () -> {
                before.forEach((index, stack) -> inventory.items.set(index, stack));
                inventory.setChanged();
            };
        }

        @Override
        public void give(final int emeralds)
        {
            final List<ItemStack> stacks = new ArrayList<>();
            for (int remaining = emeralds; remaining > 0; remaining -= 64) stacks.add(new ItemStack(Items.EMERALD, Math.min(64, remaining)));
            for (final ItemStack stack : stacks)
                if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
        }

        @Override
        public boolean canReceive()
        {
            return player.isAlive() && !player.hasDisconnected() && !player.isRemoved();
        }
    }
}
