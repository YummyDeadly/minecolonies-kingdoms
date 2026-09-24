# Autonomous settlement growth

## Scope and ownership

Phase 6 grows only Kingdoms procedural settlements backed by `NPCColonyData.kind == NPC_ABSTRACT`. It never places procedural buildings in `PLAYER_PHYSICAL` or `NPC_PHYSICAL` MineColonies colonies. `SettlementRecord` remains authoritative for stable location/archetype, and `NPCColonyData` remains authoritative for population, capacities, needs, and economy. `GrowthRegistry` owns only growth state and procedural building records.

There are four stages: `STARTER`, `GROWING`, `ESTABLISHED`, and `PROSPEROUS`. Stages are derived deterministically from logically active buildings and population; they never mutate `SettlementType`. Re-evaluation is idempotent and stage transitions grant no one-time stockpile reward.

## Building vocabulary, style packs, and effects

The data-driven `SettlementBuildingType` definitions are `HOUSE`, `FARM`, `STOREHOUSE`, `LUMBER_YARD`, `QUARRY`, `SMITHY`, `MARKET`, and `CIVIC`. Each definition declares its strategic role, housing/storage contributions, production contributions, and allowed settlement archetypes.

At server start, Kingdoms requests an asynchronous scan of the installed MineColonies structure packs through public Structurize APIs. `SettlementStructureService` exposes `UNINITIALIZED`, `LOADING`, `READY`, and `FAILED`; the potentially blocking Structurize barrier is awaited only on one bounded `Kingdoms-Structure-Catalog` worker. Server startup and integrated-world creation therefore continue while packs load. A completed immutable catalog is published atomically, duplicate initialization requests share the same scan, and shutdown cancels/joins the worker.

The catalog contains compatible level-one blueprints, their style family, dimensions, anchor, and entrance metadata. Selection is deterministic for settlement UUID, building type, sequence, and persisted style family. The chosen pack/path, rotation/mirror transform, anchor, entrance, and transformed footprint are stored with the building, so a restart or later pack-order change cannot silently reroll an existing plan. Growth that needs a structure while the service is not ready records `STRUCTURE_CATALOG_LOADING` or `STRUCTURE_CATALOG_FAILED`. Missing runtime assets remain explicit blockers; there is no procedural shell fallback.

Effects are derived from buildings in `READY`, `GENERATING`, or `COMPLETED` state. Houses add housing, storehouses/markets/civic buildings add storage, and production buildings add resource flow. `SettlementGrowthState` stores the last applied modifier; reconciliation applies only the delta, so repeated evaluation and restart cannot duplicate the bonus. Stockpile mutations remain exclusively in `EconomyManager`. Phase 6 has no construction resource charge or reservation, so no construction-spending transaction exists to double-apply.

## Decisions and logical lifecycle

The evaluator consumes the existing needs/economy model. Deterministic priority maps housing to `HOUSE`, storage to `STOREHOUSE`, FOOD to `FARM`, WOOD to `LUMBER_YARD`, STONE to `QUARRY`, and IRON/TOOLS pressure to `SMITHY`. With no shortage, it fills a stable civic foundation order and allows `MARKET` only for compatible archetypes.

Buildings use `PLANNED -> READY -> GENERATING -> COMPLETED`, plus terminal diagnostic `BLOCKED`/`FAILED`. `PLANNED -> READY` is logical and time-based; it does not require loaded chunks. A settlement can therefore advance strategically while its land is unloaded. Only one pending logical plan is allowed, and stable IDs derive from settlement UUID, sequence, type, and generation version.

## Deterministic layout, lot search, and terrain shaping (Phase 6.6)

`SettlementLayoutPlanner` owns a persistent style family, template id, plaza, gate street, local street network, and building lots. The same planner places starter slots and growth buildings. The search is hierarchical and every level is hard-bounded:

1. **Positions** – rings around the anchor (6-block steps, 8-24 angles per ring, a stable per-building angular offset), at most 96 positions. Starter slots search their template distance band; growth searches outside the starter footprint up to `maximumExpansionRadius`.
2. **Prefilter** – five samples (centre and the four corners of the largest candidate blueprint) reject water, unloaded terrain, slopes, and cliffs before any full-footprint work.
3. **Reservations** – plaza, gate, a 32x11 gate-approach corridor, the starter footprint (growth only), existing lots (3-block spacing), local streets, and nearby global-road points.
4. **Full pads** – at most 64 full-footprint terrain-shaping plans over the real transformed blueprint footprint, trying the two rotations whose entrance faces the street network first.
5. **Street extension** – bounded A* (4096 nodes) from the building frontage to the nearest street points for at most 8 non-overlapping pads. The best pad is chosen by street length, distance to the network, and terrain work.

