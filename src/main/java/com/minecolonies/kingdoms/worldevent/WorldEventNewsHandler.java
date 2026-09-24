package com.minecolonies.kingdoms.worldevent;

import com.minecolonies.kingdoms.citizen.CitizenRole;
import com.minecolonies.kingdoms.citizen.interaction.CitizenInteractionContext;
import com.minecolonies.kingdoms.citizen.interaction.CitizenInteractionHandler;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Optional;

/**
 * Presentation only: officials, merchants, and guards pass on the news of world events that concern their settlement,
 * its roads, and its faction (at most three lines). Nothing here changes state.
 */
public final class WorldEventNewsHandler implements CitizenInteractionHandler
{
    public static final int MAX_LINES = 3;

    @Override
    public int priority() { return 160; }

    @Override
    public Optional<Component> respond(final CitizenInteractionContext context)
    {
        final CitizenRole role = context.representative().role();
        if (role != CitizenRole.OFFICIAL && role != CitizenRole.MERCHANT && role != CitizenRole.GUARD) return Optional.empty();
        final KingdomsSavedData data = KingdomsSavedData.get(context.player().serverLevel());
        final List<WorldEventRecord> news = WorldEventService.news(data, context.settlement().id());
        if (news.isEmpty()) return Optional.empty();
        final long gameTime = context.player().serverLevel().getGameTime();
        final MutableComponent text = Component.empty();
        for (int index = 0; index < Math.min(MAX_LINES, news.size()); index++)
        {
            final WorldEventRecord event = news.get(index);
            if (index > 0) text.append(Component.literal("\n"));
            final String line = event.status() == WorldEventRecord.Status.PLANNED
                ? "\"They say " + WorldEventManager.rumour(data, event) + "\""
                : "\"" + event.type().displayName() + " at " + WorldEventService.subjectName(data, event) + " ("
                    + WorldEventManager.when(event.endsAt() - gameTime) + " left).\"";
            text.append(Component.literal(line).withStyle(event.status() == WorldEventRecord.Status.PLANNED ? ChatFormatting.GRAY : ChatFormatting.GOLD));
        }
        return Optional.of(text);
    }
}
