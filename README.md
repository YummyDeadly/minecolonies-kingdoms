# MineColonies: Kingdoms

MineColonies: Kingdoms is a server-authoritative NeoForge addon that adds a persistent strategic layer above MineColonies. MineColonies remains authoritative for colonies, citizens, buildings, jobs, construction, requests, warehouses, couriers, guards, and claims.

The current Phase 1–8.1 foundation provides:

- persistent factions, kingdoms, strategic colony records, economy, needs, and decisions;
- public-API MineColonies lifecycle synchronization and warehouse observation;
- a bounded round-robin world simulation with active/abstract modes and hysteresis;
- separate player and AI controllers (AI currently records intent and never orders construction);
- configurable tick intervals, batch size, time budget, and activation radii;
- sequential save-schema migration from version 1 through version 13;
- commands and cumulative timings for diagnostics.
- strategic NPC colonies that do not require a physical Town Hall;
- deterministic offer/demand matching, persistent routes, and restart-safe abstract shipments;
- centralized reservation, departure, delivery, and failure accounting;
- near-player physical caravans backed by the existing shipment, with four visible members, caps, hysteresis, stuck recovery, and restart normalization.
- seed-stable logical settlements with bounded terrain scoring, starter physical generation, and existing-world protection;
- a persistent sparse road graph with exact typed geometry (`GROUND`, `GRADED`, `BRIDGE`), bounded bridge validation, chunk-local refinement, and explicit `UNROUTABLE` edges when no safe physical route exists;
- deterministic low-frequency growth for `NPC_ABSTRACT` settlements using compatible blueprints from installed MineColonies style packs, persisted streets/lots/building transforms, conservative population growth, and safe chunk-local materialization;
- road-aware ETA for newly departing shipments using every routed edge's speed multiplier without changing cargo ownership;
- Phase 6.6 settlement quality: area-based site feasibility, starter districts built from 2–4 real MineColonies style-pack blueprints of one style, a plaza and gate street, bounded per-building terrain pads (cut/fill/terrace with retaining edges), terraced multi-level towns, hierarchical bounded lot search with street extensions, grade-aware local streets, explicit actionable blockers instead of fallbacks, and background (off-server-thread) region/road planning.
- Phase 6.7 physical settlement population: bounded, near-player representatives of `NPC_ABSTRACT` populations with a deterministic persisted roster (roles from completed buildings, homes in houses, stable names), a simple daily schedule along the local streets, safe spawning in loaded chunks only, hysteresis and per-settlement/global/per-player caps, and right-click interaction through a handler service. Population stays authoritative in `NPCColonyData`; representatives never change it.
- Phase 7 reputation, contracts, and diplomacy: per-player reputation with each faction (a separate, audited registry); delivery contracts generated from settlement shortages with an explicit lifecycle, frozen objectives, treasury reservation at acceptance, and an exactly-once delivery transaction, accepted and delivered via clickable dialogue with officials and merchants or via commands; relations between neighbouring NPC factions that change only through audited events (first contact, trade, operator) and gate trade.
- Phase 8 bandits, threats, and caravan ambushes:
  - a smoothed, explainable threat per trade road (traffic, remoteness, raid momentum, settlement security, suppression);
  - one persisted, seeded ambush decision per shipment and deterministic abstract outcomes (escape, delay, partial loss, rare total loss) applied to the cargo exactly once;
  - roadblocks on very dangerous roads;
  - near-player physical bandits with caps, hysteresis, safe outdoor spawns in loaded chunks only, orphan cleanup, and stuck recovery;
  - player intervention with one-time defender reputation;
  - escort and clear-the-road contracts built on the Phase 7 contract service.
- Phase 8.1 bandit camps: roads that stay dangerous get a small camp beside them (deterministic, capped, with cooldowns); an active camp raises its road's threat; clearing it suppresses the road and pays a clear-the-camp contract exactly once; the physical camp is placed only on wild, owned land and taken down again after the camp ends.

No Mixins, reflection, or MineColonies source changes are used.

## Supported baseline

- Minecraft 1.21.1
- NeoForge 21.1.80+
- Java 21
- MineColonies `1.1.1396-1.21.1-snapshot`

## Build and test

On Windows with Java 21 selected:

```powershell
./gradlew.bat build
./gradlew.bat runServer
```

The jar is produced in `build/libs/`. Server configuration is generated as `config/minecolonies_kingdoms-server.toml` and includes simulation enablement, intervals, batch/time limits, and activation radii.

## Commands

- `/kingdoms factions`
- `/kingdoms colonies`
- `/kingdoms colony <minecolonies-id>`
- `/kingdoms colony create-npc <name>` and `/kingdoms colony delete <uuid>` (operator)
- `/kingdoms economy <minecolonies-id>`
- `/kingdoms economy set ...` development controls (operator)
- `/kingdoms needs <minecolonies-id>`
- `/kingdoms trade routes|shipments|match|tick|stats` (operator)
- `/kingdoms caravan list|info|materialize|dematerialize|teleport-near|stats` (operator)
- `/kingdoms settlement list|info|locate|region|plan-region|materialize|site|column|stats` (operator)
- `/kingdoms road list|info|nearby|route|connect|regenerate-segment|sample` (operator)
- `/kingdoms growth list|info|evaluate|tick|materialize|grow|catalog|layout|stats` (operator)
- `/kingdoms citizen stats|list|info|materialize|dematerialize` (operator)
- `/kingdoms reputation`, `/kingdoms contract list|offers|accept|deliver|abandon` (players)
- `/kingdoms reputation of|history|set`, `/kingdoms contract all|refresh|cancel|stats`, `/kingdoms diplomacy list|info|events|evaluate|set` (operator)
- `/kingdoms bandit stats|list|info|threat|evaluate|spawn-test|set-threat|materialize|dematerialize|resolve` (operator)
- `/kingdoms camp list|info|spawn-test|build|remove` (operator)
- `/kingdoms simulation` and `/kingdoms simulation stats` (operator)
- `/kingdoms debug` (operator)

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Simulation and economy](docs/SIMULATION.md)
- [Abstract trade](docs/TRADE.md)
- [Physical caravans](docs/CARAVANS.md)
- [World settlements](docs/SETTLEMENTS.md)
- [Global roads](docs/ROADS.md)
- [Autonomous settlement growth](docs/GROWTH.md)
- [Physical settlement population](docs/CITIZENS.md)
- [Reputation, contracts, and diplomacy](docs/DIPLOMACY.md)
- [Bandits, threats, and caravan ambushes](docs/BANDITS.md)
- [Bandit camps](docs/BANDIT_CAMPS.md)
- [MineColonies integration](docs/MINECOLONIES_INTEGRATION.md)
- [Dedicated-server smoke test](docs/SMOKE_TEST.md)
- [Roadmap](docs/ROADMAP.md)
