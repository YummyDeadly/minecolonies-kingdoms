# Dedicated-server smoke test

## Environment

- Windows development run through NeoGradle `runServer`
- Temurin Java 21.0.12
- Minecraft 1.21.1 / NeoForge 21.1.80
- MineColonies `1.1.1396-1.21.1-snapshot`

## Baseline run

Before the simulation changes, the dedicated server discovered MineColonies, Kingdoms, Structurize, BlockUI, Domum Ornamentum, and MultiPiston. It logged Kingdoms initialization on `DEDICATED_SERVER`, registered MineColonies public API hooks, and reached the normal `Done` state in 4.774 seconds. No client-only classloading, mapping, dependency, or addon startup errors occurred.

The first run emitted the expected bootstrap warning for a missing `server.properties`; NeoForge generated the file and continued normally.

## Post-change run

The final server run completed on 22 September 2026. It discovered Kingdoms `0.1.0-SNAPSHOT` and the full MineColonies dependency set, initialized Kingdoms on `DEDICATED_SERVER`, registered the public API event hooks and `/kingdoms` commands, then reached `Done (3.573s)`. Immediately afterward it initialized `KingdomsSavedData` at schema 2 with zero tracked colonies. The generated server config was present at `runs/server/config/minecolonies_kingdoms-server.toml`.

`latest.log` contained no `ERROR`, Kingdoms exception, side-only classloading failure, or missing mapping/class failure. Remaining warnings came from the development environment, Structurize pack discovery, offline mode, and MineColonies compatibility discovery.

The Gradle development console did not forward the typed `stop` command to the nested Minecraft process, so the completed smoke run was terminated with Ctrl+C after verification. No process remained listening on port 25565.

## Automated verification

`gradlew test` ran 14 tests with zero failures/errors. Coverage includes economy flow/reserves, needs, decisions, scheduler batch limits, colony/economy NBT round-trip, root SavedData round-trip, and v1-to-v2 migration. A subsequent `gradlew build` completed successfully.

## Schema v3 and abstract-trade runtime scenario

On 22 September 2026 a second dedicated-server scenario used the normal registered commands through a temporary local RCON dev connection. The server reached `Done`, initialized SavedData schema 3, and loaded with no Kingdoms errors.

The scenario created strategic `Greyholm` and `Ravenport` colonies without MineColonies Town Halls. Greyholm was configured with FOOD `stock=2000, production=300, consumption=100, reserve=800`; Ravenport used `stock=100, production=50, consumption=180, reserve=900`.

Manual matching created one unique FOOD route and a planned shipment of 300. Departure reduced Greyholm from 2000 to 1700 and changed the shipment to `IN_TRANSIT`. Arrival increased Ravenport from 100 to 400 and marked the shipment `DELIVERED`. The next cycle created another 300 shipment while the deficit remained.

After `save-all`, graceful `stop`, and a new server process, schema 3 loaded two colonies, one route, and both shipments. The shipment that had been in transit completed after restart, producing Ravenport stock 700. Both shipments were terminal `DELIVERED`; neither was applied twice. The expanded suite now contains 25 passing tests, including deterministic matching, reservation accounting, full restart round-trip, and deletion refund behavior.

A final launch using the finished code loaded the same ledger, completed the remaining 208-unit need as a third shipment, and reconstructed `resources moved=808` from persisted delivered cargo. It then completed another graceful save and stop. The temporary RCON listener/password used only to exercise console commands was removed from the ignored dev configuration afterward.

## Phase 5 fixed-seed dedicated scenario

Phase 5 was tested in a separate `phase5-test-optimized` world with seed `5318008`; the prior `world` directory was not opened or modified. The server reached `Done`, loaded the then-current schema 5, and registered all settlement/road commands.

`/kingdoms settlement plan-region 1` planned nine regions in 1.436 seconds and produced three deterministic settlements and two roads. Repeating the command after a graceful save/restart returned zero new settlements; the same three settlement UUIDs and two road UUIDs remained. Materializing `Westholm P0N1` changed 1,257 blocks, persisted 42 chunk markers, and reached `GENERATED`. A direct block predicate confirmed the plaza center was `minecraft:stone_bricks` at the terrain surface.

For the 1,226-block stone road, chunk-local regeneration at 10%, 25%, 50%, 75%, and 90% changed 93, 64, 99, 99, and 106 blocks respectively. After loading those five sample chunks, `/kingdoms road info` reported `minecraft:cobblestone` at all five persisted polyline samples.

A FOOD shipment from Westholm to Highhaven resolved as `Path=ROAD`, length `1370.7`, speed multiplier `1.45`, and printed both its current path position and next waypoint. Loading the current path-position chunk allowed manual materialization. `/kingdoms caravan list` then reported one travelling caravan with four entities and the same authoritative shipment progress. The group later safely dematerialized when no player observed it.

All test force-load markers were removed, the server saved and stopped normally, and `server.properties` was restored to `level-name=world`, blank seed, and disabled RCON.

The final clean Gradle build for that phase ran 40 tests with zero failures or errors and produced `build/libs/minecolonies_kingdoms-0.1.0-SNAPSHOT.jar`.

## Phase 6 fixed-seed dedicated scenario

Phase 6 was tested in a new `runs/server/phase6-test` world with seed `5318008`; `runs/server/world` was not opened or modified. The server reached `Done` on every launch, loaded SavedData schema 6, and the final `latest.log` contained no `ERROR`, Kingdoms exception, or failed startup line. The temporary RCON listener was used only during the scenario and was disabled again before handoff.

The baseline `/kingdoms settlement plan-region 1` created the same three deterministic settlements and two roads as Phase 5. Repeating the command after save/restart returned `new settlements=0`; the original settlement and road UUIDs were unchanged. This is the Phase 5 regression gate for the growth release.

Growth was then exercised on Westholm (`2e8e0cca-2ac6-34e8-896a-7399cf480302`):

- `/kingdoms growth evaluate` selected a deterministic `FARM` for the critical food shortage.
- Repeating the evaluation before materialization returned `logical construction pending` and did not create a duplicate.
- `/kingdoms growth materialize <settlement-uuid>` changed 204 blocks for the first farm and 217 for the second farm; repeating either command changed zero blocks.
- After restart, both stable building UUIDs remained `COMPLETED` with the same anchors and persisted chunk markers.
- A direct predicate checked `minecraft:water` at `189,64,-64` and farmland at `190,64,-64`; both passed. The irrigation-grid farm layout prevents the farmland from drying during the test.
- `/kingdoms growth info` reported the exact placed sample block, and `/kingdoms growth stats` remained bounded by the configured per-cycle limits.

Road-aware ETA was verified with a FOOD shipment from Westholm to Highhaven. The shipment printed `Path=ROAD`, length `1370.7`, speed multiplier `1.45`, and `duration=2091`; the old direct-distance estimate would have been `2700`. The shipment materialized while its current chunk was loaded, reported four physical entities and road progress, then dematerialized safely. On the following restart the persisted representation was abstract/terminal with zero physical caravans; the cargo was delivered once and not duplicated.

The final dedicated checks were `/kingdoms debug` (schema 6, growth state/building counts), `/kingdoms settlement plan-region 1` (zero new settlements), `/forceload query` (none), and `/tick query` (running normally). The server was saved and stopped gracefully, and root `runs/server/server.properties` was restored to `level-name=world`, blank seed, and disabled RCON.

## Phase 6 automated verification

`gradlew clean test build` is the release gate. The suite covers deterministic growth decisions and insertion order, stable building IDs and plot exclusions, logical-stage idempotency, persisted chunk markers, restart round-trip, population caps/requirements/critical-food blocking, derived-effect delta reconciliation, v5-to-v6 migration, direct road ETA, stone-road speed, and multi-edge effective distance. The final run must report 54 tests with zero failures or errors and produce `build/libs/minecolonies_kingdoms-0.1.0-SNAPSHOT.jar`.

## Phase 6.5 fixed-seed dedicated scenario

Phase 6.5 was tested headlessly in a separate `runs/server/phase65-test-accepted` world with seed `5318008`; the normal `runs/server/world` directory was not opened or modified. The runtime structure catalog discovered 319 compatible blueprints in 23 installed style packs through public Structurize APIs.

Planning region radius 1 reproduced the three deterministic settlements. Road Geometry V2 produced one physical royal edge (`189ae443-44c9-313a-b6b8-5ae6d36e05e2`) with 1,182 persisted points: 851 `GROUND`, 159 `GRADED`, and 172 `BRIDGE`. A second unsafe connection remained explicitly non-physical `UNROUTABLE`; no direct physical fallback was created.

Westholm (`2e8e0cca-2ac6-34e8-896a-7399cf480302`) selected and persisted building `67c3b547-e484-3161-a334-ca1ecf374369` from source `structurize:Colonial`, path `fundamentals/residence1.blueprint`, with anchor `301,68,-118`, entrance `294,68,-116`, and footprint `293,-127` through `308,-112`. Its persisted Colonial layout contained two street segments, one lot, and a join at `255,63,-118`.

