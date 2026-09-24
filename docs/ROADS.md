# Global roads

## Persistent graph

Settlement gates are graph nodes. `RoadRecord` is a stable edge with two settlement UUIDs, dimension, road type, exact persisted geometry, length, physical state, generation version, and per-chunk generation markers. Every geometry point is typed `GROUND`, `GRADED`, or `BRIDGE`. `RoadNetwork` owns the records and reconstructs a chunk-to-road spatial index after loading.

Automatic planning considers same-dimension pairs inside the hard distance. A deterministic Kruskal pass connects components while respecting maximum degree; a second local pass adds useful links inside the soft distance up to each settlement type's desired degree. Edge UUIDs are derived from the sorted endpoint UUIDs, preventing reversed or repeated duplicates.

Types are `TRAIL`, `DIRT`, `STONE`, and `ROYAL`. Each declares a half-width, a speed multiplier, and how much of its outer ring is paved: trails and dirt roads are 3 wide with worn, irregular edges; stone and royal roads are 5 wide (stone with worn edges, royal fully paved with andesite curbs). Castles create royal connections and trading towns prefer stone.

## Road Geometry V2

Road geometry starts with bounded A* on a configurable coarse grid. Cost includes horizontal distance, slope, water, and deviation from endpoint elevation. The complete coarse corridor is deterministically rasterized to block resolution, bounded by `maximumFinePathPoints`, then receives a validated vertical profile. Ground exceeding the configured grade becomes bounded cut/fill (`GRADED`); water and detected ravines become `BRIDGE` only when span and bank-height limits pass.

Failure is explicit. If search bounds are exhausted, a forbidden biome blocks the corridor, or a bridge cannot satisfy its bounds, the edge is persisted as non-physical `UNROUTABLE`. Kingdoms never disguises an unsafe straight line as a generated road. Logical graph diagnostics can still explain the edge, while chunk generation and physical road-following require valid geometry.

Physical work is chunk-local. The spatial index selects only relevant roads and the generator consumes the persisted typed points in that chunk. A bounded local refiner adjusts the surface without changing the authoritative route. It clears only configured replaceable vegetation, preserves block entities, uses road-specific ground/cut-fill palettes, and builds bounded bridge decks/supports. Generation-version markers make segment placement idempotent.

## Caravan routing and shipment ETA

`RoadRoutePlanner` uses Dijkstra over the persisted graph and accounts for road speed multipliers. `RoadShipmentPath` concatenates and orients all edge polylines, exposes exact endpoints, progress projection, bounded local waypoints, and the effective distance `sum(edgeLength / edgeSpeedMultiplier)`. `ShipmentPathResolver` selects a road route when both shipment colonies have connected settlement records and otherwise returns `DirectShipmentPath`.

At departure, `ShipmentTravelTimeEstimator` computes `baseTravelTicks + ceil(effectiveDistance × travelTicksPerBlock)`. Direct paths have multiplier 1.0. Cargo ownership, reservations, and lifecycle are unchanged, and existing in-transit shipments keep their persisted duration through migration/restart.

Growth plot planning reads this graph only to reject global road corridors and endpoints. It does not create a second global road system.

## Route shape (Phase 6.6 client acceptance round 2)

- Every road leaves each gate straight outward for 32 blocks (the gate-approach corridor that settlements keep free) and routes between those approach points. The coarse A* adds a heavy cost to cells inside any settlement core (footprint radius + 8), so roads go around towns instead of through plazas, ponds, and lots. This is a cost, not a hard block, so a road that cannot detour is still planned.
- The coarse corridor (default 64-block grid) is smoothed with two Chaikin corner-cutting iterations (endpoints fixed) and rasterised with Bresenham, so the centre line curves instead of running 64-block straights with 45-degree kinks and L-shaped staircases. If the smoothed line fails the grade/bridge rules, the unsmoothed corridor is tried.

## Physical surface (Phase 6.6 client acceptance round 2)

