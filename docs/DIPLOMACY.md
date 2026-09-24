# Reputation, contracts, and diplomacy (Phase 7)

Phase 7 gives players a relationship with the world's settlements and gives settlements relationships with each other. It keeps three authoritative domains strictly separate:

| Domain | Key | Store | Only writer |
|---|---|---|---|
| Player reputation | player UUID → faction UUID | `ReputationRegistry` (`KingdomsSavedData.reputation()`) | `ReputationService` |
| Faction relations | faction UUID ↔ faction UUID | `Faction.relations()`, both directions equal | `DiplomacyService` |
| Contracts | contract UUID | `ContractRegistry` (`KingdomsSavedData.contracts()`) | `ContractService` |

Every change in each domain is audited. Physical representatives only present these services, and commands remain a complete fallback interface.

## Audit of the old `Faction.reputation` field

Phase 1 declared `Faction.reputation` as `Map<UUID subjectId, Integer>` without defining what a "subject" is: a player or a faction could both fit. Nothing wrote it until the preliminary Phase 7 build, which stored player standings in it. To avoid one field with two possible meanings, schema 11 moves those entries into the dedicated `ReputationRegistry` and removes the field from `Faction`. `Faction.relations()` was always faction ↔ faction and stays the relation store.

## Data model

### Contract record

| Group | Fields |
|---|---|
| Identity | `id` = `nameUUIDFromBytes("kingdoms-contract:<settlement>:<sequence>")`, `sequence` (persisted counter per settlement), `settlementId`, `factionId` (issuer) |
| Objective, frozen when posted | `resource`, `amount` (strategic units), `severity`, `needCurrent`/`needTarget` (evidence), `baseReward`, `reputationReward`, `offeredAt`, `offerExpiresAt` |
| Acceptance, frozen when accepted | `holder` (player UUID), `acceptedAt`, `deadline`, `tierAtAcceptance`, `agreedReward`, `reservedReward` (money currently held back from the treasury) |
| Progress | `delivered`, `deliveries` |
| Closure | `status`, `closedAt`, `closeReason` (`COMPLETED`, `DEADLINE_MISSED`, `OFFER_EXPIRED`, `PLAYER_ABANDONED`, `SETTLEMENT_REMOVED`, `ADMIN`) |
| Exactly-once flags | `reputationApplied`, `rewardIssued`, `rewardIssuedAt` |

Lifecycle:

```text
OFFERED ──accept──▶ ACCEPTED ──fully delivered──▶ COMPLETED
   │                   ├──deadline passed──────▶ FAILED
   │                   └──abandon / settlement removed / operator──▶ CANCELLED
   ├──offer lifetime passed──▶ EXPIRED
   └──settlement removed / operator──▶ CANCELLED
```

Terminal states never change. Later economy changes never touch a posted objective or accepted terms.

### Reputation record

`player UUID → faction UUID → { value (−100..100), lastChangedAt, completed, failed, cancelled, killed }`. Entries exist only for pairs that ever changed.

A bounded audit log (256 entries, newest first) holds `{ time, player, faction, delta, after, cause, reference }`. The causes are `CONTRACT_COMPLETED`, `CONTRACT_FAILED`, `CONTRACT_CANCELLED`, `REPRESENTATIVE_KILLED`, `SPILLOVER` (reference = source faction), `ADMIN_SET`, and `MIGRATED`.

### Relation event

`{ time, first, second (first < second), before, after, cause, evidence }`, kept in a bounded log of 256. The causes are `FIRST_CONTACT`, `TRADE_DELIVERIES` (evidence = number of shipments), `ADMIN_SET`, and `MIGRATED`.

### Treasury reservation model

- `Faction.treasury` is the **free** balance and never goes negative. Taxes accrue lazily at 0.2 emerald per citizen per Minecraft day, up to `64 + 2 × population`; a faction seen for the first time starts with half of that.
- **Offers reserve nothing.** An offer is posted only while the free balance covers the base rewards of all of that settlement's open offers, so advertised offers are fundable.
- **Acceptance reserves.** `reserve = min(baseReward × tier multiplier, balance)`. If that is below the base reward, the acceptance is refused ("The council cannot afford this contract right now") and the offer stays open. The reservation moves from the balance into `reservedReward`.
- **Completion** pays `reservedReward` to the player exactly once. **Failure** and **cancellation** return it to the balance. Expired offers had nothing reserved.
- Invariant: `balance + Σ reservedReward` changes only by taxes and completed payouts.

## Generation rules

