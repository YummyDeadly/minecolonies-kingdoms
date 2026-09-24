# Roadmap

## Phase 0 — Research and addon foundation

- [x] Confirm Minecraft, NeoForge, Java, and MineColonies versions.
- [x] Inspect public colony, citizen, building, work-order, warehouse, claim, event, and abandonment APIs.
- [x] Create a standalone addon project with a required MineColonies dependency.
- [x] Add architecture and integration documentation.
- [x] Avoid Mixins and MineColonies source changes.

## Phase 1 — Core strategic model

- [x] `Faction`, `FactionType`, `Kingdom`, `NPCColonyData`, and controller boundary.
- [x] Global server-side `SavedData`, versioning, and v1-to-v2 migration.
- [x] Existing-colony reconciliation and public lifecycle hooks.
- [x] Dedicated-server baseline and post-change smoke tests.
- [x] `WorldSimulationManager`, round-robin batches, timing budget, and profiling.
- [x] Active/abstract mode with activation/deactivation hysteresis.
- [x] Separate player and AI controller implementations.
- [x] Simulation/colony diagnostic commands and server configuration.

## Phase 2 — Economy and needs foundation

- [x] Resource vocabulary for FOOD, WOOD, STONE, IRON, and TOOLS.
- [x] Production, consumption, reserve, surplus, and deficit calculations.
- [x] Physical warehouse observation with abstract fallback/progression.
- [x] Needs for resources, housing, storage, and labor.
- [x] Extensible strategic decisions that record intent without executing construction.
- [x] Economy, need, decision, scheduler, round-trip, and migration tests.

## Phase 3 — Two autonomous colonies

- [x] Strategic NPC colonies independent of physical MineColonies creation.
- [x] Optional MineColonies association and explicit player/NPC physical/abstract kinds.
- [x] Deterministic offers, demands, permission policy, and distance-limited matching.
- [x] Unique persistent routes with pause/reactivation hysteresis.
- [x] Planned reservations and departure-owned in-transit cargo.
- [x] Restart-safe delivery and colony-deletion failure/refund handling.
- [x] SavedData schema v3, trade diagnostics, tests, and dedicated runtime scenario.

## Phase 4 — Physical caravan representation

- [x] `ABSTRACT`/`PHYSICAL` representation on the authoritative shipment.
- [x] Four-member caravan group: leader, carrier, and two guards.
- [x] Direct path interpolation/projection and bounded local waypoint navigation.
- [x] Near-player materialization with hysteresis, global/per-player caps, and nearest-first ordering.
- [x] Safe dematerialization, stuck recovery, destruction loss semantics, and exactly-once arrival.
- [x] Restart normalization, orphan cleanup, schema v4 migration, commands, statistics, and tests.

## Phase 5 — World settlements and global roads

- [x] Seed/dimension/region-stable settlement candidates with bounded terrain sampling.
- [x] Order-independent minimum distance, stable UUID/name/type/orientation/gate, and simulation linkage.
- [x] Procedural starter settlements with new-chunk protection and persisted idempotency markers.
- [x] Persistent sparse road graph with degree/distance limits and deterministic edge identifiers.
- [x] Bounded coarse terrain paths, compact polylines, chunk spatial index, palettes, and small bridges.
- [x] Multi-edge shortest routes, `RoadShipmentPath`, and direct-path fallback in `CaravanManager`.
- [x] SavedData schema v5 migration, commands, metrics, unit tests, and operator documentation.

## Phase 6 — Autonomous procedural settlement growth

- [x] Persisted deterministic growth stages, need-driven building decisions, and stable building identifiers.
- [x] Bounded terrain/road-aware plot planning with starter-center, gate, wall, building, and global-road exclusions.
- [x] Logical construction independent of chunk loading plus chunk-local, restart-safe physical generation markers.
- [x] Derived housing, storage, and production effects without duplicate application.
- [x] Conservative capped population growth for `NPC_ABSTRACT` settlements only.
- [x] Road-aware ETA for newly departing shipments using per-edge speed multipliers.
- [x] SavedData schema v6 migration, commands, statistics, tests, and operator documentation.

## Phase 6.6 — Settlement templates and terrain shaping

- [x] Area-based site feasibility (coarse broad phase, 9x9 area grid, connected buildable component, water/elevation/step/gate approach, terrain-work estimate) with per-archetype requirements and bounded re-centring.
- [x] Background (off-server-thread) region and road planning with generator-only sampling and server-thread commit; bounded region-candidate memo.
- [x] Starter districts from Kingdoms-owned templates and 2-4 real, style-coherent MineColonies blueprints; plaza, gate street, gate posts; explicit blockers and no procedural fallback.
- [x] Bounded per-building terrain pads (natural/cut/fill/cut-and-fill/terrace, retaining edges) with a whole-building physical preflight and tolerance.
- [x] Hierarchical, hard-bounded lot search (positions → prefilter → reservations → full pads → street extension) with gate-approach, plaza, street, lot, and global-road reservations and recessed-door support.
- [x] Grade-aware local streets (stairs, bounded cut/support, bridges, trunk/plant clearing, chunk markers).
- [x] Actionable diagnostics (`growth layout`, `settlement info`, `settlement site`, `settlement column`, `NO_VALID_LOT[...]`).
- [x] Existing-world safety: queued chunk events, session-new chunk ownership, whole-building autonomous writes, block-entity refusal before any write.
- [x] SavedData schema 8 with v7 → v8 migration; exhaustive unit tests; dedicated-server smoke and integrated-client startup regression.
- [x] Manual graphical acceptance of settlement appearance: after two feedback rounds the user reported the roads as "perfect" and the generation as good (see [SMOKE_TEST.md](SMOKE_TEST.md)).