Explicit materialization processed 4,351 blueprint blocks across four chunks and reached `COMPLETED`. Repeating the command processed zero blocks. After graceful save/restart, schema 7 loaded the same building UUID, source, style, entrance, footprint, completed status, four chunk markers, layout, and road geometry counts. Repeating region planning returned zero new settlements, and `/forceload query` reported no forced chunks. Additional settlements recorded while ordinary test chunks loaded are expected from the existing automatic world-planning hook.

The server stopped gracefully, and `server.properties` was restored to `level-name=world`, blank seed/password, and disabled RCON. This was a dedicated-server acceptance run only: camera-level appearance, collision/clearance, bridge walkability, and in-client style coherence still require the manual graphical checklist.

## Phase 6.5 automated verification

The release gate is `gradlew clean test build`. The expanded suite contains 63 tests, covering deterministic structure catalog/transform selection, persisted layout streets/lots, schema-v7 compatibility, Road Geometry V2 ground/graded/bridge/unrouteable behavior, and asynchronous catalog lifecycle regression cases. The expected artifact remains `build/libs/minecolonies_kingdoms-0.1.0-SNAPSHOT.jar`.

## Phase 6.5 integrated-client deadlock regression

The original Phase 6.5 implementation synchronously called `MineColoniesStructureSource.scan` from Kingdoms' `ServerStartedEvent`. In Structurize 1.0.832, `StructurePacks.getBlueprints` calls `waitUntilFinishedLoading`, backed by `ManualBarrier`. On an integrated client the render thread waits for integrated-server startup, while Structurize opens that barrier from `ClientStructurePackLoader.onWorldTick` only after a client level exists. The server thread waiting inside the scan therefore prevented the client lifecycle needed to release it.

An exact bytecode/source audit found no public pack-loaded event or callback in Structurize 1.0.832. `ClientStructurePackLoader.loadingState` and `ServerStructurePackLoader.loadingState` are public volatile readiness indicators, but they are side-specific and do not provide a common callback suitable for Kingdoms server code. Dedicated loading opens the barrier in the asynchronous completion path of `ServerStructurePackLoader.onServerStarting`; integrated singleplayer opens it in `ClientStructurePackLoader.onWorldTick` after `Minecraft.level` becomes non-null. `StructurePacks.getBlueprintsFuture` only moves the same blocking accessor onto Structurize's IO pool and is not a completion callback.

Kingdoms now starts one daemon `Kingdoms-Structure-Catalog` executor and exposes explicit `UNINITIALIZED`, `LOADING`, `READY`, and `FAILED` states. Only that worker awaits the barrier and scans packs. It reads captured registry access but never mutates `ServerLevel` or `SavedData`; it atomically publishes a fully constructed immutable catalog. Duplicate initialize calls reuse one completion future. Shutdown invalidates the generation, signals cancellation, interrupts the scan, joins the executor, and prevents a late result from being published. Growth requiring a blueprint reports `STRUCTURE_CATALOG_LOADING` until publication and never creates a procedural fallback.

The integrated regression used `runs/client/saves/phase65-integrated-5318008`, copied from the freshly created deadlock-reproduction world `New World`. Minecraft's own NBT API confirmed the source `level.dat` seed was `5318008`. The reproducible launch command was:

```powershell
.\gradlew.bat runClient --no-daemon -PquickPlaySingleplayer=phase65-integrated-5318008
```

The client passed the loading screen into the actual world. The log recorded integrated-server start at `18:14:23`, schema-7 SavedData initialization at `18:14:27`, and `Dev joined the game` at `18:14:29`. Only afterward, at `18:14:33`, the catalog worker reported 319 compatible structures from 23 packs and transitioned to `READY`. This ordering is the regression proof: world creation completed before the blocking catalog scan finished, so the old server/render lifecycle cycle no longer exists. `latest.log` contained no `ERROR`, exception, or failed line for the accepted run.

Three service regression tests cover duplicate initialization/single-scan publication, explicit failure and restart-after-shutdown, and cancellation with no live catalog worker. The complete suite now contains 63 tests.

## Phase 6.6 dedicated-server scenario

Run on 22 September 2026 with temporary worlds `runs/server/phase66-test`, `-b` … `-h` (seed `5318008`) through a temporary local RCON listener. The normal `runs/server/world` was never opened. `server.properties` was restored afterwards (`level-name=world`, blank seed, RCON disabled). All force-load tickets were removed, and no Kingdoms config file was changed.

The earlier runs found real defects, which were fixed before the accepted run (`phase66-test-h`):

| Finding | Cause | Fix |
|---|---|---|
| `plan-region 1` blocked the server thread for 5.3-7.7 s | generator `getBaseHeight` costs ~2 ms per column | region/road planning moved to the `Kingdoms-Settlement-Planner` worker; coarse broad phase; 9x9 grid; memo |
| 0 settlements in 9 regions | shoreline-heavy spawn area; 4 random points; 12-18% water caps | centroid re-centring; one waterfront side allowed (islands/thin shores still rejected by buildable/connected minima) |
| `NO_VALID_STARTER_PAD:HOUSE` on good land | pad budget used up by overlapping pads; recessed MineColonies doors boxed in by their own footprint | distinct-pad budget; street starts at first column outside the footprint |
| `EXCESSIVE_CUT` at Highhaven | loaded heightmap Y = top block while generator Y = first free block (off by one); sunflowers counted as ground by the physical check | one height convention; one shared "not ground" predicate |
| literal `structurize:blocksolidsubstitution` under a house | `CreativeStructureHandler(fancyPlacement=false)` does not resolve placeholders | fancy placement |
| growth farm in front of the west gate | no gate-approach reservation (roads were `UNROUTABLE`) | 32x11 gate-approach corridor (added after the accepted run; covered by unit tests, not re-run on the server) |

Accepted results:

- Startup `Done` in ~4-5 s; SavedData schema 8. The catalog indexed 319 compatible structures from 23 packs and reached `READY` on its worker.
- Background `plan-region 1`: worker 4.2 s, server-thread commit 3.4 ms. Background `plan-region 2`: worker 11.4 s, commit 0.86 ms. Region results were identical across worlds d-h. Site rejections were reported, for example `{DISALLOWED_BIOME=11, WATER=14, SLOPE=1, CLIFF=11}`.
- **Oakwatch P0P0** (`aefd45cb-648f-3613-aa34-0bdaf29583ed`, village, anchor 94,64,294): starter planned in 118 ms. Template `village-green`, style `Caledonia`, four real blueprints (`fundamentals/builder1`, `fundamentals/residence1`, `craftsmanship/storage/warehouse2`, `agriculture/horticulture/farmer1`) on four levels (y 63, 64, 68, 69; `NATURAL`, `CUT_AND_FILL`, `TERRACE`, `TERRACE`). Plaza, 26-point gate street with 6 stair steps, and four building streets. `settlement materialize` changed 19,399 blocks; repeating it changed 0.
- **Highhaven P1N1** (`1b1c6fff-e829-30e8-8d50-d747cb3d7b6e`, castle): `garrison-court`, `Medieval Birch`, three buildings (`FILL`, `CUT_AND_FILL`, `CUT`), all `COMPLETED`, 24,446 blocks; repeating it changed 0.
- `growth grow <Oakwatch> 3` added three `Caledonia` farm blueprints on their own terraces (7 buildings, stage `ESTABLISHED`); all streets are joined to the network.
- Block-entity safety (world e): a chest placed inside a planned lumber-yard footprint caused `BLOCKED: BLOCK_ENTITY ... chunk [5, 21]` with 0 blocks changed and the chest intact. After removing it, the operator retry built the lumber yard (2,200 blocks); a third run changed 0.
- Restart (world h): after save/stop/start, schema 8 loaded the same building UUIDs, types, statuses, and anchors (diffed). `plan-region 1` created nothing, and re-materializing both settlements changed 0 blocks. Street chunk markers were persisted (25).
- Placement alignment was checked with `/kingdoms settlement column`: house floor at y 62, pad/walking level 63, street surface 62, resolved `dirt` foundation below.
- Autonomous growth with no player near the settlement reported `NO_VALID_LOT:LUMBER_YARD[... UNLOADED_TERRAIN=192 ...]` and did not guess or freeze.
- `latest.log` for every run: no `ERROR`, no exception. The only "Can't keep up" warnings came from explicit operator commands that generated 121-240 brand-new chunks synchronously (`settlement materialize` from far away, `/forceload add`).

## Phase 6.6 integrated-client startup regression

A copy of the accepted smoke world (`runs/client/saves/phase66-restart-check`) was opened with `runClient -PquickPlaySingleplayer=phase66-restart-check`. The integrated server started at 21:50:27, SavedData schema 8 initialized at 21:50:28, and `Dev joined the game` at 21:50:30. Only afterwards, at 21:50:37, did the catalog worker report 319 structures and `READY`. World entry therefore still never waits for Structurize. The only log errors were offline Mojang authentication lookups. The client was closed after the check. **No visual acceptance was performed by the agent.**

