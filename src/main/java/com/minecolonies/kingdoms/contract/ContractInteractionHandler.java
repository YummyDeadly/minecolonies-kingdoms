package com.minecolonies.kingdoms.contract;

import com.minecolonies.kingdoms.citizen.CitizenRole;
import com.minecolonies.kingdoms.citizen.interaction.CitizenInteractionContext;
import com.minecolonies.kingdoms.citizen.interaction.CitizenInteractionHandler;
import com.minecolonies.kingdoms.diplomacy.ReputationService;
import com.minecolonies.kingdoms.diplomacy.ReputationTier;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Presentation only: the settlement's official and merchants show its contract board (open offers with clickable
 * [Accept], the player's contracts here with [Deliver]/[Abandon]); other residents point to them. All state changes
 * happen in {@link ContractService} (offer generation) and through the contract commands the buttons run.
 */
public final class ContractInteractionHandler implements CitizenInteractionHandler
{
    @Override
    public int priority() { return 200; }

    @Override
    public Optional<Component> respond(final CitizenInteractionContext context)
    {
        final ContractSettings settings = ContractManager.settings();
        if (!settings.enabled()) return Optional.empty();
        final KingdomsSavedData data = KingdomsSavedData.get(context.player().serverLevel());
        final UUID playerId = context.player().getUUID();
        final UUID settlementId = context.settlement().id();
        final long gameTime = ContractManager.gameTime(context.player());
        final ReputationTier tier = ReputationService.tier(data, playerId, context.colony().factionId());
        final List<Contract> mine = data.contracts().active(playerId).stream()
            .filter(value -> value.settlementId().equals(settlementId)).toList();
        final CitizenRole role = context.representative().role();
        if (role != CitizenRole.OFFICIAL && role != CitizenRole.MERCHANT)
        {
            if (!mine.isEmpty())
                return Optional.of(Component.literal("\"Our official or a merchant will take what you owe us.\"").withStyle(ChatFormatting.GRAY));
            if (tier.contractsAllowed() && !data.contracts().offers(settlementId).isEmpty())
                return Optional.of(Component.literal("\"Looking for work? Ask our official or a merchant.\"").withStyle(ChatFormatting.GRAY));
            return Optional.empty();
        }
        if (!tier.contractsAllowed())
            return Optional.of(Component.literal("\"We have no work for the likes of you.\"").withStyle(ChatFormatting.RED));

        final List<Contract> offers = ContractService.refresh(data, settlementId, gameTime, settings, false);
        final MutableComponent text = Component.empty();
        boolean any = false;
        final Optional<Component> pending = ContractManager.getInstance().claimPending(context.player());
        if (pending.isPresent())
        {
            text.append(pending.get());
            any = true;
        }
        for (final Contract contract : mine)
        {
            if (any) text.append(Component.literal("\n"));
            any = true;
            if (contract.kind() == Contract.Kind.DELIVERY)
                text.append(ContractText.button("Deliver", ChatFormatting.GOLD, "/kingdoms contract deliver " + contract.id(),
                    "Hand over what you carry", true)).append(Component.literal(" "));
            text.append(Component.literal(ContractText.progress(contract) + (contract.objective().security()
                ? " (until the bandits are dealt with) " : ", " + ContractText.duration(contract.deadline() - gameTime) + " left "))
                .withStyle(ChatFormatting.WHITE));
            text.append(ContractText.button("Abandon", ChatFormatting.DARK_GRAY, "/kingdoms contract abandon " + contract.id(),
                "Give up (reputation penalty); press Enter to confirm", false));
        }
        for (final Contract contract : offers)
        {
            if (any) text.append(Component.literal("\n"));
            any = true;
            final int reward = ContractRules.agreedReward(contract.objective().baseReward(), tier.rewardMultiplier());
            text.append(ContractText.button("Accept", ChatFormatting.GREEN, "/kingdoms contract accept " + contract.id(),
                "Take this job", true));
            text.append(Component.literal(" " + ContractText.describe(contract) + (contract.objective().security()
                ? " while the bandits are there" : " within " + ContractText.duration(settings.contractDurationTicks())) + ", " + reward
                + " emeralds, +"
                + contract.objective().reputationReward() + " reputation").withStyle(ChatFormatting.WHITE));
        }
        if (!any) text.append(Component.literal("\"We have no work for travellers right now.\"").withStyle(ChatFormatting.GRAY));
        return Optional.of(text);
    }
}
