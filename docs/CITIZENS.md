# Physical settlement population (Phase 6.7)

## Authority

`NPCColonyData.population` stays the only population truth for `NPC_ABSTRACT` settlements. Physical citizens are temporary **representatives** of that population:

- materializing, dematerializing, killing, or losing a representative never changes population, economy, needs, growth, or trade;
- a representative carries only association IDs (settlement, representative) and a cosmetic texture key, with no inventory, needs, or strategic state;
- the entity is Kingdoms-owned (`minecolonies_kingdoms:settlement_citizen`, a `PathfinderMob` in `MobCategory.MISC`); it is not a MineColonies citizen, and no colony, Town Hall, claim, work order, fake player, or colony ID is created.

Player-owned MineColonies colonies (`PLAYER_PHYSICAL`) and `NPC_PHYSICAL` colonies are never touched; MineColonies keeps simulating its own citizens there.

## Roster

Each settlement has a bounded, persisted roster of recognisable representatives (`maxRosterPerSettlement`, default 16), derived deterministically from its population and its **completed** growth buildings:

| Building | Role (slots per building) | MineColonies texture family |
|---|---|---|
| `FARM` | Farmer (2) | `farmer` |
| `STOREHOUSE` | Porter (1) | `courier` |
| `LUMBER_YARD` | Lumberjack (1) | `forester` |
| `QUARRY` | Quarry worker (1) | `miner` |
| `SMITHY` | Smith (1) | `blacksmith` |
| `MARKET` | Merchant (2) | `aristocrat` (men), `settler` (women) |
| `CIVIC` | Official (1), plus Guard (1) in castles and forts | `noble` (men), `teacher` (women); `knight` |
| — | Resident, `max(2, min(6, population/6))` | `citizen` |

Slots are filled in a fixed order: the first slot of every workplace, then two residents, then remaining workplace slots, then remaining residents. The roster never exceeds the population. Homes are assigned only to completed `HOUSE` buildings (at most 4 per house), and a valid existing assignment is kept. When the population falls, a building is removed, or the cap changes, affected entries are dropped or reassigned, while every surviving slot keeps its ID, name, gender, and cosmetic seed. The ID is `nameUUIDFromBytes("kingdoms-representative:<settlement>:<slot>")`, so a roster recomputed without persistence yields the same people. Names come from Kingdoms-owned name pools.

The roster is recomputed only when its signature changes (population, cap, set of completed buildings), not every cycle. Deleting a settlement or converting it away from `NPC_ABSTRACT` removes its roster.

## Materialization budget

`SettlementCitizenManager` runs every `updateIntervalTicks` (default 20) on the server thread:

1. For each `NPC_ABSTRACT` settlement: nearest non-spectator player, and whether the anchor chunk is loaded. Chunks are never loaded or force-loaded.
2. Activation uses hysteresis: inactive → active inside `materializationRadius` (64), active → inactive beyond `dematerializationRadius` (96).
3. Active settlements are processed nearest first. Target count = `min(round(2 + 1.5·√population), population, roster size, maxPhysicalCitizensPerSettlement)`. That is 9 for population 21 and 12 (the default cap) for population 57.
4. At most `spawnsPerCycle` (2) new representatives per settlement per cycle, and never beyond `maxPhysicalCitizensGlobal` (64) or `maxPhysicalCitizensPerPlayer` (24).
5. Excess representatives are removed first when the target drops.

Spawn positions come from `SafeSpawnFinder`: a bounded ring search (at most 729 candidates) around the representative's anchor, which is their home door, their workplace (guards, officials), or the plaza. A candidate needs a sturdy non-hazard floor, two open non-fluid blocks, an **entity-ticking** chunk, and must lie outside every building footprint. If none is found, the attempt counts as a failed spawn and is retried after 100 ticks.

## Daily schedule and street navigation

`CitizenSchedule` derives the activity from the time of day, shifted by up to 600 ticks per person so the town does not move in lockstep:

| Time (ticks) | Activity |
|---|---|
| 23000–500 | home (not materialized) |
| 500–1500 | going to work; without a workplace: heading to the plaza |
| 1500–9000 | working, milling around the workplace door; without a workplace: strolling or on the plaza |
| 9000–11500 | visiting the market or chatting on the plaza |
| 11500–12500 | returning home; without a house: chatting on the plaza |
| 12500–23000 | home; without a house the shelter is the plaza |

Guards keep their post at the gate at all hours. A representative who reaches their home door, or the plaza if they have no house, leaves the world ("went inside") and reappears at the door in the morning. Nobody is spawned while their activity is "home" or "returning home".

Routes follow the settlement's persisted local street network. `StreetRouter` builds a graph from all street points (≤ 4096 nodes: consecutive points, 8-neighbour adjacency, plaza links), runs a BFS between the nearest street nodes, and returns sparse waypoints (every 5 points) ending exactly at the destination. If either end is more than 24 blocks from a street, the route falls back to a single direct waypoint. Routes are cached (LRU, 256). The entity walks each waypoint with vanilla pathfinding, re-issued only when the waypoint changes or every 40 ticks. `StuckTracker` skips a waypoint after `stuckTimeoutTicks` (160) without progress and gives up after 3 skips: the representative is dematerialized and may reappear after 200 ticks.