## Phase 6.6 automated verification

`gradlew clean test build` (fresh, `--no-build-cache --rerun-tasks`) is the release gate. The final run (after acceptance round 1) reported **108 tests, 0 failures, 0 errors, 0 skipped** (after acceptance round 2) and produced `build/libs/minecolonies_kingdoms-0.1.0-SNAPSHOT.jar`. New coverage:

- area site analysis: flat, island, shoreline, mountain, terraced hill, determinism, coarse broad phase, centroid refinement;
- cut, fill, cut-and-fill, and terrace pads with bounded depth/volume, exact fill support, and specific rejection reasons;
- deterministic, style-coherent starter districts from real descriptors, including multiple styles, a hill with several levels, a plaza and gate street, negative coast/mountain with numeric blockers, site rejection, and an empty catalog (no procedural fallback);
- hierarchical lot search: lake-side regression, recessed door, multiple buildings without building/street collisions, hopeless terrain with bounded sampling, plaza/gate-approach/global-road/local-street reservations, stair facing, and street chunk markers;
- unloaded terrain is never guessed and a starter failure caused by it is transient;
- memoized, order-independent region planning (asynchronous commit);
- fresh-chunk ownership bounds, operator retry keeping chunk markers;
- the v7 → v8 migration (IDs, shipment timing/progress, origins, templates), a byte-identical schema-8 round trip, and a re-plan exactly once after a planner upgrade;
- the existing asynchronous catalog lifecycle tests.

## Phase 6.6 client acceptance round 1 (user report)

The user's client run (seed `5318008`) confirmed real Caledonia/Medieval Birch blueprints, one style per settlement, plaza, gate streets with stairs, terraces, no procedural boxes, idempotent re-materialization, and a `CLIFF` rejection on a mountainside. It also showed four defects, all fixed and re-verified on a dedicated server (world `phase66-test-i`):

| Observation | Cause | Fix and verification |
|---|---|---|
| Highhaven grew 9 farms in a row to the 12-building cap | a structural food deficit kept the need-driven choice on FARM | per-type saturation + style-aware roles; the rerun grew lumber yard, quarry, farm, market, house, storehouse |
| growth stopped with "style has no compatible smithy blueprint" (found during the fix) | diversification picked a role missing from the style | decisions skip roles without a blueprint in the persisted style |
| bare dirt/stone scars and grey cobblestone rims around pads | cut columns lost their top block; 1-block retaining edges were capped with cobblestone | original top block restored; retaining faces only for drops of 2 or more and capped with topsoil; `settlement column` showed `grass_block` over stone/dirt/cobblestone on cut, fill, and edge columns |
| lava flowing down a cut hillside | a cut opened a hidden vanilla lava spring | lava in the shaped volume, and next to street headroom cuts, is sealed with cobblestone |
| every road `UNROUTABLE: graded cut/fill exceeds bound` | forward-only road profile (pre-existing Road Geometry V2 limitation) | symmetric slope-limited profile; 2 of 5 edges became physical, sampled surface `cobblestone` |

Already generated blocks, and roads already persisted as `UNROUTABLE`, are not rewritten. Use a fresh world to see the fixes. `STARTER_PLANNER_VERSION` is 5, so a starter blocked in an older world is re-planned once.

## Phase 6.6 client acceptance round 2 (roads)

The user reported roads that were far too wide, made of a single block, and generated strangely, with checkerboard stretches, floating canopies, and roads crossing towns. Causes found in code and fixed:

| Observation | Cause | Fix |
|---|---|---|
| checkerboard pavement on diagonal stretches | lateral offsets `(-dz*l, dx*l)` run along the anti-diagonal and skip every other cell | rounded band coverage with nearest-centre heights |
| road raised one block above, or buried under, the grass | pavement written at the walking Y instead of Y-1; no cut above | pavement at Y-1, bounded overhang-safe cut, bounded embankment, slab ramps |
| 9-wide royal / 7-wide stone roads | `width` is a half-width but was used as the full lateral range | royal/stone 5 wide, dirt/trail 3 wide with worn edges |
| one block for a whole road | a single block per class | deterministic weighted palettes, curbs, sandy variants |
| road through the village plaza and pond | gates connected directly, ignoring settlements | straight 32-block gate exit, settlement cores avoided by the coarse A* |
| straight 64-block segments with kinks, L-shaped staircases | coarse 64-grid corridor, diagonal-first stepping | Chaikin smoothing + Bresenham |
| floating tree canopies | logs removed with `UPDATE_KNOWN_SHAPE`, so leaves never decay | whole-tree removal with block updates |
| zig-zag town streets | 4-direction A* without turn cost | turn penalty |

Dedicated verification (world `phase66-test-k`, seed `5318008`):
- **Roads:** the Oakwatch→Highhaven royal road now leaves the west gate to x≈34 and goes around the village about 60-70 blocks north of its centre. The Ravenstead road approaches Highhaven's west gate from the west.
- **Cross-sections** (`road sample`): 5-wide pavement flush with the ground; mixed `stone_bricks`/`cracked`/`mossy`/`andesite`/`cobblestone` with `polished_andesite` curbs; `stone_brick_slab` ramps on a rise; cobblestone embankment under a raised stretch; cleared headroom.
- **Growth:** Highhaven grew farm, farm, farm, lumber yard, quarry, farm, market, house.
- **Logs:** no Kingdoms errors.

User result (round 2): the roads were accepted as "perfect" and the overall settlement generation as good.

## Phase 6.7 dedicated-server scenario (physical population)

Run on 22 September 2026 (three server sessions) on `runs/server/phase67-test`, a copy of the accepted schema-8 world `phase66-test-k` (seed `5318008`), through a temporary local RCON listener. The copy also exercised the real v8 → v9 migration. `server.properties` was restored afterwards (`level-name=world`, blank seed, RCON disabled). The `/forceload` areas used to load chunks on the headless server were removed. The normal `world` was never opened.

| Check | Result |
|---|---|
| migration | schema-8 save loaded as schema 9: `rosters=0`; populations unchanged (Oakwatch 21, Highhaven 57) |
| Oakwatch (village, 4 buildings) | roster 7 (`OFFICIAL`, `PORTER`, 2 `FARMER`, 3 `RESIDENT`) limited by its buildings; 7 physical, 2 per cycle; homes only in the one `HOUSE` (4); the farmer walked a 12-waypoint street route from house to farm (waypoint 6 → 9 → wandering at the farm door) |
| Highhaven (castle, 11 buildings) | roster 16 (cap), physical 12 (per-settlement cap; target `round(2 + 1.5·√57)` = 13); roles include `GUARD` (castle only), `LUMBERJACK`, `QUARRY_WORKER`, `MERCHANT`; commutes up to 41 waypoints |
| hold expiry / far settlement | without a player, the 60 s operator hold expired and the settlement returned to abstract with 0 entities |
| `citizen dematerialize` | 8 removed, `execute if entity` found 0, population 57 unchanged |
| restart | after a stop/start, rosters persisted (2 rosters, 23 representatives, identical IDs/names); 0 citizen entities after the chunks reloaded; after materializing, exactly 12 entities = `physical=12` |
| death | `/kill` of Hilda Yeoman: `deaths=1`, she became abstract (cooldown), another roster member filled the slot, population 57 unchanged |
| evening (`time 12700`) | everyone walked home along the streets and left at the door (12 → 3 → 2 → 0); representatives without a house chatted on the plaza and left there |
| night (`time 18000`) | only the guard materialized |
| morning (`time 1200`) | 12 spawned at their doors and commuted (`going to work`, up to 41 waypoints) |
| performance | average cycle 0.45–0.8 ms every 20 ticks; the maximum (32 ms, 15 ms, and 43 ms in the three sessions) occurred once per server start, on the first activation (cold classes and JIT); later activations stayed under 15 ms |
| logs | 0 `ERROR`/exception lines in both sessions; the only "Can't keep up" came from vanilla `/forceload` loading 90 chunks |

Defects found and fixed during this scenario:

- a path goal could re-run pathfinding on every tick when a waypoint was unreachable; it is now throttled to target changes or every 40 ticks;
- spawn checks accepted loaded but non-ticking border chunks; they now require entity-ticking chunks;
- a representative whose activity was already "returning home" spawned at their door and vanished on the next cycle, over and over; nobody is spawned while home-bound;
- homeless representatives idled on the plaza all night; the plaza is now their shelter;
- the `citizens` config section was nested as `[roads.citizens]`; it is now a top-level `[citizens]`;
- the renderer hid the hat overlay, which MineColonies textures use for hair and caps.

## Phase 6.7 integrated-client check

