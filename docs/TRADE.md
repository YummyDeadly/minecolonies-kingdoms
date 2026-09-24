# Abstract trade

## Scope

Two independent strategic NPC colonies can exchange resources without a player or physical MineColonies settlement. `TradeShipment` is always the source of truth for cargo. Phase 4 may temporarily represent an in-transit shipment with caravan entities. Phase 5 supplies road paths; Phase 6 applies their speed multipliers to newly departing shipment ETA. Danger rolls, contracts, market prices, and building orders remain out of scope.

Automatic trading currently selects only `NPC_ABSTRACT` colonies. `PLAYER_PHYSICAL` colonies are protected from strategic withdrawals, and `NPC_PHYSICAL` support remains disabled until a public, transactional MineColonies inventory-mutation adapter exists.

## Market model

Offers and demands are transient values reconstructed from persisted colony economies:

- `TradeOffer` requires positive production flow and export capacity.
- `TradeDemand` uses the larger of reserve shortage and one-day flow deficit.
- planned and in-transit inbound cargo is subtracted from demand;
- planned outbound cargo is subtracted from export capacity;
- an AI `IMPORT_RESOURCE` decision increases demand priority but is not required for matching.

Export capacity is:

```text
stockpile
- desired reserve
- configured reserve safety buffer
- planned outbound reservations
```

The matcher chooses `min(available export, remaining demand, maximum shipment)`. Amounts below the configured minimum are ignored.

## Deterministic matching

`TradeMatcher` is pure domain code. Demands are sorted by descending priority, resource, and colony UUID. Candidate exporters are restricted to the same dimension and configured maximum distance, then sorted by distance and UUID. Offer capacity is consumed in that stable order, so input collection insertion order cannot change the result.

Matching is currently O(n² × resources) in the worst case, but it runs only at `trade.matchIntervalTicks`, filters non-eligible colonies first, and applies dimension/distance constraints. The API permits a spatial index to replace candidate discovery later.

`TradePermissionPolicy` is a separate boundary. The default allows same-faction and neutral relations and rejects a pair when either faction has a negative relation to the other.

## Routes

A `TradeRoute` is unique by `(origin colony, destination colony, resource)` and persists:

- UUID and endpoints;
- resource;
- target and last actual shipment amounts;
- creation/processing times;
- priority;
- `ACTIVE`, `PAUSED`, or `BROKEN` status;
- unmatched-cycle counter and optional reason.

An unmatched active route is retained and becomes `PAUSED` only after `trade.routePauseGraceCycles`. A later compatible offer/demand reactivates it. Deleted endpoints mark routes `BROKEN`; routes are retained as diagnostic history rather than recreated every cycle.

## Shipment ownership and reservations

One open shipment is allowed per route. Its lifecycle is:

```text
PLANNED
  cargo still belongs to origin stockpile
  amount is a persisted reservation
        |
        | atomic departure processing
        v
IN_TRANSIT
  origin stockpile has been reduced
  shipment exclusively owns the cargo
        |
        | arrival time reached
        v
DELIVERED
  destination stockpile has been increased once
```

`ShipmentTravelTimeEstimator` sets duration once, when a planned shipment departs:

```text
direct effective distance = geometric path length
road effective distance   = sum(edge length / edge speed multiplier)
duration                  = baseTravelTicks + ceil(effective distance × travelTicksPerBlock)
```

Representation is `ABSTRACT` or `PHYSICAL`. Abstract time progression pauses while physical; the leader's projected direct-or-road path position advances the same shipment. Dematerialization derives a new `arrivalAt` from persisted progress and the original persisted duration. No migration or restart recalculates an already in-transit shipment.

Every stockpile mutation goes through `EconomyManager.withdrawForShipment` or `depositShipment`. Match-cycle allocation plus persisted planned reservations prevents two shipments from committing the same export capacity.

## Restart and failure behavior

Routes, shipments, statuses, timestamps, progress, representation, and in-transit cargo remain unchanged in SavedData schema v6. On restart:

- `PLANNED` cargo remains in origin stock and reserved;
- `IN_TRANSIT` cargo remains absent from both colony stockpiles;
- shipment processing resumes against persisted `arrivalAt`;
- persisted physical shipments become abstract and stale entity associations are discarded;
- `DELIVERED` and `FAILED` shipments are terminal and cannot apply stock mutations again.

If a destination or route disappears, in-transit cargo is returned to the origin when it still exists, then the shipment becomes `FAILED`. If the origin itself was deleted, no valid return owner exists and cargo is treated as lost. A planned shipment fails without a refund because it has not withdrawn anything yet. Physical destruction of a leader or carrier is explicitly `CARAVAN_DESTROYED`: the shipment fails and its cargo is lost, with no entity inventory or loot duplication path.

Physical representation details and diagnostics are documented in [Physical caravans](CARAVANS.md).

## Configuration

The `trade` server-config section contains:

- `enabled`
- `matchIntervalTicks`
- `shipmentIntervalTicks`
- `maxRoutesPerColony`
- `maxAbstractTradeDistance`
- `minimumShipmentAmount`
- `maximumShipmentAmount`
- `baseTravelTicks`
- `travelTicksPerBlock`
- `routePauseGraceCycles`
- `exportSafetyBufferPercent`

## Developer commands

```text
/kingdoms colony create-npc <name>
/kingdoms colony delete <uuid>
/kingdoms economy show <colony>
/kingdoms economy set <colony> <resource> stockpile <value>
/kingdoms economy set <colony> <resource> production <value>
/kingdoms economy set <colony> <resource> consumption <value>
/kingdoms economy set <colony> <resource> reserve <value>
/kingdoms trade routes
/kingdoms trade route <uuid>
/kingdoms trade shipments
/kingdoms trade shipment <uuid>
/kingdoms trade match
/kingdoms trade tick
/kingdoms trade stats
```

`<colony>` accepts a strategic UUID, MineColonies numeric ID, or unique single-word name. Mutation and trade commands require permission level 2.

## Diplomatic permission (Phase 7)

`DefaultTradePermissionPolicy` allows trade between different factions only when both directions of their relation are at least neutral (−19 or better). Tense or hostile neighbours do not trade. Relations are set daily by the diplomacy evaluator (see [Reputation, contracts, and diplomacy](DIPLOMACY.md)). Delivered shipments between neighbours improve their relation.

## Bandit losses (Phase 8)

A shipment can lose cargo to bandits at most once: `recordBanditLoss(encounterId, lost)` stores the encounter ID and `lostAmount`, and refuses a second loss. Every deposit and refund uses `deliverableAmount() = amount − lostAmount`:
- abstract arrival;
- physical arrival;
- endpoint-unavailable returns;
- colony-deletion refunds.

A total loss fails the shipment with `BANDIT_RAID`. Holds and delays move `arrivalAt` without changing travel duration or progress already made. `/kingdoms trade shipment <id>` shows the loss and any open encounter. See [Bandits](BANDITS.md).
