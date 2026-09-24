# Architecture

## Dependency direction

```text
MineColonies public API
        |
        v
integration.minecolonies adapters
        |
        v
colony controllers --> economy / needs / decisions
        |
        v
WorldSimulationManager
        |
        +--> TradeManager --> TradeMatcher
        |         |              |
        |         v              v
        |      TradeLedger   transient offers/demands
        |         |
        +--> CaravanManager --> physical entity group
        |         |
        +--> WorldSettlementManager
        |         +--> deterministic settlement planner / chunk generator
        |         +--> Road Geometry V2 / spatial index / chunk generator
        |                       |
        |                       +--> RoadShipmentPath --> CaravanManager
        +--> SettlementGrowthManager
        |         +--> pure need-driven evaluator / persistent layout planner
        |         +--> Structurize catalog / chunk-local blueprint placer
        |
        +--> SettlementCitizenManager (reads population, buildings, layout)
        |         +--> pure roster / budget / schedule / street router / safe spawn
        |         +--> SettlementCitizenEntity (representation only)
        |         +--> SettlementCitizenInteractionService --> handlers (greeting, contract board)
        |
        +--> ContractManager (player I/O) --> ContractService (lifecycle, reservation, delivery transaction)
        |         +--> EconomyManager (stockpile)   +--> ReputationService --> ReputationRegistry
        +--> DiplomacyManager --> DiplomacyEvaluator (events) --> DiplomacyService --> Faction.relations
        |                                                                     +--> TradePermissionPolicy
        |         |
        v
KingdomsSavedData
```

Kingdoms is an addon, not a fork. Only `integration.minecolonies` translates live MineColonies state into Kingdoms domain state. Domain policy and persistence do not depend on MineColonies implementation classes.

## Ownership of state

MineColonies remains authoritative for citizens, jobs, buildings, work orders, builder progress, request-system state, physical inventories, guards, and colony claims.

Kingdoms is authoritative for factions, kingdoms, strategic settlement metadata, active/abstract mode, strategic resource aggregates, needs, recorded AI decisions, trade routes, and in-transit strategic cargo. A warehouse read observes MineColonies state; it does not mutate inventories.

## Modules

- `faction` and `kingdom`: strategic identity and membership.
- `colony`: persistent colony snapshot, metrics/resource ports, and player/AI controllers.
- `economy`: resource vocabulary, stockpiles, flows, snapshots, and interval updates.
- `colony.need` and `colony.decision`: pure evaluation policy separated from execution.
- `simulation`: bounded scheduling, mode resolution, manager, and cumulative profiler.
- `trade`: pure matching, permission policy, routes, shipments, ledger, lifecycle manager, and profiling.
- `caravan`: physical representation registry, direct shipment paths, proximity/cap policy, navigation, recovery, and profiling.
- `world.settlement`: order-independent logical planning, records/registry, terrain scoring, and chunk-local starter generation.
- `world.road`: persistent sparse graph, bounded terrain routing, typed Road Geometry V2, spatial index, chunk-local surfaces, and shipment paths.
- `world.settlement.structure` and `integration.structurize`: deterministic runtime blueprint catalog/selection and a public-API, chunk-local placer.
- `world.settlement.site`: bounded area feasibility (coarse 5x5 broad phase, 9x9 area grid, connected buildable component, water/elevation/step/gate-approach metrics) with per-archetype requirements.
- `world.settlement.template`: Kingdoms-owned semantic starter templates (roles, not file names) and the district planner that turns a template into 2-4 real blueprint lots.
- `world.settlement.terrain`: deterministic per-building pads (`NATURAL`, `CUT`, `FILL`, `CUT_AND_FILL`, `TERRACE`) with bounded depths/volume, retaining edges, a read-only preflight, and chunk-local physical shaping.
- `world.settlement.layout`: persisted style family, template id, lots, entrances, plaza/gate/local street network, per-segment street chunk markers, hierarchical lot search, and planning diagnostics.
- `world.settlement.growth`: persisted logical stages/buildings, deterministic decisions, derived strategic effects, bounded population growth, and safe physical orchestration.
- `citizen`: Phase 6.7 physical population representatives: persisted roster registry, pure roster/budget/schedule/router/safe-spawn/stuck policies, the near-player manager, profiling, and the `interaction` handler service.
- `contract`: Phase 7 contract model, pure terms/delivery/treasury rules, the persisted registry, the state-transition service, the server manager (inventory, payout, sweep), and the dialogue handler.
- `diplomacy`: reputation tiers, stances, pure relation/spillover rules, the reputation service, the bounded neighbour evaluator, and persisted evaluation state.
- `bandit`: Phase 8 components:
  - road threat records and pure threat/encounter rules;
  - encounter planning, the persisted registry, and the single-writer `EncounterService`;
  - security-contract posting;
  - the runtime `BanditRoster` and pure `BanditSpawnPlanner`;
  - the near-player `BanditManager`, profiling, and the guard report handler.