A copy of the migrated world was opened with `runClient -PquickPlaySingleplayer=phase67-client-check`. A temporary datapack set world spawn to Oakwatch's plaza and printed the number of citizen entities to chat every 15 s. `pauseOnLostFocus` was disabled in the dev client's `options.txt` for the run and restored afterwards.

- SavedData schema 9 initialized; `Dev joined the game` at 23:50:13, before the structure catalog reported `READY` at 23:50:21, so world entry still never waits for Structurize.
- With a real player (no operator hold): `citizens=7 nearOakwatch=7` at 15 s, 30 s, 45 s, and 60 s, so the count stayed stable with no duplicates or churn.
- 0 `ERROR`/exception lines (only the pre-existing MineColonies hut-model and sound warnings).
- The check world was deleted afterwards. **No visual acceptance was performed by the agent.**

## Phase 6.7 automated verification

`gradlew clean test build --no-build-cache --rerun-tasks` reported **122 tests, 0 failures, 0 errors, 0 skipped**. New coverage:

- roster: deterministic regardless of building order; never larger than population or cap; roles only from existing workplaces; workplace type matches role; homes only in houses (≤ 4 each); guards only in castles/forts; identities kept on reconcile; assignments dropped when buildings disappear;
- budget: target formula, caps (per-settlement, roster, global, per-player), gradual allowance, hysteresis;
- safe spawn: walls, holes, building footprints, unloaded chunks, and "no safe position" failure;
- street router: waypoints stay on streets, reach the plaza, deterministic/cached, and fall back to a direct waypoint far from streets;
- stuck tracker: waypoint skips then give-up;
- schedule: work by day, home at night (also without a house), guards on duty, deterministic;
- appearance/names: deterministic, style/role/gender-based texture keys;
- persistence: roster round trip without drift and without population change, v8 → v9 migration, settlement removal cleanup, invalid entries dropped.

## Phase 6.7 client acceptance (user report)

The user reported that representatives look and behave fine overall, but two of them had broken textures (a red checkered body, visible on the merchant Nesta Yeoman in Highhaven).

- **Cause:** female `aristocrat` (merchant) and `noble` (official) textures in MineColonies are 128×128 textures for its dress models, not the 128×64 humanoid layout, so the humanoid model sampled the wrong regions. All other outfits are 128×64.
- **Fix:** women in these roles use the humanoid `settler`/`teacher` outfits. The renderer now reads each texture's PNG header once and accepts only the 2:1 humanoid layout (else the `default/` style, else a vanilla skin). A new test checks every generated key (9 roles × 8 styles × 2 genders × variants × 4 skin tones) against the installed MineColonies textures.
- A visible representative walking to its door at night was labelled "at home"; the label is now "turning in for the night".

## Phase 7 dedicated-server scenario (reputation, contracts, diplomacy)

Run on 23 September 2026 on `runs/server/phase7-test`, a copy of the schema-9 world `phase67-test` (seed `5318008`), in two server sessions through a temporary local RCON listener. `server.properties` was restored afterwards; the normal `world` was never opened.

| Check | Result |
|---|---|
| migration | schema-9 save loaded as schema 10 (`Kingdoms SavedData initialized (schema 10, 7 tracked colonies)`) |
| neighbours | 5 faction pairs from the road graph (7 settlements) |
| first evaluation | 5 pairs in 0.84 ms |
| offers | `contract refresh` on Oakwatch (population 21, all four resources CRITICAL): food 192 for 12 emeralds, wood 80 for 5, stone 80 for 3; treasury 53 → 33 (escrow). Highhaven: food 192/12, wood 160/8, stone 224/8; treasury 89 → 61 |
| restart | 6 offers, treasuries 33/61, and the last evaluation time were unchanged |
| two days (`/tick sprint 50000`) | all 6 offers withdrawn after 2 days with escrow returned (Highhaven 61 → 89); 2 automatic diplomacy evaluations (0.16 ms); relations drift one point per day towards the pair baselines; a refresh re-posted 3 offers with the next deterministic IDs and taxes credited |
| logs | 0 `ERROR`/exception lines in both sessions |

A design defect was found and fixed during this run. In the first build, relations also dropped by one point per resource that both neighbours were seriously short of. In this world every young settlement was CRITICAL on all four resources, so all neighbours drifted towards "tense", which would have stopped all trade. Shared scarcity is no longer a source of friction; pair baselines now span −30..30 instead.

## Phase 7 integrated-client contract flow

A copy of the world was opened with `runClient -PquickPlaySingleplayer=phase7-client-check`. A temporary datapack spawned the player at Highhaven, gave 64 bread, and ran the player commands as the player (`execute as @a run kingdoms contract ...`); results were read from the chat lines in the client log. `pauseOnLostFocus` was disabled for the run and restored; the check world was deleted afterwards.

| Step | Chat / log |
|---|---|
| accept food and wood contracts | "Accepted: deliver 192 food (about 39 bread) to Highhaven P1N1 within 3d 0h for 12 emeralds. [Deliver]" (and 160 wood for 8) |
| deliver wood without logs | "You carry nothing they need for this contract. They need 160 wood (about 40 logs)." |
| deliver food | 39 bread taken (25 left), "Contract fulfilled! Highhaven P1N1 pays you 12 emeralds." and "+7 reputation (7, Neutral)"; the scripted count showed `emeralds=12 bread=25` |
| abandon wood | "You abandoned the contract … −4 reputation (3, Neutral)" |
| deliver a contract never accepted | "That contract is not active." |
| kill the nearest representative (12 were present around the live player) | "The people of Highhaven P1N1 will remember this." and "−10 reputation (−7, Neutral)" |

World entry again preceded the Structurize catalog `READY`. The log had 0 `ERROR`/exception lines. **No visual acceptance was performed by the agent.** The right-click dialogue (clickable buttons) was not exercised by the script.

## Phase 7 hardening to the frozen invariants

After the first Phase 7 build, the architecture was frozen around explicit invariants and reworked:
- separate domains with single writers;
- the explicit lifecycle;
- reservation at acceptance;
- the delivery transaction with rollback and exactly-once flags;
- bounded, deduplicated generation;
- audited relation events without drift;
- schema 11.

See [DIPLOMACY.md](DIPLOMACY.md) for the data model. Verification on 23 September 2026:

| Check | Result |
|---|---|
| real migration (dedicated server, copy of the preliminary schema-10 world `phase7-test` as `phase7b-test`) | `schema 11`; Highhaven treasury 85 → 113 (the 12 + 8 + 8 that the old build escrowed for three offers returned); offers kept their IDs with nothing reserved; 5 relations logged once as `MIGRATED`; a new evaluation produced 0 events (no drift, no trade); 0 errors |
| scripted client flow on the migrated world | accept logged "12 emeralds reserved"; delivering food took 39 bread and paid 12 once (+7); a second delivery of the same contract answered "That contract is not active." with the emerald count unchanged (12); nothing to deliver for wood; abandon −4; `/kingdoms contract list` answered; kill −10; 12 representatives; 0 errors |
| restore | `server.properties` and client `options.txt` restored; client check world deleted |

## Phase 7 automated verification

`gradlew clean test build --no-build-cache --rerun-tasks` reported **142 tests, 0 failures, 0 errors, 0 skipped**. New coverage:

- tiers and stances over the whole range;
- conservative relation rules: first contact never tense, trade gain capped;
- reputation spillover (audited, once, clamped);
- relations changed only by first-contact and trade events: 30 days without trade produce no event; each shipment counts once; the audit log survives a restart; operator edits are audited;
- trade denied between tense factions;
- contract terms, delivery plans, and treasury arithmetic;
- generation: most severe first, one per resource, open cap, refresh interval, frozen objective, expiry, cooldown before an equivalent offer, new stable ID, fundability;
- reservation at acceptance with the tier multiplier, hostile refusal, limit, "council cannot pay" (the treasury never goes negative); failure and abandonment return the reservation, with counters;
- the delivery transaction:
  - stockpile credit and completion;
  - a second delivery refused, before and after a restart;
  - partial progress surviving a restart;
  - a late attempt failing without taking items;
  - an inventory change or nothing usable changing nothing;
  - a payout failure leaving the reward pending, paid exactly once after a restart, with reputation applied once;
- removed settlements cancel without penalty and return money;
- registry round trip, prefix IDs, and pruning of settled history only;
- per-player audited reputation;
- migrations: v9 → v11 (relations kept and logged, faction field removed), conversion of preliminary v10 saves (standings moved, statuses mapped, offer escrow refunded, accepted reservation kept, completed marked paid), and a schema-11 round trip without drift.

## Phase 8 dedicated-server scenario (bandits, threats, caravan ambushes)

Run on 23 September 2026 on `phase8-test`, a copy of the accepted Phase 7 world `phase7b-test` (schema 11), with temporary RCON. `server.properties` was restored afterwards.