- Only `NPC_ABSTRACT` settlements with a council faction post contracts; player-managed MineColonies colonies never do.
- Generation is lazy: it runs when a player opens the board (official/merchant) or runs `/kingdoms contract offers`, and at most once per `offerRefreshTicks` (2400) per settlement. The operator `refresh` bypasses the interval.
- Candidates are resource needs (food, wood, stone, iron) of severity MEDIUM or worse, most severe first.
- Deduplication: at most one open (OFFERED or ACCEPTED) contract per settlement and resource, and at most `maxOpenPerSettlement` (3) open contracts per settlement.
- Cooldown after a contract closes: no equivalent contract for the same settlement and resource for `resourceCooldownTicks` (12000), or `unacceptedCooldownTicks` (24000) if nobody took the offer. An unresolved need therefore does not produce a stream of identical offers.
- Offers expire after `offerLifetimeTicks` (48000). Accepted contracts have `durationTicks` (72000).
- Contract IDs are stable and persistent.

| Resource | Size (units) | Step | Units per emerald | Hint |
|---|---|---|---|---|
| Food (nutrition points) | 48–192 | 8 | 24 | bread = 5 |
| Wood (planks; log = 4) | 32–160 | 8 | 32 | log = 4 |
| Stone (blocks) | 64–256 | 16 | 48 | cobblestone |
| Iron (ingots; block = 9) | 8–32 | 2 | 4 | iron ingot |

The size is half the shortage, clamped to the band and rounded down to the step. The base reward is `ceil(amount / per)`, ×1.25 for HIGH and ×1.5 for CRITICAL. The reputation reward is +3 (MEDIUM), +5 (HIGH), or +7 (CRITICAL).

## Completion transaction

`ContractService.deliver` performs one transaction through an `InventoryPort`. The real implementation reads the player's main inventory; tests use an in-memory one.

1. **Validate the contract**: it is ACCEPTED, you are the holder, the deadline has not passed (otherwise it fails now), and the settlement still posts contracts (otherwise it is cancelled with no penalty).
2. **Validate the items**: plan the hand-over with the least surplus.
   - Items that are never accepted: enchanted, renamed, uncommon (golden apples, for example), or food with side effects (rotten flesh, spider eyes, raw chicken, and similar).
   - With nothing usable, the delivery is refused and nothing changes.
3. **Remove the items** all or nothing: every slot is checked first, then shrunk. The port returns an undo action.
4. **Deposit** all handed-over units into the settlement's real strategic stockpile (`EconomyManager.depositShipment`).
5. **Update the contract** once: credit up to the remaining amount, and complete it when fulfilled.
6. **Apply reputation** once (the `reputationApplied` flag), with any spillover.
7. **Issue the reward** once (the `rewardIssued` flag): the reserved emeralds go into the inventory, or are dropped at the player's feet if it is full.

Failure handling:
- An exception during steps 4–5 restores the stockpile, rolls back the contract credit, and returns the removed items; the player sees "nothing was taken".
- A failure during step 7 leaves the contract COMPLETED with the reward still reserved and pending. The reward is paid exactly once on the player's next contract action (board, list, accept, deliver, abandon), also after a restart.
- A repeated delivery on a completed contract is refused. The flags are persisted, so a restart never pays twice.

## Reputation

| Tier | Range | Effect |
|---|---|---|
| Hostile | −100..−60 | No contracts; refusals and hostile greetings |
| Unfriendly | −59..−20 | Contracts pay ×0.8 |
| Neutral | −19..19 | ×1.0 |
| Friendly | 20..59 | ×1.1 |
| Honored | 60..89 | ×1.25 |
| Revered | 90..100 | ×1.4 |

| Event | Change |
|---|---|
| Contract completed | +3 / +5 / +7 by severity |
| Accepted contract expired (FAILED) | −6 (`failReputationPenalty`) |
| Contract abandoned by the player | −4 (`abandonReputationPenalty`) |
| Killing a settlement representative | −10 (`killReputationPenalty`) |
| Settlement removed / operator cancel | none |

Spillover works like this:
- A change spreads once, never recursively, to factions related to the target.
- Allies (relation ≥ 60) receive a quarter of it.
- A hostile faction (relation ≤ −60) loses a quarter of any *gain*.
- Changes smaller than 4 do not spread.

There is no passive decay. Standings are per player, so players in multiplayer never affect each other's reputation.

## Diplomacy between settlements

Relations exist only between **diplomatic neighbours**: NPC factions (city states, kingdoms, tribes) whose settlements share a road edge (routed or unroutable) or a trade route. The road graph's degree limit keeps this linear in the number of settlements.

Relations change only through explicit, audited events:
- **First contact**: when a pair first becomes neighbours, its relation starts at a stable, pair-specific disposition (−30..30), clamped to **never start tense** (−19..30).
- **Trade**: once per `evaluationIntervalTicks` (a Minecraft day; the time is persisted), each pair gains +1 per shipment delivered between them in that window, at most +2. Each shipment counts once.
- **Operator** edits (`/kingdoms diplomacy set`).
- **Migration** of values that existed before auditing.

