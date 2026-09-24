# Physical caravans

## Authority and lifecycle

Physical caravans are a temporary representation of an existing in-transit `TradeShipment`. The shipment remains the only owner of resource type and amount. Entities persist only caravan ID, shipment ID, and member role, so killing or duplicating an entity cannot create lootable strategic cargo.

```text
ABSTRACT shipment
  | player enters inner radius and caps permit
  v
PHYSICAL shipment + CaravanInstance + 4 entities
  | leader movement projects onto ShipmentPath
  +--> arrival: TradeManager deposits once, shipment DELIVERED
  +--> outer radius/stuck/technical loss: remaining time recalculated, ABSTRACT
  +--> leader or carrier destroyed: shipment FAILED, cargo LOST
```

The physical group is one leader, one carrier, and two guards. The leader accepts right-click interaction and reports route, cargo, and authoritative shipment progress. The entities have no strategic inventory.

## Materialization policy

Checks run on the configured interval and sort candidates by nearest player distance. A shipment materializes only when:

- it is `IN_TRANSIT` and `ABSTRACT`;
- a non-spectator player in the shipment dimension is within `materializationRadius`;
- global and assigned-player caps both have room;
- its estimated position is in an already-loaded chunk.

It dematerializes after all players are beyond `dematerializationRadius`. The required gap between radii prevents boundary flapping. No chunk tickets are created.

## Path and recovery

`ShipmentPath` is the replaceable path boundary. `ShipmentPathResolver` now selects a multi-edge `RoadShipmentPath` when both endpoint settlements have a graph route, otherwise it safely falls back to `DirectShipmentPath`. Both support position interpolation, position-to-progress projection, total length, and a bounded waypoint ahead of current progress. The manager sends the leader toward local waypoints; carrier and guards follow the leader.

If the group cannot progress for `stuckTimeoutTicks`, or required entities disappear for a technical reason, the representation safely falls back to abstract travel. A deliberate leader/carrier death is not a technical fallback: `TradeShipment` becomes `FAILED` with `CARAVAN_DESTROYED` and the withdrawn cargo is lost.

## Restart safety and orphans

SavedData schema v6 records shipment representation/progress and `CaravanInstance` associations alongside the settlement/road/growth registries. At server startup every physical shipment is normalized to abstract travel using its stored progress, and the caravan registry is cleared. Any old entity loaded afterward fails its periodic registry check and self-discards. This avoids entity duplication and prevents a server restart from delivering cargo twice.

## Configuration

The `caravan` server-config section contains:

- `enabled`
- `materializationRadius`
- `dematerializationRadius`
- `maxPhysicalCaravans`
- `maxPhysicalCaravansPerPlayer`
- `updateIntervalTicks`
- `localWaypointDistance`
- `stuckTimeoutTicks`
- `pathRetryIntervalTicks`
- `debugNames`

## Operator diagnostics

```text
/kingdoms caravan list
/kingdoms caravan info <shipment-uuid>
/kingdoms caravan materialize <shipment-uuid>
/kingdoms caravan dematerialize <shipment-uuid>
/kingdoms caravan teleport-near <shipment-uuid>
/kingdoms caravan stats
```

`materialize` provides a short administrative hold so a headless dedicated server can inspect the four spawned entities even with no connected player. `teleport-near` moves that group to the command source and updates authoritative path progress; it does not alter cargo directly.

Settlement representatives (Phase 6.7) reuse the same principles: authoritative abstract state, near-player bounded materialization with hysteresis and caps, loaded chunks only, and orphan-safe entities. See [Physical settlement population](CITIZENS.md).

## Bandit encounters (Phase 8)

- While an active bandit encounter holds the shipment (`BanditManager.holds`), the physical caravan stops, its guards defend, and its stuck timer is paused. An abstract shipment is held by `TradeShipment.holdUntil` instead.
- If the leader or carrier dies during such an encounter, the encounter decides the loss: a seeded 30–60% partial loss through `EncounterService`. The caravan then dematerializes, and the rest of the cargo travels on abstractly. Without an encounter, the old `CARAVAN_DESTROYED` rule applies.
- A total loss decided by an encounter fails the shipment with `BANDIT_RAID`, and the caravan is removed on its next update.
- Delivery deposits only the deliverable amount (`amount − lostAmount`).

See [Bandits](BANDITS.md).
