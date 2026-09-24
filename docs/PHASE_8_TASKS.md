# Phase 8 — Bandits, threats & caravan ambushes: task checklist

Status legend: `[x]` done, `[ ]` open, `[-]` deliberately deferred (with reason).

## 1. Audit and plan

- [x] Baseline: 142 tests green before any change.
- [x] Audit caravan/shipment authority. Cargo is withdrawn at departure and deposited at delivery (`TradeManager.resolveInTransit`, `completePhysicalShipment`). `CaravanManager.onCriticalMemberDestroyed` fails the whole shipment on any leader/carrier death, which conflicts with encounter authority.
- [x] Audit road routing: `RoadShipmentPath` chains road polylines; geometry points are typed (GROUND/GRADED/BRIDGE); routes use physical, routable roads only.
- [x] Audit contracts, reputation, relations (Phase 7), citizens, entity registration, config, commands, and schema 11.
- [x] No `CLAUDE.md` existed; created one from the stated requirements.
- [x] Architecture plan reported.

## 2. Domain and persistence

- [x] Road threat records: one `RoadThreat` per road, smoothed (at most `threatStep` per evaluation), transparent contributors, suppression, cooldown (`RoadThreat`, `ThreatRules`).
- [x] Bandit encounters: AMBUSH/ROADBLOCK, explicit lifecycle, representation separate from status, seed, remaining strength, persisted outcome and cargo result (`BanditEncounter`).
- [x] Shipment assessments: one persisted decision per shipment (`BanditRegistry.assessed`).
- [x] `TradeShipment`: exactly-once bandit loss (`lostAmount`, encounter ID), holds and delays, `deliverableAmount()` used by every delivery and refund path.
- [x] Schema 12 with v11 → v12 migration (`SchemaV12MigrationTest`).

## 3. Behaviour

- [x] Threat evaluation, bounded to the road count plus unassessed in-transit shipments per interval (`ThreatEvaluator`, `BanditManager.evaluate`).
- [x] Encounter planning on road corridors, outside settlement exclusion zones, never on bridges or behind the caravan (`EncounterPlanner`).
- [x] Activation when a shipment reaches the ambush point; the shipment is held (abstract `holdUntil`; physical caravans stop through `BanditManager.holds`).
- [x] Deterministic abstract resolution: escape, delay, partial loss, and rare total loss for groups of 6 or more (`EncounterRules.decideAbstract`).
- [x] Single resolution transaction (`EncounterService.resolve` / `cancel`).
- [x] Caravan integration: a leader or carrier death during an encounter resolves it as an overrun (30–60% loss) and the caravan continues abstractly; a held caravan stops and its guards fight.
- [x] ROADBLOCK encounters on very dangerous roads; expiry; suppression after clearing.
- [-] Bandit camps: deferred to Phase 8.1. ROADBLOCK encounters provide local bandit presence without world-structure generation, which would need its own chunk-safe generation and cleanup design.

## 4. Physical representation

- [x] `BanditEntity`: a Kingdoms-owned `Monster`, never saved, orphans discard themselves, only vanilla XP (no loot, equipment never drops).
- [x] Simple AI: retaliate, then the caravan of its own encounter, then a nearby player, then the rally point (re-path every 40 ticks). Stuck recovery: bandits beyond 40 blocks are removed; a fight with no progress for 6000 ticks goes abstract and stays suppressed until the abstract rules settle it.
- [x] Materialization with hysteresis (48/80), per-encounter/per-player/global caps, safe outdoor spawns outside settlement and colony zones (`BanditSpawnPlanner`), entity-ticking chunks only, never on peaceful, and a failed spawn keeps the encounter abstract with a retry delay (`BanditRoster`).
- [x] Renderer: humanoid model with MineColonies barbarian textures referenced at runtime.

## 5. Integration

- [x] Contracts: ESCORT_CARAVAN and CLEAR_BANDITS from real encounters; completion, failure, or cancellation on resolution; reward paid exactly once through the Phase 7 pending-reward path.
- [x] Reputation: +2 once per encounter for defenders without a contract; contract holders get contract reputation; no per-kill reputation.
- [x] Citizens: guards report threat (`BanditReportHandler`); officials and merchants show security contracts on the settlement board.
- [x] Config `[bandits]`.
- [x] Commands `/kingdoms bandit stats|list|info|threat|evaluate|spawn-test|set-threat|materialize|dematerialize|resolve`.

## 6. Verification

- [x] Unit tests: `BanditRulesTest`, `EncounterPlanningTest`, `EncounterResolutionTest`, `BanditContractTest`, `BanditPresenceTest`, `SchemaV12MigrationTest` (178 tests in total).
- [x] Clean test build: `gradlew clean test build --no-build-cache --rerun-tasks`, 178 tests, 0 failures, 0 warnings.
- [x] Dedicated-server smoke on `phase8-test` (a copy of `phase7b-test`): migration, unchanged state, abstract ambushes, exactly-once loss and delivery, restart, natural roadblock, physical materialization/no duplicates/restart/victory, peaceful; repeated on the final code.
- [x] Integrated-client scripted checks: materialization near the real player, leave and return without duplicates, player victory, contract completion paid once, reputation once; repeated on the final code.
- [x] Merge-blocking review: 7 findings fixed with regression tests; a second pass found no blocker (see SMOKE_TEST.md).
- [x] Documentation: BANDITS.md (new), README, ARCHITECTURE, ROADMAP, SMOKE_TEST, CARAVANS, TRADE, DIPLOMACY.
- [x] Manual checklist A–L handed to the user (SMOKE_TEST.md, "Phase 8 manual client checklist").
- [ ] Manual graphical acceptance by the user.
