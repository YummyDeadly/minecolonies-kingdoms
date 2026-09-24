# Bandits, threats, and caravan ambushes (Phase 8)

Bandits are a persistent, strategic threat on the trade-road network. They appear around busy, remote roads, plan ambushes of real caravans, sometimes block a road outright, and can be fought by players and caravan guards. They are **not** a faction: they have no treasury, no relations, no settlements, and no reputation of their own. Reputation changes only through the existing Phase 7 services.

## Authority model

The strategic records are authoritative, and entities are temporary representations:

| Domain | Record | Single writer |
| --- | --- | --- |
| Road danger | `RoadThreat` (one per eligible road) | `ThreatEvaluator` / `EncounterService` |
| Encounters | `BanditEncounter` | `EncounterService` |
| Cargo in transit | `TradeShipment` (`lostAmount`, `banditEncounterId`, holds) | `EncounterService` via `TradeShipment.recordBanditLoss` / `holdUntil` / `releaseHold` |
| Security contracts | `Contract` (kinds `ESCORT_CARAVAN`, `CLEAR_BANDITS`) | `ContractService` |
| Player reputation | `ReputationRegistry` | `ReputationService` |
| Physical bandits | runtime `BanditRoster` only (never saved) | `BanditManager` |

A `BanditEntity` stores only its encounter ID, its shipment ID, a rally point, and a chief flag. It never decides an outcome. A bandit's death lowers the encounter's persisted `remainingStrength` by one, and the encounter alone decides the result through `EncounterService`.

## Road threat

Every road with physical geometry that is not `UNROUTABLE`/`FAILED` has a `RoadThreat` (0–100). Every evaluation interval (default 1200 ticks) the threat moves towards a transparent target by at most `threatStep` (default 5), so it never jumps or oscillates:

```
target = base (5)
       + traffic     min(30, 6 × recentTraffic)       recentTraffic: +1 per assessed caravan, ×0.8 per evaluation
       + remoteness  min(25, remoteLength / 40)        road length outside all exclusion zones
       + momentum    min(25, raidMomentum)             +15 per bandit win, ×0.9 per evaluation
       - security    min(40, 8 × (security(A) + security(B)))   VILLAGE 1, TOWN/TRADING_TOWN 2, FORT 3, CASTLE 4
       - suppression 50 while the road is suppressed (after bandits were defeated)
```

`/kingdoms bandit threat <road>` prints every contributor. Security uses both endpoints, so roads touching a castle are practically safe unless an operator raises the threat. A busy, remote road between two villages can reach about 45.

When bandits are defeated, the road loses 20 threat, raid momentum resets, and the road is suppressed for `suppressionTicks` (default 24000). Every encounter also starts a per-road cooldown (`encounterCooldownTicks`, default 6000).

## Encounters

```
PLANNED --shipment reaches the ambush point--> ACTIVE --resolution--> RESOLVED_BANDITS | RESOLVED_CARAVAN | RESOLVED_PLAYER
   |                                             |--roadblock lifetime over--> EXPIRED
   +--shipment gone / lifetime / operator--> CANCELLED (or EXPIRED) <---+--shipment gone / road gone / operator
```

- **AMBUSH:** bandits wait for one specific in-transit shipment at a point ahead of it. Its ID is `nameUUID("kingdoms-encounter:" + shipment + road)`, so the same shipment can never be ambushed twice on the same road.
- **ROADBLOCK:** on a road whose threat reaches `roadblockThreshold` (default 70), bandits occupy a remote point until they are cleared or `roadblockLifetimeTicks` (default 24000) passes. There is at most one open roadblock per road.

Each encounter persists:
- ID, kind, road, shipment, dimension, position, and trigger progress;
- a seed derived from the ID;
- threat at creation, strength, and remaining strength;
- status, representation (ABSTRACT or PHYSICAL), and all timestamps;
- cause, outcome, and the exact cargo result (resource, before, lost, delay);
- defenders (at most 8 players) and a consequences-applied flag.

Representation is separate from status, as it is for shipments, and every loaded encounter starts ABSTRACT.

### Placement

