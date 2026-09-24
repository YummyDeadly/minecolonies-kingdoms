package com.minecolonies.kingdoms.contract;

import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.diplomacy.ReputationTier;
import com.minecolonies.kingdoms.economy.EconomicResource;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.Locale;

/** Chat formatting for contracts and reputation (English, like the rest of the mod's operator output). */
public final class ContractText
{
    private ContractText() {}

    public static String resource(final EconomicResource resource)
    {
        final String name = resource.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** "96 food (about 20 bread)". */
    public static String amount(final EconomicResource resource, final long amount)
    {
        final ContractRules.Terms terms = ContractRules.terms(resource);
        final String unit = resource.name().toLowerCase(Locale.ROOT);
        if (terms == null || terms.unitsPerHintItem() == 1) return amount + " " + (terms == null ? unit : terms.hintItem());
        return amount + " " + unit + " (about " + ContractRules.hintItems(resource, amount) + " " + terms.hintItem() + ")";
    }

    /** What an offer asks for, per kind. */
    public static String describe(final Contract contract)
    {
        final Contract.Objective objective = contract.objective();
        final String where = objective.targetPosition() == null ? ""
            : " near " + objective.targetPosition().getX() + ", " + objective.targetPosition().getZ();
        return switch (objective.kind())
        {
            case DELIVERY -> resource(objective.resource()) + ": " + amount(objective.resource(), objective.amount());
            case ESCORT_CARAVAN -> "Escort: defend the caravan carrying " + objective.amount() + " "
                + objective.resource().name().toLowerCase(Locale.ROOT) + " from bandits" + where;
            case CLEAR_BANDITS -> "Clear the road: " + objective.amount() + " bandits" + where;
        };
    }

    /** Progress of an accepted contract, per kind. */
    public static String progress(final Contract contract)
    {
        return switch (contract.kind())
        {
            case DELIVERY -> resource(contract.resource()) + " " + contract.delivered() + "/" + contract.amount();
            case ESCORT_CARAVAN, CLEAR_BANDITS -> describe(contract);
        };
    }

    /** Minecraft time: 24000 ticks per day, 1000 per hour. */
    public static String duration(final long ticks)
    {
        final long clamped = Math.max(0L, ticks);
        final long days = clamped / ContractRules.DAY_TICKS;
        final long hours = (clamped % ContractRules.DAY_TICKS) / 1000L;
        if (days > 0L) return days + "d " + hours + "h";
        return Math.max(1L, hours) + "h";
    }

    public static MutableComponent button(final String label, final ChatFormatting color, final String command,
        final String hover, final boolean run)
    {
        return Component.literal("[" + label + "]").withStyle(style -> style.withColor(color)
            .withClickEvent(new ClickEvent(run ? ClickEvent.Action.RUN_COMMAND : ClickEvent.Action.SUGGEST_COMMAND, command))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    public static ChatFormatting color(final ReputationTier tier)
    {
        return switch (tier)
        {
            case HOSTILE -> ChatFormatting.DARK_RED;
            case UNFRIENDLY -> ChatFormatting.RED;
            case NEUTRAL -> ChatFormatting.GRAY;
            case FRIENDLY -> ChatFormatting.GREEN;
            case HONORED -> ChatFormatting.AQUA;
            case REVERED -> ChatFormatting.LIGHT_PURPLE;
        };
    }

    /** "Highhaven P1N1 Council: +5 reputation (12, Neutral)" lines for every change, marking tier changes. */
    public static MutableComponent reputation(final KingdomsSavedData data, final ReputationService.Result result)
    {
        final MutableComponent text = Component.empty();
        boolean first = true;
        for (final ReputationService.Change change : result.changes())
        {
            if (!first) text.append(Component.literal("\n"));
            first = false;
            final String name = data.faction(change.factionId()).map(faction -> faction.name()).orElse("?");
            final int delta = change.after() - change.before();
            text.append(Component.literal(name + ": " + (delta > 0 ? "+" : "") + delta + " reputation (" + change.after() + ", ")
                .withStyle(delta >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
            text.append(Component.literal(change.tierAfter().displayName()).withStyle(color(change.tierAfter())));
            text.append(Component.literal(change.tierChanged() ? ", was " + change.tierBefore().displayName() + ")" : ")")
                .withStyle(delta >= 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        return text;
    }
}