This is "find a buildable pad, then a bounded street to it", so a lot never depends on a pre-existing frontage. Recessed doors (MineColonies porches and arcades, whose entrance lies inside the footprint) start their street at the first column outside the footprint, so a street never paves a building floor.

`TerrainShapingPlanner` chooses one deterministic target height per building that minimises cut + fill within `maximumCutDepth`, `maximumFillDepth`, `maximumPadHeightVariance`, and a per-footprint terrain-work cap. Pads are `NATURAL`, `CUT`, `FILL`, `CUT_AND_FILL`, or `TERRACE` (a shaped pad at least three blocks above or below the plaza). Exposed fill edges of two or more blocks get cobblestone retaining faces up to `maximumRetainingWallHeight`; a one-block step stays a natural grass step. Every shaped column keeps its original top block (grass, sand, podzol, stone, ...), so cut or filled ground outside the blueprint does not leave bare dirt or stone scars. Lava exposed by cutting (hidden vanilla springs) inside the footprint plus a 2-block margin is sealed with cobblestone before it can flow. Each building has its own level, so a moderate hill becomes several terraces joined by stepped streets rather than one flattened platform. Ravines and cliffs are rejected rather than filled.

Local streets are persisted typed points (`GROUND`, `GRADED`, `STAIRS`, `BRIDGE`) with at most one block of height change per step and at most `maximumTerrainAdjustment` from natural ground. Bridges are limited to `maximumBridgeSpan` water columns. The street A* adds a turn penalty, so connections are built from straight runs and L-bends instead of one-block zig-zag staircases. Physically a street is a ribbon perpendicular to its direction, paved mostly with dirt path, and the sides mix coarse dirt, gravel, and packed mud. Steps get a cobblestone stair in the lower column facing uphill. At most 3 blocks of natural terrain are cut for headroom and at most 4 blocks of support are filled below. Standing tree trunks are removed whole, plants are cleared, fluids and block entities are never touched, and bridge decks get railings. The plaza is a mixed andesite/stone-brick/cobblestone square with a stone-brick border and lantern posts in its corners, and the gate has stone-brick posts with lanterns. Trees in pads and streets are removed whole (logs with block updates, so leaves decay naturally), and a street column whose natural ground rises more than 5 blocks above the walking level is left natural instead of leaving a floating shelf. Each (street segment, chunk) slice is written once and marked. Local streets never enter the global `RoadNetwork`.

Terrain planning and the physical recheck share one definition of ground: air, logs, leaves, replaceable plants, and non-solid plants (flowers, sugar cane, bushes) are not ground. Heights always mean "first free Y above ground", matching the generator's `getBaseHeight`.

## Diagnostics

Every search records positions, structures, rotations, full pads, accepted pads, terrain work, street extensions, duration, and rejection counts (`WATER`, `DISALLOWED_BIOME`, `SLOPE`, `CLIFF`, `ROUGHNESS`, `EXCESSIVE_CUT`, `EXCESSIVE_FILL`, `EXCESSIVE_TERRAIN_VOLUME`, `RETAINING_WALL`, `SAMPLE_LIMIT`, `UNSUPPORTED_FOOTPRINT`, `STARTER_EXCLUSION`, `GATE_APPROACH`, `BUILDING_COLLISION`, `GLOBAL_ROAD`, `LOCAL_STREET_COLLISION`, `STREET_UNREACHABLE`, `OUTSIDE_RADIUS`, `SEARCH_BUDGET`, `UNLOADED_TERRAIN`, `NO_STRUCTURE`, `NO_VALID_ROTATION`). A failed growth search reports `NO_VALID_LOT:<TYPE>[<summary>]` instead of the Phase 6.5 `no connected full-footprint lot`. Physical refusals are `BLOCK_ENTITY: ...`, `EXCESSIVE_CUT: physical cut N at x,z ...`, or `EXCESSIVE_FILL: ...`.

`/kingdoms growth layout <settlement-uuid>` prints the site analysis, starter status/planner version/blocker, cumulative and last-search diagnostics, every street (purpose, width, point kinds, endpoints), and every lot with its building type/status and pad (mode, target Y, cut/fill volume, maximum depths, retaining edges). It also works for a blocked starter that has no layout.