- `entity`: lightweight caravan members and settlement representatives carrying association IDs but no cargo or population authority.
- `persistence`: versioned server-authoritative `SavedData` and migrations.
- `integration.minecolonies`: public API synchronization, metric reads, item valuation, and warehouse observation.
- `command`: read-only diagnostics; it contains no simulation policy.

Future diplomacy, outpost, army, war, and event packages remain separate peers. There is no global god-object for every system.

## Persistence

`KingdomsSavedData` is stored once in the server overworld. Colonies in other dimensions still carry their own dimension key. The stable strategic UUID is derived from dimension plus MineColonies colony ID, preventing duplicate records after restart.

Schema version 12 adds the Phase 8 bandit registry:
- road threats, encounters, shipment assessments, and the last evaluation time;
- the optional `banditEncounter`/`lostAmount` on shipments;
- contracts in format 3 (a `kind` and an optional objective target).

v11 → v12 only adds an empty registry, and old contracts read as deliveries. Schema version 11 is the final Phase 7 model: the player `reputation` registry, contracts in format 2 (lifecycle, objective snapshot, reservation at acceptance, exactly-once flags, per-resource cooldowns), and the relation audit log. The ambiguous Phase 1 `Faction.reputation` map is removed. Schema 10 was written only by the preliminary Phase 7 build; v10 → v11 converts it without losing value, and v9 → v10 only adds empty containers. Schema version 9 adds the settlement representative rosters (`citizens`); v8 → v9 only adds an empty registry. Schema version 8 persists the growth registry, blueprint identities/transforms, per-building terrain-shaping plans, building origin (`LEGACY`/`STARTER`/`GROWTH`), settlement site analyses, layouts with template id, typed local street points and per-segment street chunk markers, starter-district status/planner version/diagnostics, exact typed road geometry, and generation-version chunk markers. `KingdomsDataMigrator` provides sequential v1 → … → v7 → v8 migration; v7 → v8 only adds defaults (origin, empty template) and never rewrites settlement, road, shipment, or caravan identity or progress. Runtime scheduler cursors, the reconstructed road spatial index, region-candidate memo, fresh-chunk tracker, starter retry back-off, last-search diagnostics, and timing aggregates are deliberately not persisted. At server startup all persisted `PHYSICAL` shipments are normalized to `ABSTRACT`, remaining travel time is reconstructed from progress, and stale entity associations are cleared.

`NPCColonyData` distinguishes `PLAYER_PHYSICAL`, `NPC_PHYSICAL`, and `NPC_ABSTRACT`. The MineColonies numeric association is optional. Abstract NPC settlements have their own UUID, location, faction, population, economy, controller, and creation time without pretending that a Town Hall exists.

## Trade boundary

`TradeManager` schedules matching and shipment progression but delegates pair selection to pure `TradeMatcher` and all resource mutation to `EconomyManager`. `TradeLedger` owns persisted route/shipment collections and reconstructs reservations from `PLANNED` shipments after restart. Transient offers/demands never need NBT.

`TradeShipment`, never an entity, owns cargo in transit. `CaravanManager` can pause abstract time progression, project physical movement back into shipment progress, or safely return it to abstract travel. Arrival is committed only through `TradeManager.completePhysicalShipment`, which is terminal and idempotent.

`ShipmentTravelTimeEstimator` consumes the selected `ShipmentPath` only when a planned shipment departs. Direct paths use geometric length; road paths expose `sum(edgeLength / edgeSpeedMultiplier)`. Persisted in-transit durations are never recomputed.

