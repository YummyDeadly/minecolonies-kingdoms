package com.minecolonies.kingdoms.citizen.interaction;

import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.contract.ContractText;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.diplomacy.ReputationTier;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Collects the interaction lines for a representative from the registered handlers. */
public final class SettlementCitizenInteractionService
{
    private static final SettlementCitizenInteractionService INSTANCE = new SettlementCitizenInteractionService();
    private final List<CitizenInteractionHandler> handlers = new CopyOnWriteArrayList<>();

    private SettlementCitizenInteractionService()
    {
        register(new GreetingHandler());
    }

    public static SettlementCitizenInteractionService getInstance() { return INSTANCE; }

    /** Registers a handler; a second handler of the same class replaces the first (servers restart in one JVM). */
    public synchronized void register(final CitizenInteractionHandler handler)
    {
        handlers.removeIf(existing -> existing.getClass() == handler.getClass());
        handlers.add(handler);
        handlers.sort(Comparator.comparingInt(CitizenInteractionHandler::priority));
    }

    public List<Component> interact(final CitizenInteractionContext context)
    {
        final List<Component> lines = new ArrayList<>();
        final ReputationTier tier = ReputationService.tier(KingdomsSavedData.get(context.player().serverLevel()),
            context.player().getUUID(), context.colony().factionId());
        lines.add(Component.literal(context.representative().name()).withStyle(ChatFormatting.GOLD)
            .append(Component.literal(" — " + context.representative().role().displayName() + " of "
                + context.settlement().name() + " (" + context.activity().description() + ") ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(tier.displayName()).withStyle(ContractText.color(tier))));
        for (final CitizenInteractionHandler handler : handlers) handler.respond(context).ifPresent(lines::add);
        return lines;
    }

    /** Default flavour line derived from the settlement's current strategic state (read-only). */
    static final class GreetingHandler implements CitizenInteractionHandler
    {
        @Override
        public Optional<Component> respond(final CitizenInteractionContext context)
        {
            final var critical = context.colony().needs().stream()
                .filter(need -> need.severity() == NeedSeverity.CRITICAL || need.severity() == NeedSeverity.HIGH)
                .findFirst();
            final ReputationTier tier = ReputationService.tier(KingdomsSavedData.get(context.player().serverLevel()),
                context.player().getUUID(), context.colony().factionId());
            final String line;
            if (tier == ReputationTier.HOSTILE) line = "Leave. You are not welcome in " + context.settlement().name() + ".";
            else if (tier == ReputationTier.UNFRIENDLY) line = "Hmph. Mind your manners here.";
            else if (critical.isPresent())
                line = switch (critical.get().type())
                {
                    case FOOD_SHORTAGE -> "Food has been scarce here lately.";
                    case HOUSING_SHORTAGE -> "We are running out of room; new houses are sorely needed.";
                    case STORAGE_SHORTAGE -> "The storehouses are overflowing.";
                    case WOOD_SHORTAGE -> "Timber is hard to come by these days.";
                    case STONE_SHORTAGE -> "We could use more stone for building.";
                    case IRON_SHORTAGE -> "Iron is dear; the smith grumbles about it.";
                    case LABOR_SHORTAGE -> "There is more work than hands.";
                };
            else if (context.dayTime() % 24000L >= 12500L) line = "It is late; I should be getting home.";
            else if (tier.compareTo(ReputationTier.HONORED) >= 0) line = "Always good to see you, friend of " + context.settlement().name() + "!";
            else if (tier == ReputationTier.FRIENDLY) line = "Good to see you again.";
            else line = "Welcome to " + context.settlement().name() + ", traveller.";
            return Optional.of(Component.literal("\"" + line + "\"").withStyle(ChatFormatting.WHITE));
        }
    }
}
