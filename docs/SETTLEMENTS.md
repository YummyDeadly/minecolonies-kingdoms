# World settlements

## Deterministic planning

The logical planner partitions every allowed dimension into square regions. A region result depends only on world seed, dimension id, region coordinates, a fixed Kingdoms salt, server configuration, and generator terrain samples. It never depends on chunk visitation order, which chunks happen to be loaded, or registry insertion order.

Each eligible region draws a bounded set of candidate centers (`candidateCount`). Minimum distance is resolved by comparing the winning candidates of all relevant neighbouring regions with a stable priority, so planning A then B produces the same layout as B then A.

Settlement types are `VILLAGE`, `TOWN`, `TRADING_TOWN`, `CASTLE`, and `FORT`. Names, UUIDs, gate, population, and faction UUID are deterministic. The gate orientation starts from a deterministic preference and rotates to the first side that the site analysis found approachable.

## Site feasibility (Phase 6.6)

Candidate acceptance is area based, not a few point samples. For every candidate:

1. **Broad phase** – a 5x5 coarse grid (a subset of the full grid, so samples are reused). Sites that are mostly ocean/disallowed, far too wet, or have an elevation span above three times the archetype limit are rejected immediately.
2. **Narrow phase** – a 9x9 grid covering `max(sampleRadius, footprintRadius + 16)`. The analyzer records total/buildable/water/disallowed samples, the largest 4-connected buildable component (bounded flood fill on the fixed grid), minimum/maximum height, maximum neighbour step, gate approaches in the four cardinal directions, and an estimated terrain-work volume relative to 3-block terrace levels.
3. **Refinement** – a site rejected for `WATER`, `INSUFFICIENT_BUILDABLE_AREA`, or `NO_GATE_APPROACH` whose connected land is still at least 25% is re-centred once on that component's centroid (at most two refinements per region, inside the region margin). A shoreline candidate therefore moves onto the adjacent land instead of either becoming a shoreline town or discarding usable land nearby.

Requirements are per archetype: minimum buildable and connected fractions, maximum water fraction (one waterfront side is allowed; the buildable/connected minima reject islands and thin shores), maximum elevation span, maximum sample step, and maximum terrain work. Castles are strictest. Rejection reasons are `DISALLOWED_BIOME`, `WATER`, `SLOPE`, `CLIFF`, `INSUFFICIENT_BUILDABLE_AREA`, `NO_GATE_APPROACH`, and `EXCESSIVE_TERRAIN_VOLUME`. The accepted analysis, including the approach mask, is persisted on the `SettlementRecord`.

Generator height queries cost roughly 1-2 ms each on a noise generator. Region planning therefore never runs on the server thread: chunk-load events and `/kingdoms settlement plan-region` schedule it on the `Kingdoms-Settlement-Planner` worker, which uses generator-only sampling and never touches chunks. Results are committed on the server thread (a fraction of a millisecond). Region candidates are memoized in a bounded LRU cache for the session.

## State ownership

`SettlementRecord` owns generation/location state: settlement UUID, name/type, dimension, anchor, orientation, gate, faction UUID, initial population, physical state, creation region, optional MineColonies id, creation time, site analysis, and legacy generated-chunk markers.

`NPCColonyData` with the same UUID owns simulation/economy state. Planning creates a matching strategic colony and city-state faction exactly once. No Town Hall or fictitious MineColonies numeric id is created.

Physical states are `PLANNED`, `GENERATING`, `GENERATED`, and `FAILED`.

## Starter districts (Phase 6.6)

New settlements no longer use the old four procedural timber boxes. That generator only continues saves that already contain legacy procedural chunk markers.

A starter district is planned once the catalog is `READY` and the settlement's anchor area is loaded:

- a deterministic Kingdoms-owned template per archetype (`village-green`, `market-quarter`, `garrison-court`) with 2-4 role slots (civic centre, first homes, stores, and farm/market/smithy); templates name roles, never blueprint files;
- one style family per settlement that contains every required role (`selectStyle`), so all starter blueprints are coherent;
- a stone plaza at the anchor and a terrain-aware gate street (`gate-to-plaza`) with gate posts;
- for each slot, a hierarchical lot search (see [growth](GROWTH.md)) that places a real blueprint on a bounded terrain pad and connects its entrance to the plaza/street network.