## Phase 6.7 — Physical settlement population

- [x] Bounded near-player representatives of `NPC_ABSTRACT` populations; `NPCColonyData.population` stays authoritative and is never changed by representation, death, or loss.
- [x] Deterministic persisted roster (roles from completed buildings, homes in houses only, stable IDs/names/appearance), reconciled on population/building changes.
- [x] Budget `round(2 + 1.5·√population)` with per-settlement/global/per-player caps, gradual spawning, hysteresis, loaded (entity-ticking) chunks only, no chunk tickets.
- [x] Safe spawn (floor, headroom, no fluids/hazards, outside footprints), street-graph routes with sparse waypoints, stuck recovery.
- [x] Simple day schedule (commute, work, market/plaza, home at night; guards at the gate).
- [x] Right-click interaction through `SettlementCitizenInteractionService` handlers (Phase 7 hook; no diplomacy logic).
- [x] Death/unload/restart safety (no population change, cooldown, orphan cleanup, no duplicates), schema 9 with v8 → v9 migration.
- [x] Kingdoms-owned renderer using installed MineColonies citizen textures at runtime (no copies), with fallbacks.
- [x] `/kingdoms citizen` commands and profiling; unit tests; dedicated-server smoke; integrated-client startup and proximity check.
- [x] Manual graphical acceptance of representatives: the user reported them fine overall; the two broken textures (female merchant/official dress-model textures) were fixed afterwards (see [SMOKE_TEST.md](SMOKE_TEST.md)).

## Phase 7 — Reputation, contracts, and diplomacy

- [x] Three separate authoritative domains with single writers: player reputation (`ReputationRegistry`), faction relations (`Faction.relations` via `DiplomacyService`), contracts (`ContractRegistry` via `ContractService`); the ambiguous Phase 1 `Faction.reputation` removed.
- [x] Explicit contract lifecycle (OFFERED, ACCEPTED, COMPLETED, FAILED, EXPIRED, CANCELLED) with stable UUIDs and frozen objectives.
- [x] Treasury reservation at acceptance; no overcommitment; failure and cancellation return value.
- [x] Delivery as one transaction with rollback, and exactly-once reputation and reward, also across restarts (pending rewards are paid once later).
- [x] Bounded, deduplicated generation: interval, open-contract cap, one per resource, cooldowns, expiry, fundability.
- [x] Per-player, audited reputation (tiers, spillover, kill penalty).
- [x] Relations changed only by audited events (first contact, trade, operator); no drift or competition effects; trade gated by stance.
- [x] Contract board in the official/merchant dialogue (presentation only) plus full command fallback.
- [x] Schema 11 with v9 → v10 → v11 migration (including conversion of the preliminary Phase 7 saves); tests; dedicated-server smoke; scripted integrated-client flow.
- [ ] Manual in-game acceptance of the dialogue/contract experience (delegated to the user; see the Phase 7 checklist in [SMOKE_TEST.md](SMOKE_TEST.md)).

## Phase 8 — Bandits, threats, and caravan ambushes

- [x] Persistent, smoothed road threat with transparent contributors, suppression, and cooldowns (no per-block state).
- [x] Explicit encounter lifecycle (PLANNED, ACTIVE, RESOLVED_*, EXPIRED, CANCELLED) with stable UUIDs, seeds, strength, and the exact persisted cargo result; representation separate from status.
- [x] One persisted seeded assessment per shipment; ambush points on road corridors, outside settlement/colony exclusion zones, and away from bridges; roadblocks on very dangerous roads.
- [x] Deterministic abstract resolution and a single resolution transaction (`EncounterService`); exactly-once cargo loss (`TradeShipment.recordBanditLoss`), with delivery and refunds using the deliverable amount.
- [x] Physical bandits:
  - Kingdoms-owned entity, near players only, in entity-ticking chunks only;
  - per-encounter, per-player, and global caps, with hysteresis;
  - safe outdoor spawns;
  - orphan cleanup, stuck and stall recovery, and none on peaceful.
- [x] Caravan integration (hold, guards, overrun as partial loss) and player intervention with one-time defender reputation.
- [x] ESCORT_CARAVAN and CLEAR_BANDITS contracts from real encounters through the Phase 7 contract service; guard threat reports.
- [x] Config `[bandits]`, `/kingdoms bandit ...` diagnostics, schema 12 with v11 → v12 migration, tests, dedicated-server smoke, and a scripted integrated-client check.
- [-] Bandit camps as world structures (deferred to Phase 8.1; roadblocks provide local presence).
- [ ] Manual graphical acceptance (delegated to the user; see the Phase 8 checklist in [SMOKE_TEST.md](SMOKE_TEST.md)).

## Later phases

8.1. Optional bandit camps (chunk-safe structures tied to roadblocks), if wanted.
9. Abstract armies and local materialization.
10. Goal-driven wars and territorial consequences.
11. Systemic world events, migration, abandonment history, and bounded world history.

Normal MineColonies owner-free work-order construction remains blocked on a suitable public API and is not the next assumed implementation step. Wars, armies, outposts, world events, and production GUI remain outside Phases 6–8. Phase 7 started after Phase 6.6 settlement quality and Phase 6.7 representatives were accepted by the user.