## Growth boundary

`SettlementGrowthManager` is a separate low-frequency service; it does not replace the simulation, economy, trade, road, or world-settlement managers. It reads current `NPCColonyData` needs and `SettlementRecord` archetypes, stores only growth-owned state, and delegates plots and decisions to pure bounded components. Population remains authoritative in `NPCColonyData`. Global roads remain authoritative for inter-settlement corridors.

Logical construction advances from `PLANNED` to `READY` without chunks. `READY`, `GENERATING`, and `COMPLETED` buildings contribute derived effects. Runtime structure selection is restricted to installed compatible style-pack assets and then persisted. Autonomous physical work writes a building only when every footprint chunk (and its neighbourhood) is loaded and was generated during this server session, and then writes all slices together after a whole-building preflight; otherwise it waits or records `EXISTING_CHUNK_PROTECTED`. The explicit operator override may load the bounded footprint. Both refuse block entities before any write, skip entities/anchor blocks, persist each processed chunk, and never own chunk tickets.

## Structurize and threading boundary

`SettlementStructureService` scans installed MineColonies style packs on one `Kingdoms-Structure-Catalog` worker because `StructurePacks.getBlueprints`/`getBlueprint` call `waitUntilFinishedLoading()`. On an integrated client that barrier opens only after the client world ticks, so any server-thread call before the catalog is `READY` can deadlock world entry. All blueprint placement (`SettlementBuildingChunkGenerator`, the preflight, the operator override, and autonomous generation) therefore checks `READY` first and otherwise records `STRUCTURE_CATALOG_LOADING`. Placement uses Structurize's creative handler with fancy placement so solid/fluid substitution placeholders resolve to real ground instead of being written literally.

## Citizen boundary

`SettlementCitizenManager` reads `NPCColonyData.population`, completed growth buildings, and the persisted layout; it writes only the roster registry. Population, economy, needs, growth, and trade never read physical representatives. A `SettlementCitizenEntity` stores only settlement/representative IDs and a cosmetic texture key, is never saved to chunks, and discards itself when the manager no longer lists it. Spawning reads only entity-ticking chunks and never creates chunk tickets. Interaction goes through `SettlementCitizenInteractionService`, so Phase 7 can add handlers without touching the entity. See [Physical settlement population](CITIZENS.md).

## Contract and diplomacy boundary

Three domains stay separate, each with a single writer:
- player reputation: `ReputationService` → `ReputationRegistry`;
- faction relations: `DiplomacyService` → `Faction.relations`;
- contracts: `ContractService` → `ContractRegistry`.

`ContractService` performs every contract transition. Offers reserve nothing; acceptance reserves the agreed reward from the treasury (never more than the free balance); completion pays it out once; failure and cancellation return it. Delivery is one transaction through an `InventoryPort`: validate, remove items all or nothing, deposit into the stockpile, credit and complete, then apply reputation and issue the reward, each behind a persisted flag. A failure before completion rolls back and returns the items; a failed payout stays pending and is paid once later. `ContractManager` implements the port for real players; the contract board is an interaction handler, not entity code. `DiplomacyEvaluator` emits only first-contact and trade events for road/trade neighbours; `DefaultTradePermissionPolicy` reads the resulting stance. See [Reputation, contracts, and diplomacy](DIPLOMACY.md).

## Bandit boundary

`EncounterService` is the only writer of encounters and of the bandit effects on shipments. Resolution is one transaction:
1. Check that the encounter is open and the shipment is still in transit.
2. Record the loss once with `TradeShipment.recordBanditLoss`.
3. Record the result on the encounter.
4. Apply the consequences: road threat, contracts through `ContractService.onEncounterResolved`, and defender reputation through `ReputationService`.

Abstract rolls, physical victories, caravan overruns, and operator commands all call it, so there is no second path to a result, and a repeated call changes nothing.

`BanditManager` holds only runtime representation (`BanditRoster`: presences, entity index, timers). A `BanditEntity` carries association IDs and a rally point, calls back on hurt and death, and discards itself when the roster no longer lists it. Caravans consult `BanditManager.holds`/`onCaravanOverrun` and never read encounter internals. See [Bandits](BANDITS.md).