The template requires at least two buildings. If terrain cannot support a safe district the starter is **blocked explicitly**, with the failing role and the rejection counts in the blocker text, for example:

```text
STARTER_TEMPLATE_BLOCKED:NO_VALID_STARTER_PAD:HOUSE[positions=18 structures=2 rotations=224 pads=32 accepted=0 ... rejected={BUILDING_COLLISION=95,STARTER_EXCLUSION=42,WATER=12,ROUGHNESS=7,CLIFF=6,...}]
```

There is no procedural fallback. `SITE_REJECTED:<reason>`, `NO_COHERENT_STRUCTURE_STYLE`, `NO_STRUCTURE:<role>`, and `STREET_UNREACHABLE:GATE_TO_PLAZA` are the other terminal starter verdicts. `STARTER_SITE_NOT_LOADED:...` is **not** a verdict: part of the area is unloaded, nothing is persisted as blocked, and planning retries after a short back-off.

The starter state stores its planner version. When the planner implementation changes (`STARTER_PLANNER_VERSION`), a starter blocked by an older planner is re-planned once; a starter blocked by the current planner stays blocked across restarts.

## Physical generation and existing-world safety

`settlement.modifyExistingChunks=false` remains the default. Automatic (non-operator) physical work is written only into chunks that NeoForge reported as newly generated **during the current server session** (an in-memory, bounded record that is never persisted, so after a restart nothing old is assumed to be unexplored). A building is written only when all of its footprint chunks and their neighbourhoods are loaded and owned, and then all its slices are written together; a building that overlaps a pre-existing chunk records `EXISTING_CHUNK_PROTECTED` and waits for an operator. Local street slices follow the same ownership rule per chunk.

Chunk-load events are queued and processed from the server tick. They are never handled inside chunk loading, where terrain may still be receiving neighbour features.

`/kingdoms settlement materialize <uuid>` is the explicit operator override. It loads the bounded starter area (radius 80 blocks) so pad planning reads exact loaded terrain, plans the district if needed, writes all pending buildings and then streets, and reports the blocker if the district is blocked. Every building write is preceded by a whole-building preflight that refuses block entities and terrain drift beyond the physical tolerance, so a refusal never leaves a half-built structure. Repeating the command writes nothing (persisted building and street chunk markers).

If the command is used far from any player it must generate up to 11x11 new chunks synchronously, which can take several seconds. Standing at the settlement first avoids that.

## Physical population

Near a player, an `NPC_ABSTRACT` settlement shows a bounded number of physical representatives of its population, who walk its local streets on a daily schedule. They are cosmetic representation only: see [Physical settlement population](CITIZENS.md).

## Configuration

The `settlement` server-config section contains:

- `enabled` and `allowedDimensions`;
- `regionSize`, `densityPercent`, and `minimumDistance`;
- `candidateCount` and `sampleRadius`;
- `maximumSlope` and `maximumRoughness` (upper bounds applied to the per-archetype site requirements);
- `typeWeights.village`, `town`, `tradingTown`, `castle`, and `fort`;
- `modifyExistingChunks`;
- `terrainShaping.maximumCutDepth`, `maximumFillDepth`, `maximumPadHeightVariance`, `maximumRetainingWallHeight`, `maximumTerrainWorkVolume`;
- `localStreets.maximumSearchNodes`, `maximumTerrainAdjustment`, `maximumBridgeSpan`, `maximumLength`.

## Commands

```text
/kingdoms settlement list
/kingdoms settlement info <uuid>          # includes starter status, blocker, and site metrics
/kingdoms settlement locate
/kingdoms settlement region
/kingdoms settlement plan-region [radius] # background; a completion message follows
/kingdoms settlement materialize <uuid>
/kingdoms settlement site [type]          # site analysis at the command position (diagnostic)
/kingdoms settlement column               # planning heights and block stack at the position (diagnostic)
/kingdoms settlement stats
```

`plan-region` performs logical planning only; radius is measured in settlement regions and is bounded to 0-8. `site` and `column` create nothing; use them with `/execute positioned <x> <y> <z> run ...` to explain a location.