## Physical construction and existing-world safety

Blueprint placement is Kingdoms-owned, chunk-local, bounded, and server-thread-only. Structurize supplies the public blueprint and placement primitives; fancy placement resolves Structurize substitution placeholders to real ground. Before any block of a building is written, a preflight checks every pending slice for existing block entities and for physical terrain that drifted more than 2 blocks from the plan. A refusal marks the building `BLOCKED` with the reason and writes nothing. Entities and `IAnchorBlock` blocks are not placed. Per-building chunk markers survive restart; repeating a completed building is a no-op.

Autonomous work follows the ownership rule in [world settlements](SETTLEMENTS.md#physical-generation-and-existing-world-safety): whole buildings only, in chunks generated during this session, with loaded neighbourhoods. Blueprint placement is never attempted before the structure catalog is `READY`, because Structurize's blueprint accessor blocks on its loading barrier.

Operator overrides:

- `/kingdoms growth materialize <building-uuid>` writes one building (retrying a `BLOCKED` building once) and paves the settlement's pending streets;
- `/kingdoms growth materialize <settlement-uuid>` and `/kingdoms settlement materialize <settlement-uuid>` write every pending building and street;
- `/kingdoms growth grow <settlement-uuid> [1-8]` repeats evaluate + materialize to add up to eight growth buildings.

Lot planning reads loaded terrain only, so growth commands should be run while standing in or near the settlement. Otherwise the search reports `UNLOADED_TERRAIN` and plans nothing.

## Population

Population checks use a separate low-frequency interval. Growth requires food at or above reserve, non-negative food flow, free housing, and no critical need. It adds at most one aggregate citizen per eligible check. The effective cap is the minimum of the configured hard cap and the settlement archetype maximum plus a small stage allowance. No fake citizens, deaths, or migration are modeled.

Completed growth buildings also define the roles, workplaces, and homes of the settlement's physical representatives (see [Physical settlement population](CITIZENS.md)); representatives never feed back into population or growth.

## Persistence and performance

SavedData schema 8 stores growth states (including starter status, starter planner version, and starter diagnostics), structure identities/transforms, building origin, terrain-shaping pads, exact footprints/entrances, layout styles/templates, typed streets, street chunk markers, lots, statuses, timestamps, blockers, applied modifiers, sequence counters, generation versions, and chunk markers. Migration v6 -> v7 preserved legacy building records; v7 -> v8 adds `origin` (`LEGACY` for procedural records, `GROWTH` for blueprint records) and an empty template id without altering settlement, road, shipment, or caravan identity or progress.

The manager evaluates at most `maxSettlementsPerCycle` using a rotating sorted-UUID cursor. Lot searches are hard-bounded as described above and read only loaded heightmaps; a starter district plan measured 30-200 ms on the dedicated test server. Statistics retain only cumulative counts, average duration, and maximum duration; no unbounded samples are stored.

## Configuration

The `growth` server section contains:

- `enabled`
- `evaluationIntervalTicks`
- `maxSettlementsPerCycle`
- `maximumBuildingsPerSettlement`
- `maximumExpansionRadius`
- `populationIntervalTicks`
- `populationHardCap`

Existing-world physical ownership remains controlled by `settlement.modifyExistingChunks`.

## Commands

```text
/kingdoms growth list
/kingdoms growth info <settlement-uuid>
/kingdoms growth evaluate <settlement-uuid>
/kingdoms growth tick
/kingdoms growth materialize <building-uuid|settlement-uuid>
/kingdoms growth grow <settlement-uuid> [count]
/kingdoms growth catalog
/kingdoms growth layout <settlement-uuid>
/kingdoms growth stats
```

All growth commands require permission level 2. `settlement info` also reports stage, current authoritative population, building counts, and last decision/blocker.

## Known limitations

Logical construction uses a fixed evaluation-delay model rather than MineColonies builders, work orders, or itemized costs. Growth answers the pressing need first, but a type is saturated at `2 + buildings/4`. A saturated need, or a role for which the settlement's style has no blueprint, falls through to missing foundation roles and then to the least represented role available in the style, so a structural food deficit no longer produces a row of farms. Only compatible level-one blueprints in packs installed at runtime are cataloged; this phase does not upgrade a placed building through its MineColonies level chain. Player-block ownership cannot be proven from vanilla block state alone, so automatic changes remain new-chunk-only by default and old chunks require explicit operator action. Camera-level appearance, collision, and walkability remain manual client checks when no graphical client is available.