Candidates are the nine points at 10% steps along a road (or along the road's span inside a shipment path). A candidate is eligible only when all of these hold:
- it lies at least `settlementExclusionRadius` (default 192, horizontal) from every settlement anchor and every tracked colony centre, including a player's own MineColonies colony next to the road;
- it is at least 12 blocks from any bridge point;
- for an ambush, it lies at least 32 blocks ahead of the caravan.

The seed chooses one eligible candidate, and a road without eligible points gets no encounter. There is no per-block map, and the remote length of each road is cached until the set of anchors changes.

### Assessment (one roll per shipment)

Each in-transit shipment is assessed once, at the first evaluation after departure. The decision is persisted (`BanditRegistry.assessed`) and never rolled again:
1. Traffic is added to every road of its path.
2. The roads are considered from the most threatened down, skipping suppressed and cooling roads.
3. The first road with a non-zero chance gets one seeded roll `unit(seed(ambushId), SALT_AMBUSH) < chance`, where `chance = ambushChanceAtMaxThreat × (threat − 15) / 85` (zero below threat 15, default maximum 0.6).
4. A success plans a PLANNED ambush, and either way the shipment is done.

Direct (road-less) shipments are marked assessed and are never ambushed. The active-encounter cap (`maxActiveEncounters`, default 16) bounds everything.

### Activation and holding

When the shipment's progress reaches the trigger, the encounter becomes ACTIVE and `resolveAt = now + abstractResolveTicks` (default 2400).
- An abstract shipment is held: its progress stops, and its arrival moves back by the same time.
- A physical caravan is held by `CaravanManager`: it stops, its guards defend, and its stuck timer is paused.

A shipment that is no longer in transit cancels its ambush (`SHIPMENT_GONE`). A PLANNED ambush that is still waiting at its lifetime (twice the shipment's travel time) expires, so no encounter can linger.

### Abstract resolution (deterministic)

An unobserved ACTIVE ambush is decided at `resolveAt` by `EncounterRules.decideAbstract(seed, remainingStrength, security)`, where security is the stronger endpoint:

- bandit win chance `clamp(0.2 + 0.08 × strength − 0.05 × security, 0.05, 0.75)`:
  - usually a **partial loss** of 20–60% of the cargo;
  - a **total loss** only for groups of 6 or more, in about 5% of their wins;
- otherwise the caravan holds: 40% **delayed** (1200–3600 ticks), 35% **bandits beaten off**, 25% **escaped**;
- no remaining strength means the bandits were defeated.

Strength is `clamp(2 + floor(threat / 25), 1, maxBanditsPerEncounter)`. The same seed always gives the same decision, including after a restart.

### The single resolution transaction

`EncounterService.resolve` is the only path to a result: abstract roll, physical victory, caravan overrun, or operator.
1. If the encounter is no longer open, return `applied=false` and change nothing.
2. For an ambush, check that the shipment is still in transit; otherwise cancel.
3. Compute the lost units from the deliverable amount.
4. `TradeShipment.recordBanditLoss(encounterId, lost)` records the loss exactly once per shipment, and a second loss is refused.
5. Release the hold, apply any delay, and fail the shipment with `BANDIT_RAID` when nothing is left.
6. Record the cargo result on the encounter and set its terminal status.
7. Apply the consequences:
   - road threat: raided or cleared;
   - Phase 7 contracts: complete, fail, or cancel;
   - defender reputation, then set the consequences flag.

Delivery deposits and failure refunds use `deliverableAmount() = amount − lostAmount`. Lost cargo is simply gone (an economy sink), and nobody receives it.

## Physical bandits

`BanditManager` runs every 20 ticks. Materialization happens only when all of these hold:
- the encounter is ACTIVE and has remaining strength;
- its position is in an **entity-ticking** chunk (never loaded for this purpose);
- a player is within `materializationRadius` (default 48), or an operator holds it;
- the difficulty is not peaceful (on peaceful the game would remove hostile mobs every tick, so encounters stay abstract);
- it is neither suppressed nor waiting after a failed spawn.

Bandits leave beyond `dematerializationRadius` (default 80, always at least 8 more than the radius above), which gives hysteresis.

- **Caps:** per encounter `maxBanditsPerEncounter` (5), per observing player `maxPhysicalBanditsPerPlayer` (10), and server-wide `maxPhysicalBanditsGlobal` (24).
- **Safe spawn (`BanditSpawnPlanner`):**
  - at most 3 seeded probes per bandit on a ring 5–10 blocks around the point, each a bounded `SafeSpawnFinder` search: dry sturdy floor, two open blocks, loaded chunks only, at most 729 checks;
  - rejects probes more than 8 blocks above or below the road, indoors (sky light below 10), inside an exclusion zone, or within 5 blocks of a player.
  - If nothing qualifies, the encounter stays abstract and is retried after 200 ticks.
- **AI:** retaliate (and alert other bandits), attack the caravan of their own shipment, attack a nearby player, or walk back to the rally point (re-pathing at most every 40 ticks). The chief (for strength 4 or more) carries an iron axe, the others stone swords. There are no drops beyond vanilla experience, and equipment never drops.
- **Stuck recovery:**
  - A bandit more than 40 blocks from its point is removed. Its strength is kept, so it returns later.
  - A fight in which no player hits a bandit and no bandit dies for 6000 ticks goes back to abstract. It stays suppressed until the abstract rules have settled it, so a player standing next to it cannot keep it alive forever.
  - A roadblock past its lifetime leaves once nobody has fought it for 600 ticks.
- **Leaving and returning:** dematerializing discards the entities and never decides the fight on the spot, because abstract resolution waits at least half of `abstractResolveTicks` more. Returning shows the remaining strength again, never more.
- **Orphans:** entities are never saved to chunks (`shouldBeSaved() == false`). Every 40 ticks a bandit checks that the runtime roster lists it under its encounter's current materialization, and otherwise removes itself. After a restart nothing physical survives.

Physical victory: when every bandit of an encounter is dead (persisted strength 0), the encounter resolves as `BANDITS_DEFEATED`, with cause `PLAYER_VICTORY` if any player fought it and `CARAVAN_GUARDS` otherwise. If the caravan's leader or carrier dies while bandits hold it, the encounter resolves as a **caravan overrun**: a seeded 30–60% partial loss, after which the rest of the cargo travels on abstractly.

## Players, reputation, and contracts

- A player who hurts a bandit of an encounter becomes one of its defenders (at most 8 are recorded).
- If the players win, every defender without a contract for that encounter gets **+2** reputation with the beneficiary once per encounter. The beneficiary is the caravan owner's faction, or for a roadblock the faction of the nearest road endpoint. Kills themselves give nothing.
- **ESCORT_CARAVAN** is posted by the caravan's origin settlement for a real planned or active ambush.
  - Reward `3 + 2 × strength`, +5 reputation.
  - It completes when the players win and the holder fought, and fails (penalty, reservation returned) when the bandits take cargo.
  - It is cancelled without penalty otherwise.
- **CLEAR_BANDITS** is posted by the settlement nearest to a roadblock.
  - Reward `4 + 2 × strength`, +6 reputation.
  - It completes when the players win and the holder fought, and fails when the roadblock outlives its lifetime.
  - It is cancelled without penalty otherwise.
- Security contracts reuse the Phase 7 machinery:
  - offers reserve nothing, and acceptance reserves the agreed reward;
  - completion keeps the reward pending until it is paid exactly once;
  - there is one contract per encounter and at most one open security contract per settlement.
- An accepted security contract is decided by its encounter, never by the generic contract deadline. If its encounter disappears without closing it, the sweep cancels it without penalty.
- Officials and merchants show security offers on the settlement board next to deliveries. Guards report the most dangerous road nearby ("The road to X is dangerous...") and where bandits were seen.

## Persistence (schema 12)

- `bandits`: road threats, encounters, shipment assessments, and the last evaluation time.
- `TradeShipment`: optional `banditEncounter` and `lostAmount`.
- Contracts format 3: `kind` plus the optional objective target (encounter, shipment, position). Records without `kind` read as `DELIVERY`.

Migration v11 → v12 only adds an empty `bandits` compound. Unreadable encounter records are dropped with an error log and never crash the load. Resolved encounters are kept for 168000 ticks, 256 at most. Assessment markers are dropped once their shipment is no longer in transit.

## Configuration (`[bandits]` in `minecolonies_kingdoms-server.toml`)

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | true | Disabling keeps the state, removes physical bandits, and stops new activity |
| `evaluationIntervalTicks` | 1200 | Threat evaluation interval |
| `materializationRadius` / `dematerializationRadius` | 48 / 80 | Physical hysteresis |
| `maxBanditsPerEncounter` / `maxPhysicalBanditsPerPlayer` / `maxPhysicalBanditsGlobal` | 5 / 10 / 24 | Caps |
| `settlementExclusionRadius` | 192 | No bandit activity near settlements and colonies |
| `baseThreat` / `threatStep` | 5 / 5 | Threat model |
| `encounterCooldownTicks` | 6000 | Minimum time between encounters on a road |
| `ambushChanceAtMaxThreat` | 0.6 | Ambush chance at threat 100 |
| `abstractResolveTicks` | 2400 | How long an unobserved ambush holds a caravan |
| `roadblockThreshold` / `roadblockLifetimeTicks` | 70 / 24000 | Roadblocks |
| `suppressionTicks` | 24000 | Quiet period after bandits are defeated |
| `maxActiveEncounters` | 16 | Open encounter cap |

## Commands (operator level 2)

- `/kingdoms bandit stats`: tracked roads, dangerous roads, planned and active encounters, physical encounters and bandits, and assessed shipments. It also prints counters (evaluations, planned, activated, abstract and physical resolutions, overruns, materializations, spawn failures, dematerializations, deaths, fled, stalls) and the average and maximum cycle time.
- `/kingdoms bandit list [all]`: open encounters (or all of them).
- `/kingdoms bandit info <encounter>`:
  - why it exists (threat then and now, strength, seed);
  - the shipment's state (lost, deliverable, progress, held);
  - timestamps and the result with its cause and exact cargo;
  - defenders, physical state, and targeting contracts;
  - when the next encounter on the road is possible.
- `/kingdoms bandit threat [road-or-settlement]`: the threat and all contributors, the current ambush chance (0 while suppressed or cooling down), and history counters.
- `/kingdoms bandit evaluate`: runs an evaluation now.
- `/kingdoms bandit spawn-test <road> [ambush|roadblock]`: plans an ambush for an in-transit shipment on that road without a roll, or forces a roadblock, and posts its security contract.
- `/kingdoms bandit set-threat <road> <0..100>`.
- `/kingdoms bandit materialize|dematerialize <encounter>`: `materialize` holds bandits for 1200 ticks (still only in entity-ticking chunks), and `dematerialize` suppresses the encounter for 1200 ticks.
- `/kingdoms bandit resolve <encounter> <outcome|cancel>`: goes through the same transaction, so a second call reports "already resolved; nothing changed".
- `/kingdoms trade shipment <id>` also shows the bandit loss, the deliverable amount, and the open encounter.

## Performance

| Work | Frequency and bound |
| --- | --- |
| Threat evaluation | Linear in eligible roads plus unassessed in-transit shipments, once per interval; the remote length of each road is cached |
| Cycle work | Linear in open encounters (≤ 16 natural) and physical presences |
| Spawn search | ≤ 3 probes × 729 checks per missing bandit |
| Timers | Kept only for open encounters |
| Profiling | Counts, a running average, and a maximum |

In the dedicated-server smoke test, the average cycle took 0.07–0.17 ms over 59–309 cycles. Right after a restart, the first dozen cycles averaged about 1.7 ms, with a 17 ms maximum for the first evaluation.

## Known limitations

- Roadblocks are local bandit presence only; they do not stop abstract caravans. Bandit camps as world structures are deferred to Phase 8.1.
- A `CARAVAN_DELAYED` outcome delays only abstract shipments. A physical caravan continues right away, and its delay is not re-applied when it later becomes abstract.
- At most 8 defenders are recorded per encounter. In a larger crowd, a contract holder who joins late may not be recorded.
- The ambush point is fixed when the ambush is planned. A road rebuilt later (a new geometry) keeps the old point until the encounter ends.
- Bandit AI is deliberately simple. They do not open doors, use ranged weapons, or coordinate.
