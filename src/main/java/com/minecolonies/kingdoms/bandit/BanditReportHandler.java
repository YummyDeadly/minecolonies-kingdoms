package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.citizen.CitizenRole;
import com.minecolonies.kingdoms.citizen.interaction.CitizenInteractionContext;
import com.minecolonies.kingdoms.citizen.interaction.CitizenInteractionHandler;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.world.road.RoadRecord;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Presentation only: a settlement's guard reports the most dangerous road nearby and where bandits were seen. */
public final class BanditReportHandler implements CitizenInteractionHandler
{
    /** Below this, a guard calls the roads quiet. */
    public static final double REPORT_THRESHOLD = 25.0D;

    @Override
    public int priority() { return 150; }

    @Override
    public Optional<Component> respond(final CitizenInteractionContext context)
    {
        if (context.representative().role() != CitizenRole.GUARD) return Optional.empty();
        final KingdomsSavedData data = KingdomsSavedData.get(context.player().serverLevel());
        final UUID settlementId = context.settlement().id();
        final RoadRecord worst = data.roads().incident(settlementId).stream()
            .max(Comparator.comparingDouble(road -> data.bandits().threatOf(road.id()))).orElse(null);
        if (worst == null || data.bandits().threatOf(worst.id()) < REPORT_THRESHOLD)
            return Optional.of(Component.literal("\"The roads around here are quiet.\"").withStyle(ChatFormatting.GRAY));
        final String other = data.settlements().get(worst.other(settlementId)).map(value -> value.name()).orElse("the next town");
        final StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "\"The road to %s is dangerous (threat %.0f).",
            other, data.bandits().threatOf(worst.id())));
        data.bandits().open().stream().filter(encounter -> encounter.roadId().equals(worst.id())
                && encounter.status() == BanditEncounter.Status.ACTIVE).findFirst()
            .ifPresent(encounter -> line.append(" Bandits were seen near ").append(encounter.position().getX()).append(", ")
                .append(encounter.position().getZ()).append('.'));
        line.append('"');
        return Optional.of(Component.literal(line.toString()).withStyle(ChatFormatting.GOLD));
    }
}
