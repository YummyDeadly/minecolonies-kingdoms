package com.minecolonies.kingdoms.bandit;

import com.minecolonies.kingdoms.colony.need.NeedSeverity;
import com.minecolonies.kingdoms.contract.Contract;
import com.minecolonies.kingdoms.contract.ContractService;
import com.minecolonies.kingdoms.contract.ContractSettings;
import com.minecolonies.kingdoms.persistence.KingdomsSavedData;
import com.minecolonies.kingdoms.trade.ShipmentRepresentation;
import com.minecolonies.kingdoms.trade.TradeShipment;
import com.minecolonies.kingdoms.trade.TradeShipmentStatus;
import com.minecolonies.kingdoms.world.road.RoadRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Security contracts come only from real, open encounters, through the Phase 7 contract service (reservation at
 * acceptance, exactly-once reward and reputation): an escort offer from the owner of a caravan that bandits are
 * waiting for, and a clear-the-road offer from the settlement nearest to a roadblock. One contract per encounter.
 */
public final class SecurityContracts
{
    public static final int ESCORT_REPUTATION = 5;
    public static final int CLEAR_REPUTATION = 6;
    /** Offers stay up at least this long, so a player has time to react. */
    public static final long MINIMUM_OFFER_TICKS = 1_200L;

    private SecurityContracts() {}

    public static int escortReward(final int strength) { return 3 + 2 * Math.max(1, strength); }
    public static int clearReward(final int strength) { return 4 + 2 * Math.max(1, strength); }

    public static List<Contract> post(final KingdomsSavedData data, final long gameTime, final BanditSettings settings,
        final ContractSettings contractSettings)
    {
        final List<Contract> posted = new ArrayList<>();
        if (!contractSettings.enabled()) return posted;
        for (final BanditEncounter encounter : data.bandits().open())
        {
            if (!data.contracts().targeting(encounter.id()).isEmpty()) continue;
            if (encounter.kind() == BanditEncounter.Kind.AMBUSH)
            {
                final TradeShipment shipment = data.tradeLedger().shipment(encounter.shipmentId()).orElse(null);
                if (shipment == null || shipment.status() != TradeShipmentStatus.IN_TRANSIT || shipment.deliverableAmount() <= 0L) continue;
                final long expires;
                if (encounter.status() == BanditEncounter.Status.PLANNED)
                {
                    final double ahead = Math.max(0.0D, encounter.triggerProgress() - EncounterService.progress(shipment, gameTime));
                    final long eta = shipment.representation() == ShipmentRepresentation.PHYSICAL ? 0L
                        : (long) Math.ceil(ahead * Math.max(1L, shipment.travelDurationTicks()));
                    expires = gameTime + Math.max(MINIMUM_OFFER_TICKS, eta + settings.abstractResolveTicks());
                }
                else expires = Math.max(gameTime + MINIMUM_OFFER_TICKS, encounter.resolveAt());
                final Contract.Objective objective = new Contract.Objective(Contract.Kind.ESCORT_CARAVAN, shipment.resource(),
                    shipment.deliverableAmount(), NeedSeverity.HIGH, 0.0D, 0.0D, escortReward(encounter.remainingStrength()),
                    ESCORT_REPUTATION, encounter.id(), shipment.id(), encounter.position());
                ContractService.postSecurityOffer(data, shipment.originColonyId(), objective, gameTime, expires, contractSettings)
                    .ifPresent(posted::add);
            }
            else if (encounter.status() == BanditEncounter.Status.ACTIVE && encounter.remainingStrength() > 0)
            {
                final RoadRecord road = data.roads().get(encounter.roadId()).orElse(null);
                if (road == null) continue;
                final var issuer = EncounterService.nearestEndpoint(data, road, encounter.position()).orElse(null);
                if (issuer == null) continue;
                final Contract.Objective objective = new Contract.Objective(Contract.Kind.CLEAR_BANDITS, null,
                    encounter.remainingStrength(), NeedSeverity.HIGH, 0.0D, 0.0D, clearReward(encounter.remainingStrength()),
                    CLEAR_REPUTATION, encounter.id(), null, encounter.position());
                ContractService.postSecurityOffer(data, issuer.id(), objective, gameTime,
                    Math.max(gameTime + MINIMUM_OFFER_TICKS, encounter.expiresAt()), contractSettings).ifPresent(posted::add);
            }
        }
        return posted;
    }
}