There is no autonomous drift and no effect from ordinary shared resource demand. In the smoke worlds every young settlement was CRITICAL on all four resources, so a competition rule would have turned all neighbours against each other. Hostile events belong to later phases (bandits, wars).

| Stance | Range | Effect |
|---|---|---|
| Hostile | ≤ −60 | No trade; resents reputation gains with its enemy |
| Tense | −59..−20 | No trade |
| Neutral | −19..19 | Trade |
| Friendly | 20..59 | Trade |
| Allied | ≥ 60 | Trade; reputation changes spread to allies |

## Persistence

Schema 11 stores `contracts` (format 2), `reputation`, and `diplomacy.relationEvents`.
- **9 → 10** adds empty containers.
- **10 → 11** converts saves written by the preliminary Phase 7 build:
  - player standings move from `Faction.reputation` to the registry;
  - contract statuses map `ACTIVE→ACCEPTED`, `ABANDONED→CANCELLED`, and `WITHDRAWN→EXPIRED` (or `CANCELLED` if it had a holder);
  - rewards that the old build had escrowed for mere offers return to the treasury, accepted contracts keep theirs as the reservation, and completed ones are marked as paid;
  - existing relation values are logged once as `MIGRATED`.

Closed contracts are pruned after `historyRetentionTicks` (7 days) and beyond `maxHistory` (256), but never while a reward is pending.

## Configuration

`[contracts]`: `enabled`, `maxOpenPerSettlement` (3), `maxActivePerPlayer` (3), `offerLifetimeTicks` (48000), `durationTicks` (72000), `offerRefreshTicks` (2400), `resourceCooldownTicks` (12000), `unacceptedCooldownTicks` (24000), `interactionRadius` (96), `historyRetentionTicks` (168000), `maxHistory` (256), `failReputationPenalty` (6), `abandonReputationPenalty` (4), `killReputationPenalty` (10).

`[diplomacy]`: `enabled`, `evaluationIntervalTicks` (24000).

## Commands

Players (no permission needed; the chat buttons run these):

```text
/kingdoms reputation
/kingdoms contract list
/kingdoms contract offers
/kingdoms contract accept|deliver|abandon <contract>
```

Operators (fallback and debugging):

```text
/kingdoms reputation of <player>
/kingdoms reputation history [player]
/kingdoms reputation set <player> <faction-uuid> <-100..100>
/kingdoms contract all [settlement-uuid]
/kingdoms contract refresh <settlement-uuid>
/kingdoms contract cancel <contract>
/kingdoms contract stats
/kingdoms diplomacy list|events|evaluate
/kingdoms diplomacy info <faction-uuid>
/kingdoms diplomacy set <faction-uuid> <faction-uuid> <-100..100>
```

Contract IDs may be given as a unique prefix of at least 6 characters.

## Boundaries

- `SettlementCitizenEntity` holds no contract or reputation logic. Its right-click reaches `SettlementCitizenInteractionService`, whose `ContractInteractionHandler` renders the board and calls `ContractService`/`ContractManager`.
- `ContractManager` is the only code that touches player inventories (through the port) and messages.
- `DefaultTradePermissionPolicy` reads the stance: neutral or better trades, tense or hostile does not.
- Not implemented: bandits, wars, armies, outposts, player-to-player contracts, and a GUI.

## Security contracts (Phase 8)

The contract kind is part of the frozen objective (format 3; older records read as `DELIVERY`). Two security kinds come only from real bandit encounters:

| Kind | Posted by | Reward | Completes when | Fails when |
| --- | --- | --- | --- | --- |
| `ESCORT_CARAVAN` | the caravan's origin settlement | `3 + 2 × strength`, +5 reputation | the players win and the holder fought | the bandits take cargo |
| `CLEAR_BANDITS` | the settlement nearest to a roadblock | `4 + 2 × strength`, +6 reputation | the players win and the holder fought | the roadblock outlives its lifetime |

- Anything else cancels a security contract without penalty.
- There is one contract per encounter and at most one open security contract per settlement.
- The same reservation, pending-reward, and exactly-once rules as deliveries apply. `deliver` refuses security contracts (`NOT_A_DELIVERY`).
- An accepted security contract is never failed by the generic deadline, because its encounter always ends. If the encounter disappears without closing it, the sweep cancels it without penalty.
- Players who defeat an encounter without a contract get +2 reputation (`ENCOUNTER_DEFENDED`) once per encounter. Kills give nothing.

See [Bandits](BANDITS.md).
