# Simulation and economy

## Scheduling

`WorldSimulationManager` is called from the NeoForge post-server-tick event. At the start of a strategic cycle it snapshots tracked colony UUIDs into a deterministic, sorted round-robin queue. Each server tick processes at most `batchSize` colonies and stops when the soft `maxWorkNanosPerTick` budget is reached. Additions or deletions are resolved safely when their queued UUID is processed; the next cycle refreshes the set.

The default strategic cycle is 600 ticks, while an individual economy update is limited to once per 1,000 ticks. Runtime counters expose processed colonies/batches, pending work, average and maximum colony duration, economy updates, and AI evaluations.

Trade has independent low-frequency clocks. Matching runs at `trade.matchIntervalTicks`; shipment lifecycle checks run at `trade.shipmentIntervalTicks`. Neither performs MineColonies API access or chunk loading. Matching is deterministic and runs only over eligible abstract NPC colonies.

Physical caravan checks run independently at `caravan.updateIntervalTicks`. Candidates are sorted nearest-player-first, constrained by global and per-player caps, and use separate materialization/dematerialization radii. Physical navigation only uses already-loaded chunks; abstract shipment progression remains the fallback.

World settlement planning is event-driven rather than part of the strategic tick queue. A newly observed region is planned once, using bounded generator-only terrain probes. Physical generation is chunk-local and normally runs only for chunks identified by NeoForge as new. Worldgen counters report region/settlement/road totals plus average and maximum planning time.

Settlement growth has its own low-frequency cadence and rotating UUID-sorted cursor. One cycle evaluates at most `growth.maxSettlementsPerCycle`; plot searches inspect at most 48 deterministic candidates. It never requests chunks. Logical construction, derived effects, stages, and population can advance while the area is unloaded; physical generation remains event-driven.

## Active and abstract modes

A colony becomes `ACTIVE` when a non-spectator player in its dimension is within `activeRadius` (default 256 blocks). It becomes `ABSTRACT` only after all such players are beyond `deactivationRadius` (default 320). The gap is hysteresis; the effective outer radius is always at least 16 blocks larger than the inner radius.

Active colonies refresh live metrics and attempt a complete physical warehouse observation. If that observation cannot be proven complete, they safely use the abstract path. Abstract colonies never force chunks to load and advance only aggregate resource flows.

Strategic `NPC_ABSTRACT` colonies remain abstract even when a player stands nearby because they have no physical MineColonies representation to activate.

## Economy formulas

For each resource:

```text
net per day       = production per day - consumption per day
surplus per day   = max(net per day, 0)
deficit per day   = max(-net per day, 0)
reserve shortage  = max(desired reserve - stockpile, 0)
exportable stock  = max(stockpile - desired reserve, 0)
abstract delta    = round(net per day * elapsed ticks / 24000)
new stockpile     = max(old stockpile + abstract delta, 0)
```

Baseline reserve and consumption targets are derived from population/workers. Existing higher reserve targets and explicitly non-zero consumption rates are preserved. Active physical reads replace stockpile amounts only; strategic flow rates and reserve targets remain Kingdoms-owned.

## Needs and decisions

Resource needs compare stockpile against desired reserve. Housing targets population plus 10% headroom, storage targets two units per citizen, and labor targets workers equal to 60% of population. Persistent needs keep their original creation time. Severity is based on shortage ratio: low below 25%, medium from 25%, high from 50%, and critical from 75%.

The base AI selects one deterministic highest-priority need and records an intent. High food shortage, for example, produces `PRODUCE_MORE_FOOD`; critical resource shortage records `IMPORT_RESOURCE`. Housing/storage produce expansion intents. For procedural settlements, the separate growth evaluator consumes the same need list and maps it to a bounded building plan; it does not invoke MineColonies construction.

Trade consumes the same economy state as a decoupled offer/demand projection. `IMPORT_RESOURCE` increases matching priority, but reserve shortage and deficit alone are sufficient to generate demand. See [Abstract trade](TRADE.md) for ownership and reservation rules.

## Server configuration

All settings live under `simulation` in the generated server config:

- `enabled`
- `batchSize`
- `strategicIntervalTicks`
- `economyIntervalTicks`
- `maxWorkNanosPerTick`
- `activeRadius`
- `deactivationRadius`

The separate `caravan` section contains enablement, inner/outer radii, global/per-player caps, update cadence, local waypoint distance, stuck timeout, retry cadence, and debug-name control. See [Physical caravans](CARAVANS.md).

The `settlement` and `roads` sections control deterministic world planning and physical generation. See [World settlements](SETTLEMENTS.md) and [Global roads](ROADS.md).

The separate `growth` section contains `enabled`, `evaluationIntervalTicks`, `maxSettlementsPerCycle`, `maximumBuildingsPerSettlement`, `maximumExpansionRadius`, `populationIntervalTicks`, and `populationHardCap`. See [Autonomous settlement growth](GROWTH.md).

The `citizens` section bounds the physical representatives of `NPC_ABSTRACT` populations (radii, caps, cadence, cooldowns). See [Physical settlement population](CITIZENS.md).

The `contracts` and `diplomacy` sections configure player contracts, reputation penalties, and relations between NPC factions. See [Reputation, contracts, and diplomacy](DIPLOMACY.md).