| Step | Result |
|---|---|
| migration | `Kingdoms SavedData initialized (schema 12, 7 tracked colonies)` |
| existing state unchanged | NBT comparison of the saved data before and after (Python NBT reader), excluding the new `bandits` key: `citizens`, `diplomacy`, `reputation`, `kingdoms`, and `caravans` identical; settlement records identical; the 9 Phase 7 contracts identical apart from the added `kind=DELIVERY`; contract format 2 → 3 |
| threat | only the 2 physical roads are tracked (the 3 `UNROUTABLE` ones are not); both had threat 0, with contributors base 5 + remoteness 25 − security 40 (village + castle) |
| shipments | `economy set <Oakwatch> FOOD stockpile 5000`, `trade match`, `trade tick`: 6 shipments in transit, one of them on both physical roads (Oakwatch → Highhaven → Ravenstead) |
| ambush 1 (abstract) | `bandit set-threat <road> 80`, `bandit spawn-test <road> ambush`: PLANNED, strength 5, at 1169,65,−528 (206 blocks from Highhaven, outside the 192 exclusion), ESCORT offer posted at Oakwatch. At progress 0.44 it became ACTIVE and the shipment was held; at `resolveAt` the abstract roll gave `BANDITS_DEFEATED` (cargo 0/260 lost). Arrival moved back by exactly the hold (2400 ticks); threat −20; road suppressed; offer cancelled |
| determinism | an independent Python re-implementation of the seed functions gave the same ambush point (7 eligible candidates, u=0.93 → k=9) and the same outcome roll (0.7395 → `BANDITS_DEFEATED`) |
| ambush 2 (abstract) | the second road of the same shipment: `CARAVAN_DELAYED`, delay 2606; arrival 72320 + 2606 + remaining travel = 75727 |
| cargo loss exactly once | a new Highhaven shipment (119 food) with a third ambush; `bandit resolve <id> partial_loss` gave `RESOLVED_BANDITS`, 48 of 119 lost. A second identical command answered "already RESOLVED_BANDITS; nothing changed". On arrival Highhaven's food rose by exactly 71 (1027 → 1098) |
| restart | stop, then start: all encounter infos, shipments, contracts, threats, and economy lines identical (diff apart from the current time) |
| natural roadblock | after its cooldown, the Highhaven–Ravenstead road (threat ≥ 70 after the operator raise) received a roadblock from `ThreatEvaluator` itself, with a CLEAR_BANDITS offer at Highhaven |
| physical (vanilla `/forceload` of the roadblock area, standing in for a player; the mod itself never loads chunks) | `bandit materialize` spawned 4 bandits; a second `materialize` still showed 4; `dematerialize` left 0; `materialize` gave 4 again, standing on the ground next to the road with none in water; `/kill` of one lowered persisted strength to 3 |
| restart while physical | after the restart, 0 bandit entities in the still force-loaded chunks; the encounter was ABSTRACT with strength 3/4 |
| physical victory | `materialize` spawned 3 again; `/kill` of all gave `RESOLVED_CARAVAN / BANDITS_DEFEATED / CARAVAN_GUARDS` (no player fought); CLEAR offer cancelled; threat −20; road suppressed |
| logs | 0 `ERROR`/exception lines in 3 sessions; the only config warning was the expected addition of the `[bandits]` section |

The force-loaded chunks also advanced road generation there (`generatedChunks` 30 → 57), and the region planner added one settlement in a newly loaded region. Both are existing Phase 6 behaviours, not Phase 8.

## Phase 8 integrated-client check

The world was prepared on the dedicated server (a copy of `phase8-test` with a roadblock from `spawn-test` at 1601,70,−1153 and its CLEAR_BANDITS offer). It was then copied to `runs/client/saves/p8-client-check` and opened with `runClient -PquickPlaySingleplayer=p8-client-check`. A temporary datapack played the scenario with the real player, and the results were read from the client log. `pauseOnLostFocus` was disabled for the run and restored; the check world was deleted afterwards.

| Step | Chat / log |
|---|---|
| player teleported 30 blocks from the roadblock | "Bandits have blocked the road ahead!"; 4 bandits (strength 4), 0 in water, at y 68–70 next to the road; the chief shown as "Bandit chief" |
| accept the CLEAR contract far from the settlement | "You must be in Highhaven P1N1 to do that." (the Phase 7 rule) |
| player 280 blocks away | 0 bandits |
| player back | 4 bandits again (no duplicates) |
| player kills all four (`damage ... by @p`) | `RESOLVED_PLAYER / BANDITS_DEFEATED / PLAYER_VICTORY`; "The road is clear of bandits."; "Highhaven P1N1 Council: +2 reputation (2, Neutral)" |
| 20 s later at the same place | 0 bandits, reputation message not repeated |
| client log | 0 exceptions; the MineColonies barbarian textures are 64×32, matching the model layer |

After the merge-blocking review fixes, both runs were repeated on the final code.

- **Dedicated server:**
  - `bandit threat` showed "ambush chance per caravan now 0%" and "next encounter: not before … (bandits suppressed)", and `contract all` described the security contracts;
  - on peaceful, `bandit materialize` was refused, and switching to peaceful while bandits were physical removed them without deaths (`dematerialized=1, deaths=0`);
  - on normal, 4 bandits spawned on open ground next to the road.
- **Client, with the contract accepted in Highhaven first:**

| Step | Chat / log |
|---|---|
| accept at Highhaven | "Accepted for Highhaven P1N1: Clear the road: 4 bandits near 1601, -1153, for 12 emeralds. The contract completes when you defeat those bandits." |
| roadblock, leave, return | 4 → 0 → 4 bandits, none in water |
| player kills all | `RESOLVED_PLAYER / PLAYER_VICTORY`; "Contract fulfilled."; "+6 reputation (6, Neutral)" (contract reputation only, no extra +2); "You receive 12 emeralds…"; emeralds 0 → 12 |
| 20 s later | 0 bandits, still 12 emeralds, no repeated messages |
| logs | 0 exceptions in the server and client sessions |

**No visual acceptance was performed by the agent.**

## Phase 8 merge-blocking review

The first pass was read-only, and each finding was then fixed with a regression test:

| Finding | Fix | Regression test |
|---|---|---|
| A physical fight with no progress was dematerialized and re-materialized in the same cycle, so its abstract resolution never ran while a player stood nearby (a stuck caravan could be held forever) | a stall now suppresses re-materialization past the deferred abstract resolution; an expired roadblock leaves after 600 idle ticks | `BanditPresenceTest.hysteresisAndStuckRecoveryLetTheAbstractRulesFinishAFight` |
| On peaceful difficulty the game removes hostile mobs every tick, and the manager respawned them every cycle (churn) | no materialization on peaceful; physical bandits leave | `indoorSpotsAndPeacefulWorldsGetNoBandits` |
| A PLANNED ambush had no hard lifetime | `EncounterService.cancelStale` expires it at its lifetime and cancels ambushes whose shipment is gone, with notifications | `staleAmbushesEndAndTheirOffersWithThem` |
| An accepted security contract could fail on the generic contract deadline while its encounter was still open | security contracts are decided only by their encounter; an orphaned one is cancelled without penalty | `securityContractsAreDecidedByTheirEncounterNotByTheClock` |
| A player's own colony next to a road was not an exclusion zone | exclusion anchors include every tracked colony centre | `aPlayerColonyNextToTheRoadIsKeptClearLikeATown` |
| Spawns could be inside roofed buildings or caves outside towns | spawns require sky light ≥ 10 | `indoorSpotsAndPeacefulWorldsGetNoBandits` |
| An accepted CLEAR contract whose roadblock expired was cancelled without penalty (a player could block the settlement's only security slot for free) | it now fails like a missed escort | `anExpiredRoadblockFailsTheAcceptedClearContract` |
| Cosmetic: death log lines for named bandits; the ambush chance was shown while the road was suppressed; roadblock info printed "cargo 0/0 null"; `contract all` printed "null 0/4" for security contracts | fixed | — |

The second pass over the fixes found no further blocker. No path was found to:
- resolve twice or lose cargo twice;
- pay a reward twice or gain reputation per kill;
- re-roll after a restart;
- resolve both physically and abstractly;
- duplicate or accumulate entities;
- load chunks, spawn in towns, or scan without bounds;
- corrupt the schema.

## Phase 8 automated verification

`gradlew clean test build --no-build-cache --rerun-tasks`: 178 tests, 0 failures, 0 compiler warnings. The Phase 8 tests are listed below.

| Test class | Covers |
|---|---|
| `BanditRulesTest` | threat target and smoothing, chance, strength and security bounds, deterministic abstract decisions (partial losses dominate; total loss only for 6+), caps |
| `EncounterPlanningTest` | busy remote roads become dangerous; quiet and `UNROUTABLE` roads do not; exclusion zones and bridges; one persisted assessment per shipment surviving restart; cooldown, suppression, and the active cap; roadblocks and road-gone cancellation |
| `EncounterResolutionTest` | activation and hold; partial loss exactly once (delivery deposits only the rest); caravan victory, delay, and total loss; restart never re-rolls; physical and abstract cannot both resolve; leaving defers the decision; gone shipments cancel; roadblock expiry and clearing |
| `BanditContractTest` | escort offers from real ambushes only once; escort completion paid once after a reload with the right reputation; failure when robbed; cancellation when others win; clear contracts at the nearest settlement; expired roadblock fails an accepted clear contract; security contracts never fail by the clock; stale ambushes end |
| `BanditPresenceTest` | no duplicates on rematerialization; orphan detection (earlier visit, other encounter, unknown, restart); per-encounter, per-player, and global caps with many players; safe spawns (dry, loaded, outdoors, outside towns and player colonies, not on players); no read of unloaded chunks; bounded work; failed spawn stays abstract with retry; hysteresis and stall recovery; timers bounded; player intervention resolves once across restarts; peaceful |
| `SchemaV12MigrationTest` | v11 → v12 (empty registry; format-2 contracts read as deliveries; shipments without loss fields) and a schema-12 round trip with a persisted loss |

## Phase 8 manual client checklist (user acceptance)

Back up the world first, or test on a copy. You need operator rights (cheats on). `<road>`, `<shipment>`, `<encounter>`, `<contract>`, and `<settlement>` are UUIDs; the first 8 characters are enough for `contract` commands.

### 0. Build and start

```powershell
.\gradlew.bat clean test build
.\gradlew.bat runClient
```

Open the world with the Kingdoms settlements (for example a copy of the one used for Phase 7).

### 1. Find a road and a caravan (A)

```text
/kingdoms road list
/kingdoms trade shipments
```

Choose a road marked `physical=true`. If no shipment is `IN_TRANSIT`, create one from a settlement at one end of that road:

```text
/kingdoms economy set <settlement> FOOD stockpile 5000
/kingdoms economy set <settlement> FOOD reserve 100
/kingdoms trade match
/kingdoms trade tick
/kingdoms trade shipments
/kingdoms trade shipment <shipment>
/kingdoms caravan info <shipment>
```

`caravan info` prints the caravan's current path position. Teleport there (`/tp @s <x> <y+3> <z>`) and watch it walk along the road (A).

### 2. Escort contract (G, part 1)

```text
/kingdoms bandit set-threat <road> 90
/kingdoms bandit spawn-test <road> ambush
/kingdoms bandit info <encounter>
```

The second command prints `Created AMBUSH <encounter> at x, y, z (PLANNED, strength N)`. Check that this point is not inside a town (B). Go to the caravan's **origin** settlement, talk to an official or a merchant, and press **Accept** on "Escort: defend the caravan…". Alternatively:

```text
/kingdoms contract offers
/kingdoms contract accept <contract>
/kingdoms reputation
```

Note your reputation with that settlement.

### 3. Ambush (B, C, D)

1. Put the caravan in front of the ambush:

   ```text
   /tp @s <caravan position from caravan info>
   /kingdoms caravan materialize <shipment>
   /tp @s <a road point about 40 blocks before the ambush point, on the caravan's side>
   /kingdoms caravan teleport-near <shipment>
   ```

2. Follow the caravan. At the ambush point the encounter becomes ACTIVE, you see "Bandits are attacking a caravan nearby!", and 2–5 bandits appear 5–10 blocks from the road.
3. Check that they are on the ground: not in water, not inside buildings, not in the town (C). Write down the coordinates (F3) of any bad spawn.
4. The caravan stops and its guards fight; the bandits attack the caravan (D).

### 4. Fight (E, F, G, H)

Kill all bandits. You should see:
- "The bandits are beaten; the caravan continues.";
- "Contract fulfilled.", the emerald reward, and the reputation line (+5 for the contract).

```text
/kingdoms bandit info <encounter>
/kingdoms trade shipment <shipment>
/kingdoms contract list
/kingdoms reputation
```

Expected:
- `RESOLVED_PLAYER`, `PLAYER_VICTORY`, you as a defender, and the contract COMPLETED;
- the shipment `IN_TRANSIT` without "Bandits took";
- the caravan walks on (F);
- the emeralds paid once (G) and the reputation changed once (H).

### 5. Save and reload (I)

Use Save & Quit, then open the world again.

```text
/kingdoms bandit info <encounter>
/execute if entity @e[type=minecolonies_kingdoms:bandit]
```

Expected: the same result lines as before. The second command answers "Test failed" (no bandits), and no reward or reputation message repeats.

### 6. Leave and return (J)

```text
/kingdoms bandit spawn-test <road> roadblock
```

It prints `Created ROADBLOCK <encounter> at x, y, z`. Then:
1. Teleport about 30 blocks from it. Bandits appear with "Bandits have blocked the road ahead!".
2. Run `/execute if entity @e[type=minecolonies_kingdoms:bandit]` and note the count.
3. Fly more than 150 blocks away, wait 10 s, then come back.
4. Run the same count command. It must be the same, with no extra group.
5. `/kingdoms bandit info <encounter>` shows `physical: N bandit(s)`.

### 7. Unattended encounter (K)

Plan an ambush for another in-transit shipment (repeat step 2 without accepting) and stay more than 100 blocks away, for example inside a settlement.

```text
/tick sprint 6000
/kingdoms bandit info <encounter>
/kingdoms trade shipment <shipment>
```

Expected: a result `by ABSTRACT_ROLL`:
- escaped, beaten off, or delayed (the shipment arrives later);
- or a partial loss ("Bandits took N"). Delivery then brings only the rest.

### 8. Regression (L)

Walk through a town and along a road: buildings, streets, roads, citizens, and the contract board look as before.

`/kingdoms bandit stats` shows the counters and the cycle time.

### What to send back

- screenshots of the ambush, the bandits, and the caravan during and after the fight;
- the output of `/kingdoms bandit info <encounter>` and `/kingdoms bandit stats`;
- `/kingdoms trade shipment <shipment>` and `/kingdoms caravan info <shipment>`;
- `/kingdoms contract list` and `/kingdoms reputation`;
- `/kingdoms settlement info <settlement>` of the origin settlement;
- coordinates of any bad spawn or pathing.

## Phase 7 manual client checklist (user acceptance)

Graphical acceptance is delegated to the user. Nothing below has been visually confirmed by the agent.

### 0. Start

Build and run the client as before (`.\gradlew.bat build`, `.\gradlew.bat runClient`). Any world with Kingdoms settlements works; existing worlds migrate to schema 10. Survival or Creative both work, and commands are needed only for the operator checks.

### 1. Contract board

Go to a generated settlement (for example Highhaven: `/tp @s 1360 100 -609`) and right-click its **official** (and a **merchant**, if the town has a market). PASS:
- the header shows your standing (Neutral);
- the list shows up to three offers with a green **[Accept]**, amount, "about N bread/logs", days, emeralds, and reputation;
- ordinary residents only say "Looking for work? Ask our official or a merchant."

### 2. Accept and deliver

Click **[Accept]** on the food contract, get bread (`/give @s bread 64`), and right-click the official again. PASS: the contract shows as **[Deliver] Food 0/192 … [Abandon]**. Click **[Deliver]**. PASS:
- about 39 bread are taken;
- the reward in emeralds appears in your inventory;
- chat shows "+7 reputation";
- other items stay untouched: golden apples, rotten flesh, enchanted or renamed items.

### 3. Partial delivery, abandonment, deadline

- Accept the wood contract and deliver fewer logs than needed. PASS: "still owed" with the remaining amount and time.
- Click **[Abandon]**; it only fills the command, so press Enter. PASS: −4 reputation, and the delivered logs are not returned.
- Optional: accept one and run `/tick sprint 80000`. PASS: "Your contract with … has expired." and −6; `/kingdoms factions` shows the reserved emeralds back in the treasury.

### 4. Reputation and consequences

`/kingdoms reputation`. PASS: your standing with every council you dealt with. Kill a representative. PASS: "will remember this", −10. With `/kingdoms reputation set <you> <faction-uuid> -70` (operator; the faction UUID is in `/kingdoms factions`): the header says Hostile, greetings are hostile, and the official refuses work.

### 5. Diplomacy (operator)

`/kingdoms diplomacy list`. PASS: neighbour pairs with value, stance, and disposition. `/kingdoms diplomacy evaluate` produces no events unless the pair traded (at most +2), and `/kingdoms diplomacy events` explains every value. `/kingdoms diplomacy set <a> <b> -30`, then run `/kingdoms trade match` (or wait): those two do not trade.

### 6. Save and reload

Save and quit, reopen. PASS: active contracts, reputation (`/kingdoms reputation`), and treasuries (`/kingdoms factions`) are unchanged; no duplicate rewards.

### What to send back

Screenshots of the dialogue (offers and your contracts), chat after deliver/abandon, `/kingdoms reputation`, `/kingdoms contract list`, and anything confusing or wrong (with the contract ID from `/kingdoms contract all` if you are an operator).

## Phase 6.7 manual client checklist (user acceptance)

Graphical acceptance is delegated to the user. Nothing below has been visually confirmed by the agent.

### 0. Build and start

```powershell
$env:JAVA_HOME = 'D:\Mods\.toolchains\temurin21\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd D:\Mods\minecolonies-kingdoms
.\gradlew.bat clean test build
.\gradlew.bat runClient
```

Use a **new** world: **Creative**, **Allow Commands: ON**, **Seed `5318008`**. An existing Phase 6.6 world also works; it migrates to schema 9 automatically.

### 1. Startup

```text
/kingdoms debug
/kingdoms citizen stats
```

PASS: `SavedData version: 9`; `physical=0` far from settlements.

### 2. Find a settlement (checks 1–2)

```text
/kingdoms settlement plan-region 1
/kingdoms settlement list
/tp @s 94 90 294
/kingdoms settlement materialize aefd45cb-648f-3613-aa34-0bdaf29583ed
/kingdoms growth info aefd45cb-648f-3613-aa34-0bdaf29583ed
```

Oakwatch should have COMPLETED buildings. For more roles, also prepare Highhaven (`/tp @s 1360 100 -609`, `/kingdoms settlement materialize 1b1c6fff-e829-30e8-8d50-d747cb3d7b6e`, `/kingdoms growth grow 1b1c6fff-e829-30e8-8d50-d747cb3d7b6e 8`).

### 3. Arrival (checks 3, 8)

`/time set 3000`, stand on the plaza, and wait about 10 s.

```text
/kingdoms citizen list aefd45cb-648f-3613-aa34-0bdaf29583ed
```

PASS: representatives appear gradually (about 2 per second) up to `physical ≤ min(population, 12)`; each shows a name when you look at them; nobody spawns in a roof, in a wall, in water, or inside a building footprint.

### 4. Workplaces (checks 4–6)

Still in daytime: farmers near the farm entrance, the porter near the storehouse, the official near the civic building, the guard (castle) at the gate, and in Highhaven also the smith, merchant (market), lumberjack, and quarry worker at their building entrances. `citizen list` shows `home=` and `work=` for each; they must match the building types.

### 5. Streets (check 7)

`/time set 0`, then watch the morning commute (`going to work`). PASS: people walk along the settlement's streets and stairs and around buildings, not through them; nobody stands stuck for long (`citizen stats` `stuckRecoveries` stays low).

### 6. Evening and night (check 9)

`/time set 11800` and watch: people with a house walk home and disappear at their door; people without a house gather on the plaza and disappear there. `/time set 18000`: only guards (castle/fort) remain. `/time set 1000`: they reappear at their doors.

### 7. Leaving and returning (checks 10–11)

Fly more than 96 blocks away (for example `/tp @s ~200 ~ ~`), then run `/kingdoms citizen stats`: `physical=0`. Come back: the same count and the same names reappear, no more than before. Repeat 3–4 times.

### 8. Interaction

Right-click a representative. PASS: they stop and look at you; chat shows name, role, settlement, current activity, and one greeting line.

### 9. Death and population (check 13)

```text
/kingdoms settlement info aefd45cb-648f-3613-aa34-0bdaf29583ed
/kill @e[type=minecolonies_kingdoms:settlement_citizen,limit=1,sort=nearest]
/kingdoms citizen stats
/kingdoms settlement info aefd45cb-648f-3613-aa34-0bdaf29583ed
```

PASS: `deaths=1`; `population=` is the same before and after; that person reappears after about 60 s.

### 10. Save and reload (check 12)

Esc → *Save and Quit to Title*, reopen, stand on the plaza, wait 10 s:

```text
/execute if entity @e[type=minecolonies_kingdoms:settlement_citizen]
/kingdoms citizen stats
```

PASS: the entity count equals `physical=`; the names are the same as before; no duplicates.

### 11. Several settlements (check 14)

Visit Oakwatch and Highhaven alternately. PASS: `physical` never exceeds 24 for you (64 in total), and `execute if entity` always equals `physical`.

### 12. Performance (check 15)

F3 for FPS, `/tick query` for MSPT, and `/kingdoms citizen stats` for `avg`/`max` cycle time. PASS: no noticeable FPS drop, MSPT well under 50, `avg` around 1 ms or less.

### What to send back

Screenshots (plaza with people, farm workers, the evening walk home, anything wrong); output of `/kingdoms citizen stats`, `/kingdoms citizen list <settlement-uuid>`, and `/kingdoms settlement info <settlement-uuid>` (logical population); the physical count; for every stuck or badly spawned person, their `/kingdoms citizen info <representative-uuid>` output and coordinates.

## Phase 6.6 manual client checklist (user acceptance)

Graphical acceptance is delegated to the user. Nothing below has been visually confirmed by the agent.

### 0. Build and start

```powershell
$env:JAVA_HOME = 'D:\Mods\.toolchains\temurin21\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd D:\Mods\minecolonies-kingdoms
.\gradlew.bat clean test build
.\gradlew.bat runClient
```

Create a new world: **Creative**, **Allow Commands: ON**, **Seed `5318008`**, default world type. Wait about 10 s after spawning.

### 1. Startup and catalog

```text
/kingdoms debug
/kingdoms growth catalog
```

PASS: `SavedData version: 8`; the catalog shows `state=READY descriptors=319` (or a similar non-zero count for your packs). If it says `LOADING`, wait a few seconds and repeat. The world must have loaded without hanging.

### 2. Plan and list

```text
/kingdoms settlement plan-region 1
/kingdoms settlement list
```

`plan-region` answers immediately and posts a second message ("Planned region radius 1: background regions=... settlements=...") a few seconds later. If automatic planning already covered the regions it answers "already planned". With default config the list contains:

- `Oakwatch P0P0 aefd45cb-648f-3613-aa34-0bdaf29583ed VILLAGE @ 94,64,294`
- `Highhaven P1N1 1b1c6fff-e829-30e8-8d50-d747cb3d7b6e CASTLE @ 1360,66,-609`

If your config differs, use any VILLAGE/TOWN from your list and substitute its UUID below.

### 3. Positive starter (Oakwatch)

```text
/tp @s 94 90 294
```

Wait 5-10 s for chunks to load. Autonomous generation may already build the starter while chunks load; that is expected. Then:

```text
/kingdoms settlement info aefd45cb-648f-3613-aa34-0bdaf29583ed
/kingdoms settlement materialize aefd45cb-648f-3613-aa34-0bdaf29583ed
/kingdoms growth layout aefd45cb-648f-3613-aa34-0bdaf29583ed
/kingdoms growth info aefd45cb-648f-3613-aa34-0bdaf29583ed
```

`growth layout` lists every lot with its footprint, pad mode, and target Y, and every street with its endpoints. Use those coordinates for inspection. In the agent's dedicated run: plaza around 94,64,294, gate at 66,68,294, builder hut at 72..84 / 274..286 (y 69), house at 109..121 / 288..306 (y 63), warehouse at 82..100 / 306..324 (y 64), farm at 55..67 / 303..315 (y 68).

PASS criteria (walk and fly around):

- no old wooden procedural boxes; 2-4 real MineColonies blueprints in one coherent style;
- a stone plaza at the anchor and a gate street with two stone-brick gate posts with lanterns;
- every entrance → street → plaza, and plaza → gate street → gate;
- each building sits on its own pad: no floating corners, no buildings sunk more than their foundation, no giant dirt/cobblestone columns, no cut faces taller than about 4-5 blocks, no single huge flattened platform;
- level changes on streets use cobblestone stairs or single-block steps;
- no tree trunks standing in streets; no floating trunks cut in half inside a pad;
- no ordinary path across deep water (short bridges only, with railings);
- no `structurize:blocksolidsubstitution` / placeholder blocks visible anywhere.

### 4. Idempotency

```text
/kingdoms settlement materialize aefd45cb-648f-3613-aa34-0bdaf29583ed
```

PASS: `buildings=0 changed blocks=0`, and nothing visibly changes.

### 5. Growth to 5-7 structures (stand inside the settlement)

```text
/kingdoms growth grow aefd45cb-648f-3613-aa34-0bdaf29583ed 3
/kingdoms growth info aefd45cb-648f-3613-aa34-0bdaf29583ed
/kingdoms growth layout aefd45cb-648f-3613-aa34-0bdaf29583ed
```

Growth follows the colony's needs; a food-starved village may add several farms. If you get `Growth stopped: NO_VALID_LOT:...[... UNLOADED_TERRAIN=...]`, move closer to the settlement centre and repeat.

PASS: new buildings are outside the starter area, do not overlap streets or each other, leave the approach in front of the gate open, each has a street to the network, and all use the same style.

### 6. Terraces / hill behaviour

Look at Oakwatch from the gate side: the builder hut and farm stand on terraces about 4-5 blocks above the plaza, joined by stepped streets. The castle (`/tp @s 1360 100 -609`, then `/kingdoms settlement materialize 1b1c6fff-e829-30e8-8d50-d747cb3d7b6e`) uses fill/cut pads on uneven ground. PASS: local shaping per building, not one flattened town.

### 7. Negative terrain

- In your earlier random-seed world with **Oakholm**, run `/kingdoms settlement list`, then `/kingdoms settlement info <oakholm-uuid>` and `/kingdoms growth layout <oakholm-uuid>`. The planner version changed, so the blocked starter is re-planned exactly once. Expected: either still `STARTER_TEMPLATE_BLOCKED:...` with rejection counts in brackets (for example `WATER=`, `CLIFF=`, `ROUGHNESS=`, `BUILDING_COLLISION=`), or a starter built only on safe pads. Never procedural boxes, never floating or buried buildings.
- On a steep mountainside or a narrow beach anywhere: `/kingdoms settlement site village` (or `site town`). PASS: `accepted=false` with a reason (`CLIFF`, `SLOPE`, `WATER`, `INSUFFICIENT_BUILDABLE_AREA`, ...) and the numeric metrics.

### 8. Save and reload

Esc → *Save and Quit to Title*, reopen the same world, then:

```text
/kingdoms debug
/kingdoms settlement list
/kingdoms growth info aefd45cb-648f-3613-aa34-0bdaf29583ed
/kingdoms settlement materialize aefd45cb-648f-3613-aa34-0bdaf29583ed
```

PASS: the same settlement UUID, building UUIDs, style, template, and lots; `changed blocks=0`; no duplicate structures and no new terrain work.

### 9. Global roads (regression)

```text
/kingdoms road list
```

Around spawn on this seed both edges are `UNROUTABLE` (non-physical by design). After `/kingdoms settlement plan-region 2`, `road list` may show a `physical=true` edge; use `/kingdoms road info <road-uuid>` for sample coordinates and check that the road surface/bridges look as in Phase 6.5 and that local streets do not overwrite it.

### 10. Optional block-entity check

Run `/kingdoms growth evaluate aefd45cb-648f-3613-aa34-0bdaf29583ed`, find the new `PLANNED` building's footprint in `growth info`, place a chest inside it, and run `/kingdoms growth materialize <building-uuid>`. PASS: `status=BLOCKED ... BLOCK_ENTITY`, 0 blocks changed, chest untouched. Remove the chest and repeat: the building is built.

### What to send back

Screenshots (plaza, gate, each building's base/edges, stairs, any defect); the output of `/kingdoms growth layout <uuid>`, `/kingdoms growth info <uuid>`, and `/kingdoms settlement info <uuid>`; any blocker text; coordinates of every visual defect (optionally with `/execute positioned <x> <y> <z> run kingdoms settlement column` at that spot).

## Phase 6 manual client checklist

Back up an existing world first. Use a new creative test world with seed `5318008`; keep `settlement.modifyExistingChunks=false` while checking existing-world safety.

Build and start the development server:

```powershell
$env:JAVA_HOME = 'D:\Mods\.toolchains\temurin21\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build
.\gradlew.bat runServer
```

Run the operator commands:

```text
/kingdoms debug
/kingdoms settlement plan-region 1
/kingdoms settlement list
/kingdoms growth stats
/kingdoms growth list
/kingdoms growth evaluate
/kingdoms growth tick
```

Copy a settlement UUID from `settlement list` and inspect it:

```text
/kingdoms growth info <settlement-uuid>
/kingdoms growth materialize <settlement-uuid>
/kingdoms growth info <settlement-uuid>
```

Expected visual result: the settlement keeps its starter plaza/walls, the new building sits outside the starter footprint and gate corridor, farms have visible irrigating water channels, and roads remain readable through the settlement. Run `growth materialize` a second time; it must be idempotent. Walk around the generated plot in a client and verify that no existing block entity was overwritten.

For a restart check, use `save-all`, stop normally, restart, then run `growth info`, `growth list`, `settlement plan-region 1`, and `debug`. Stable building IDs/statuses and chunk markers must remain, and planning must create nothing new.

For a road ETA/caravan check, configure an exporter/importer as documented in the Phase 5 checklist, run `trade match` and `trade tick`, then inspect `caravan info <shipment-uuid>` and `trade shipment <shipment-uuid>`. The output must include `Path=ROAD`, a speed multiplier, and a duration derived from the road's effective per-edge distance. Load the printed current path chunk, materialize once, and confirm one leader, one carrier, two guards, then dematerialize and remove tickets with `/forceload remove all`.

The following acceptance items are intentionally manual-client checks and were not claimed by the headless dedicated run: camera-level visual appearance, walking collision/clearance, and confirmation that a player-built block entity remains untouched. Phase 6 has no construction-resource charge or reservation, so a construction-spending double-charge test is not applicable; stockpile mutations remain owned by `EconomyManager`.

## Phase 5 manual client checklist

Back up an existing world first. Prefer a new creative test world with seed `5318008`. Keep `settlement.modifyExistingChunks=false` while validating existing-world safety.

Build and start the development server:

```powershell
$env:JAVA_HOME = 'D:\Mods\.toolchains\temurin21\jdk-21.0.12.1+1'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean test build
.\gradlew.bat runServer
```

Run these commands as an operator:

```text
/kingdoms debug
/kingdoms settlement region
/kingdoms settlement plan-region 1
/kingdoms settlement list
/kingdoms settlement locate
/kingdoms settlement stats
```

Copy one UUID from `settlement list` and inspect/materialize it:

```text
/kingdoms settlement info <settlement-uuid>
/kingdoms settlement materialize <settlement-uuid>
/kingdoms settlement info <settlement-uuid>
```

Expected result: the second info shows `GENERATED`, a non-zero generated-chunk count, a stone plaza/paths and four timber starter buildings are visible, and a castle/fort also has its perimeter wall. Run `materialize` again: it must be idempotent and report zero or only genuinely missing block work.

Inspect roads:

```text
/kingdoms road list
/kingdoms road info <road-uuid>
/kingdoms road nearby 2048
/kingdoms road route <first-settlement-uuid> <second-settlement-uuid>
```

`road info` prints 10/25/50/75/90 percent sample coordinates. At each printed coordinate run:

```text
/execute positioned <x> <y> <z> run kingdoms road regenerate-segment
```

Then keep those chunks loaded temporarily and repeat `road info`:

```text
/forceload add <x> <z>
/kingdoms road info <road-uuid>
```

Expected result: loaded samples report a road-palette block (`dirt_path`, `coarse_dirt`, `cobblestone`, `stone_bricks`, or a bridge plank), the path follows terrain, vegetation is cleared only in a narrow corridor, and water crossings are narrow decks. Remove test tickets afterward:

```text
/forceload remove all
```

To test an explicit edge and segment idempotency:

```text
/kingdoms road connect <first-settlement-uuid> <second-settlement-uuid>
/kingdoms road connect <first-settlement-uuid> <second-settlement-uuid>
/kingdoms road regenerate-segment
```

The second `connect` must return the same UUID and must not create a duplicate.

To test road-backed trade, choose two connected settlement UUIDs. Configure one exporter and one importer:

```text
/kingdoms economy set <origin-uuid> food stockpile 5000
/kingdoms economy set <origin-uuid> food reserve 100
/kingdoms economy set <origin-uuid> food production 100
/kingdoms economy set <origin-uuid> food consumption 0
/kingdoms economy set <destination-uuid> food stockpile 0
/kingdoms economy set <destination-uuid> food reserve 1000
/kingdoms economy set <destination-uuid> food production 0
/kingdoms economy set <destination-uuid> food consumption 100
/kingdoms trade match
/kingdoms trade tick
/kingdoms trade shipments
```

Copy the new shipment UUID and inspect it:

```text
/kingdoms caravan info <shipment-uuid>
```

Expected result: output includes `Path=ROAD`, `pathPosition`, `nextWaypoint`, and a road speed multiplier. Load the chunk containing the printed current path position, then materialize:

```text
/forceload add <path-position-x> <path-position-z>
/kingdoms caravan materialize <shipment-uuid>
/kingdoms caravan info <shipment-uuid>
/kingdoms caravan list
```

Expected result: representation is `PHYSICAL`, four entities exist, and the next waypoint remains on the road polyline. Finish safely:

```text
/kingdoms caravan dematerialize <shipment-uuid>
/forceload remove all
/save-all
/stop
```

Restart the same world and repeat:

```text
/kingdoms debug
/kingdoms settlement list
/kingdoms road list
/kingdoms settlement plan-region 1
```

Expected result: schema is 5, UUID/counts are unchanged, generated states/markers survive, and planning the same regions creates nothing new.

For an existing-world protection check, keep `modifyExistingChunks=false`, enter already explored chunks, and use only `region`, `plan-region`, `list`, and `info`. Do not call the explicit mutation commands `settlement materialize` or `road regenerate-segment`. Existing blocks must remain unchanged.