The persisted geometry Y is the walking level; the pavement is written at `Y - 1`. The old generator wrote it at `Y` (a raised causeway that was buried wherever the terrain was higher) and offset lateral columns along the anti-diagonal, which left every other cell unpaved on diagonal stretches (the checkerboard seen in the client). The generator now:

- covers a rounded band of radius `width + 0.5` around the centre line in every direction; each column takes the height of its nearest centre point;
- removes whole trees standing in the band (logs are removed with block updates so the canopy decays naturally instead of floating);
- cuts natural ground above the walking level only when the entire overhang fits within 6 blocks (otherwise the column is left natural, so nothing floats), and seals exposed lava;
- builds a bounded embankment (at most 6 blocks) below the pavement;
- paves with a deterministic weighted mix per road class (royal: stone bricks with cracked/mossy bricks, andesite, cobblestone, and polished-andesite curbs; stone: cobblestone/gravel/andesite/mossy cobblestone; dirt: path/coarse dirt/gravel/packed mud/rooted dirt; sandy biomes use sandstone variants);
- puts stone-brick or cobblestone half slabs on the lower side of every one-block rise of stone and royal roads;
- builds bridges as decks at bank level with stone-brick walls or spruce fences and pillars every 6 blocks down to the water floor;
- never paves settlement lots (footprint + 1) or plazas, even if the road was planned after the lot.

`physicalRefinementRadius` and `clearReplaceableVegetation` are no longer used (the band clears its own volume). Chunks generated by an older version keep their old surface; use a new world to see the new roads.

`/kingdoms road sample <road-uuid> <percent>` prints the centre point at that percentage, its kind, and a perpendicular block cross-section, for reporting road defects.

## Vertical profile (Phase 6.6 fix)

The fine profile used to be a forward-only clamp (at most one block per step), which lagged behind any local rise and failed the whole edge with `graded cut/fill exceeds bound` whenever a step exceeded `maximumBridgeBankDelta`. On seed `5318008` every edge near spawn failed this way. The profile is now the midpoint of the slope-limited fill and cut envelopes (two passes each), with bridge decks as fixed anchors. It spreads a step over both sides, keeps at most `maximumGroundGrade` per block and at most `maximumBridgeBankDelta` from the ground, and still fails explicitly when those bounds cannot be met. After the fix the same seed produced two physical `ROYAL` edges (1,307 and 1,445 points). The remaining `UNROUTABLE` edges are water spans beyond `maximumBridgeSpan` or exhausted coarse searches. Already persisted `UNROUTABLE` edges are not re-planned automatically.

## Planning thread (Phase 6.6)

Automatic road planning runs on the `Kingdoms-Settlement-Planner` worker together with region planning. The worker runs `RoadPlanner.planMissing` on NBT copies of the settlement registry and road network with generator-only sampling. New edges are merged into the live network on the server thread by their stable UUID, only if both endpoint settlements still exist. Road geometry, IDs, and determinism are unchanged; only the thread that computes them moved. Physical road generation stays chunk-local on the server thread.

Local settlement streets (plaza, gate street, building frontages) are a separate persisted network owned by each settlement layout. They never create or modify `RoadRecord`s, and they skip columns occupied by a global road surface.

## Configuration and commands

The `roads` section contains `enabled`, soft/hard maximum distance, maximum degree, coarse grid size, search margin, maximum path nodes, slope/water/elevation costs, `maximumGroundGrade`, `maximumBridgeSpan`, `maximumBridgeBankDelta`, `ravineDepthThreshold`, `maximumFinePathPoints`, `physicalRefinementRadius`, and `clearReplaceableVegetation`.

```text
/kingdoms road list
/kingdoms road info <uuid>
/kingdoms road nearby [radius]
/kingdoms road route <from-settlement-uuid> <to-settlement-uuid>
/kingdoms road connect <from-settlement-uuid> <to-settlement-uuid>
/kingdoms road regenerate-segment
/kingdoms road sample <road-uuid> <percent>
```

`connect` creates a stable edge if absent. `regenerate-segment` explicitly invalidates and rebuilds only the command source's current chunk.