## Caravan boundary

`CaravanManager` does not match offers, reserve resources, withdraw cargo, or deposit cargo directly. `CaravanInstance` stores only representation state: caravan/shipment IDs, dimension, endpoints, position, spawned entity UUIDs, observer assignment, and timestamps. Each `CaravanMemberEntity` stores caravan/shipment association and role; it never stores resource type or amount.

The path abstraction chooses a multi-edge `RoadShipmentPath` when a graph route exists and a `DirectShipmentPath` otherwise. Local navigation uses bounded waypoints and loaded chunks only; it creates no chunk tickets. Missing entities and stuck navigation fall back to the authoritative abstract shipment. Deliberate leader/carrier destruction is different: it marks the shipment failed with `CARAVAN_DESTROYED` and loses the already-withdrawn cargo. The exception is a caravan held by an active bandit encounter: that encounter decides the loss (a 30–60% overrun), and the rest travels on abstractly.

## Controller boundary

`WorldSimulationManager` resolves each batched colony, selects `PlayerColonyController` or `AIColonyController`, and supplies a context containing only declared services. The player controller observes metrics/economy. The AI controller performs the same observation, evaluates needs, and records a strategic decision. It does not place buildings, create work orders, transfer items, or change MineColonies-owned state.

Storage is exposed through `ColonyResourceStorage`. The current adapter returns a complete physical snapshot only when every registered warehouse container is loaded and exposes an item handler. Otherwise it returns no observation, causing safe abstract progression instead of trusting partial inventory data.

## Performance invariants

- Strategic work runs only on the logical server.
- A deterministic round-robin queue processes at most the configured batch size and stops after a soft per-tick time budget.
- Mode distance checks happen only when a colony reaches its batch slot.
- Active/abstract radii use hysteresis to prevent boundary flapping.
- Profiling stores counts, a running average, and a maximum; it retains no unbounded samples.
- Exceptions are isolated per colony and logged without stopping the server tick.
- Region/road planning samples the generator only (no chunk access) on the single daemon `Kingdoms-Settlement-Planner` worker. A generator height query costs roughly 1-2 ms on a noise generator, so no region analysis runs on the server thread; results are committed on the server thread and are order-independent. Region candidates are memoized (bounded LRU).
- Lot/pad planning runs on the server thread but reads only loaded chunks whose 8 neighbours are loaded (cheap heightmaps that match the physical terrain). Unloaded columns are `UNLOADED_TERRAIN`, a transient result with a back-off, never a guess and never a permanent block.
- NeoForge chunk-load events are queued and processed from the server tick (at most 64 per tick), because `MinecraftServer.execute` runs inline on the server thread and the event can fire in the middle of synchronous chunk loading.
- Every search is hard-bounded: 96 lot positions, 64 full pads, 8 street extensions, 4096 A* nodes, 81+25 site samples per candidate, 2 site refinements per region.
- Physical settlement/road/street work is selected by footprint or chunk spatial index, validated for the whole building before any write, and idempotent by persisted chunk markers.
- Settlement representatives: one distance check per far settlement per cycle (every 20 ticks); rosters recomputed only on signature change; street graph ≤ 4096 nodes, cached per layout; route cache 256; safe-spawn search ≤ 729 candidates. Spawns per settlement per cycle and per-settlement/global/per-player caps bound all entity work.
- Bandits:
  - one threat evaluation per interval (default 1200 ticks), linear in eligible roads plus unassessed in-transit shipments, with the remote length of each road cached;
  - a 20-tick cycle linear in open encounters (≤ 16 natural) and physical presences;
  - spawn search ≤ 3 probes × 729 checks per missing bandit;
  - entity caps per encounter, per player, and server-wide;
  - no chunk tickets and no world scans.
- Contracts: offers are generated only when a player asks, at most once per refresh interval per settlement; open contracts are bounded by offers per settlement and active contracts per player; closed ones by retention and a count cap; the sweep runs every 200 ticks. Diplomacy: one evaluation per Minecraft day over road/trade neighbours (0.2–0.8 ms for 5 pairs in the smoke test).