## Death, unloads, restarts

- **Death** (player or environment): counted in `deaths`; that representative reappears after `respawnCooldownTicks` (1200). Population is unchanged. A player who kills one loses reputation with the settlement (Phase 7). Another roster member may fill the free physical slot meanwhile.
- **Unload**: representatives are never saved to chunks (`shouldBeSaved() == false`). A representative whose entity disappeared is treated as a technical loss and simply respawns when possible.
- **Orphans**: every 40 ticks an entity checks whether the manager still lists it with the same entity UUID; if not, it discards itself. After a restart no representative entity survives, and the persisted roster reproduces the same identities.

## Interaction (ready for Phase 7)

Right-clicking a representative stops it, turns it to the player, and prints its name, role, settlement, and current activity, followed by lines from registered `CitizenInteractionHandler`s. `SettlementCitizenInteractionService` receives a `CitizenInteractionContext` (player, representative, settlement, `NPCColonyData`, activity, day time). The only built-in handler is a read-only greeting that mentions the settlement's most pressing need or the time of day. Since Phase 7 the contract board is such a handler (see [Reputation, contracts, and diplomacy](DIPLOMACY.md)), and the header shows the player's standing with the settlement. The entity itself contains no contract or reputation logic.

## Rendering

`SettlementCitizenRenderer` uses vanilla player geometry baked for a 128×64 texture, which is exactly the humanoid UV layout of MineColonies' `MaleCitizenModel`/`FemaleCitizenModel` (female uses slim arms). MineColonies citizen textures are referenced **at runtime**, never copied: `minecolonies:textures/entity/citizen/<style>/<prefix><male|female><variant>_<a|b|d|w>.png`. The style comes from the settlement's style family (medieval, nordic, eastasian, hellenic, nether, modern, undead, else default). The renderer reads each texture's PNG header once and accepts only the 2:1 humanoid layout (128×64 or an HD multiple). A missing texture, or one in another layout, falls back to the same name under `default/`, then to a vanilla default skin. Female aristocrats and nobles in MineColonies use dress models with 128×128 textures, so women in those roles wear the `settler`/`teacher` outfits instead. Accessory cubes that MineColonies draws from the right half of the texture (hats, beards, and similar) are not rendered.

## Persistence

Schema 9 adds the `citizens` roster registry. The v8 → v9 migration only adds an empty registry. The roster is rebuilt from buildings on first activation, and no settlement, growth, road, shipment, or caravan data is rewritten. Physical state (entity UUIDs, waypoints, cooldowns, holds, router cache) is runtime-only.

## Configuration (`[citizens]`)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Master switch; disabling removes all representatives on the next tick |
| `materializationRadius` | 64 | Activation distance from the settlement anchor |
| `dematerializationRadius` | 96 | Deactivation distance; kept at least 8 above the activation radius |
| `maxPhysicalCitizensPerSettlement` | 12 | Per-settlement physical cap |
| `maxPhysicalCitizensGlobal` | 64 | Server-wide physical cap |
| `maxPhysicalCitizensPerPlayer` | 24 | Cap per observing player |
| `updateIntervalTicks` | 20 | Manager cycle |
| `maxRosterPerSettlement` | 16 | Persisted recognisable representatives (not the population) |
| `spawnsPerCycle` | 2 | Gradual appearance |
| `respawnCooldownTicks` | 1200 | Delay after a death |
| `stuckTimeoutTicks` | 160 | No-progress timeout per waypoint |

## Commands (operator)

```text
/kingdoms citizen stats
/kingdoms citizen list <settlement-uuid>
/kingdoms citizen info <representative-uuid>
/kingdoms citizen materialize <settlement-uuid>
/kingdoms citizen dematerialize <settlement-uuid>
```

`materialize` holds a settlement active for 1200 ticks even with no player nearby (useful on a headless server). `dematerialize` removes its representatives and suppresses it for 1200 ticks. Neither changes the logical population. `stats` reports physical/active/roster counts and the profiler: materialized, dematerialized, failedSpawns, deaths, stuckRecoveries, cycles, and average/maximum cycle time.

## Performance

Far settlements cost one nearest-player distance check per cycle. Measured on the dedicated smoke test (2 active settlements, 19 representatives): average cycle 0.45–0.8 ms, once every 20 ticks. The maximum, 15–43 ms, occurred once per server start, on the first activation (cold classes and JIT, roster planning, street graph construction); later activations stayed under 15 ms.

## Known limitations

- Representatives do not enter buildings; being home means dematerialized at the door ("turning in for the night" is the walk there).
- Workers mill around the workplace door instead of animating a job.
- Only settlements with a persisted layout get street routes; others use direct waypoints.
- Accessory cubes from the MineColonies texture (hats, beards) are not drawn.
