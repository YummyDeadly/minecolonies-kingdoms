# MineColonies integration research

## Baseline

- Minecraft 1.21.1
- NeoForge 21.1.80
- Java 21
- MineColonies `1.1.1396-1.21.1-snapshot`
- locally inspected source: `D:/Mods/minecolonies-1.21.1-1.1.1396-snapshot`

The addon compiles against MineColonies as a required dependency. It modifies no MineColonies files and uses no Mixins or reflection.

## Public APIs used

- `IColonyManager` for enumeration and dimension-aware lookup.
- `IColony` for identity, center, permissions, citizen manager, and registered structures.
- `ICitizenManager` plus public job/guard interfaces for population aggregates.
- `IRegisteredStructureManager.getWareHouses()` and public `IWareHouse.getContainers()` for warehouse locations.
- NeoForge block item-handler capabilities for loaded container contents.
- MineColonies public event bus for colony-created and colony-renamed hooks.
- NeoForge server lifecycle, server tick, and command registration events.

Startup reconciliation remains the safety net for colonies created before Kingdoms is installed or while no relevant event was observed.

## Physical economy observation

`MineColoniesWarehouseResourceStorage` enumerates the public warehouse container positions and reads only loaded block entities through `Capabilities.ItemHandler.BLOCK`. The observation is all-or-nothing: an unloaded chunk, missing block entity, or absent capability rejects the physical snapshot. The controller then keeps the previous aggregate and performs abstract flow progression for that interval.

`MinecraftItemResourceValueProvider` converts stacks into strategic values using public item tags/components:

- FOOD: nutrition points times stack count;
- WOOD: planks at one unit, logs at four units;
- STONE: stone crafting materials plus common stone/deepslate blocks;
- IRON: raw iron/ingots at one unit, blocks at nine;
- TOOLS: axes, hoes, pickaxes, and shovels at one unit each.

These values are strategic aggregates, not replacement item stacks. Physical observation never removes, inserts, or duplicates items.

## Deliberate limits

- Public colony creation requires a real `Player` owner and structure pack; autonomous founding is not guessed.
- No stable high-level public call means “place this blueprint and create its normal construction work order”; recorded decisions are not executed yet.
- Warehouse reads can only be complete while all relevant chunks and capabilities are available.
- Claim mutation and some construction APIs expose core implementation types and are not used.
- MineColonies connections are not a faction diplomacy model.
- Kingdoms never invokes MineColonies colony tick methods.
- Automatic trade currently operates only on strategic `NPC_ABSTRACT` stockpiles. Mutating an `NPC_PHYSICAL` warehouse would require a separately validated, transactional public-API adapter; silently editing the strategic aggregate would desynchronize real items.
- Phase 6 repeated the bounded audit against the exact 1.1.1396 sources. `IColonyManager.createColony(ServerLevel, BlockPos, Player, String, String)` still requires a real `Player` owner and structure pack. `IWorkManager.addWorkOrder` accepts an already constructed `IServerWorkOrder`, while the normal building order factory is the core `WorkOrderBuilding.create(..., IBuilding)` path and depends on a registered real building. There is no public owner-free `colony -> blueprint placement -> normal work order` lifecycle. Growth therefore uses Kingdoms-owned building records (placing installed style-pack blueprints since Phase 6.5) linked only to `NPC_ABSTRACT` records; it does not create a Town Hall, fake colony id, fake player, internal implementation object, or copied style asset.

## Phase 6.6 world-generated colony audit (MineColonies 1.1.1396)

Audited against the local source `D:/Mods/minecolonies-1.21.1-1.1.1396-snapshot`.

- **Classes.** `com.minecolonies.core.structures.EmptyColonyStructure` (a vanilla `Structure` subclass, codec `COLONY_CODEC`) registered as structure type `minecolonies:empty_colony` by `MineColoniesStructures.EMPTY_COLONY` through a `DeferredRegister<StructureType<?>>`. Its step is `SURFACE_STRUCTURES`.
- **Location.** Vanilla structure placement from `data/minecolonies/worldgen/structure_set/empty_colony.json`: `minecraft:random_spread`, spacing 95, separation 45 chunks, salt 1225566777, twelve equally weighted style structures. Each `data/minecolonies/worldgen/structure/<style>_colony.json` restricts biomes with `#minecolonies:has_structure/<style>_colony`. `EmptyColonyStructure.findGenerationPoint` → `isFeatureChunk` checks only one column: the top block from `getFirstOccupiedHeight(WORLD_SURFACE_WG)` + `getBaseColumn` must not be fluid and `landHeight < 200`. There is no slope, area, or shoreline analysis. The start is the chunk middle at `getFirstFreeHeight(WORLD_SURFACE_WG)`. The optional `allow_cave` variant searches 10 random heights for a 32-block air column.
- **Style/layout.** The style is chosen by the structure-set weights. Layout is vanilla jigsaw (`JigsawPlacement.addPieces`, `size` 5, `max_distance_from_center` 80) starting from `<style>_pool` (for example `colonial/townhall1`, weight 20, versus an empty element, weight 1).
- **Composite or multiple buildings.** Multiple buildings. There is no settlement-level blueprint: each piece is a vanilla `.nbt` template under `data/minecolonies/structure/<style>/` (townhall, builder, farm, forester, guardtower, house, mine, restaurant, warehouse, park, fountain, junction/straight road pieces), connected by jigsaw connectors. Relative placement is jigsaw-socket based, not a stored offset table.
- **Roads/plaza.** Yes: the `<style>/roads` pool uses `legacy_single_pool_element` pieces with `projection: terrain_matching` (roads follow the surface) and `<style>/placeholder_replacement` or `street` processors. Buildings use `projection: rigid`. Parks and fountains act as plaza-like pieces.
- **Terrain preparation.** Only vanilla noise-level adaptation: `terrain_adaptation: beard_box` and `adapt_noise: true`. This works only while the chunk's noise is being generated (the beardifier). It cannot be applied to existing terrain afterwards.
- **Processors.** `placeholder_replacement` ignores `structurize:blocksubstitution` and `minecolonies:blockwaypoint`, replaces `structurize:blocksolidsubstitution` with `grass_block`, ages blocks (mossiness 0.15), and randomises terracotta to coarse dirt.
- **What needs a real colony.** Generation creates no colony, citizens, claims, or Town Hall building. Hut tile entities carry a positioned `DEACTIVATED` tag plus a style tag. Right-clicking a deactivated hut (`AbstractColonyBlock.useItemOn` → `IColonyManager.openReactivationWindow`) leads to `CreateColonyMessage`, which calls `hut.reactivate()` and `IColonyManager.createColony(world, pos, ServerPlayer, name, pack)` and then `addNewBuilding`. Other deactivated huts are reactivated inside an existing colony through `ReactivateBuildingMessage`. A real `Player` owner, an `IColony`, a Town Hall, and claims are all required. "Abandoned colonies" are a separate owner-inactivity mechanism (`CommandSetAbandoned`, `ColonyAbandonOwnMessage`), not generation.
- **What Kingdoms uses.** No MineColonies worldgen object, template pool, processor list, or `.nbt` asset is used or copied, and nothing is reactivated. Kingdoms keeps using installed Structurize style-pack blueprints through public Structurize APIs, with Kingdoms-owned records. The audit informed the design only: one style per settlement, rigid buildings with terrain-following streets, plaza-like centre pieces, and noise-time `beard_box` adaptation being unavailable after chunk generation. Kingdoms therefore uses explicit, bounded, per-building terrain pads and a much stronger area-based site analysis than MineColonies' single-column check.
- **Structurize placement detail.** `CreativeStructureHandler` must be constructed with `fancyPlacement=true`. In Structurize 1.0.832, `PlacementHandlers$SolidSubstitutionPlacementHandler.handle` resolves the placeholder through `IPlacementContext.getSolidBlockForPos` only in fancy mode; otherwise it writes `structurize:blocksolidsubstitution` literally (observed in the Phase 6.6 smoke test and fixed).

If a future version requires access not exposed by a stable public API, that integration must be separately researched and version-gated. This milestone has no “possible Mixin” implementation path hidden in the codebase.

## Phase 6.7 citizen rendering audit (MineColonies 1.1.1396)

- **Classes.** `com.minecolonies.api.client.render.modeltype.CitizenModel` extends `HumanoidModel`; `core.client.model.MaleCitizenModel` and `FemaleCitizenModel` build their meshes with `LayerDefinition.create(mesh, 128, 64)`; `core.client.render.RenderBipedCitizen` is a `MobRenderer` over `AbstractEntityCitizen`. All of them are typed to MineColonies' own citizen entity and colony data, so Kingdoms cannot reuse them for a non-MineColonies entity without internal types.
- **UV layout.** Both models use the vanilla player layout: head (0,0) with a +0.5 overlay at (32,0) baked into the head part (the separate `hat` part is empty and hidden), body (16,16)/jacket (16,32), right arm (40,16)/sleeve (40,32), left arm (32,48)/sleeve (48,48), right leg (0,16)/pants (0,32), left leg (16,48)/pants (0,48). The male model has 4-wide arms, the female model 3-wide (slim) arms. Extra accessory cubes (for example beards in the unused head corners, hats at x ≥ 80) use the remaining texture area.
- **Textures.** `assets/minecolonies/textures/entity/citizen/<style>/<job><male|female><n>_<a|b|d|w>.png` with styles `default`, `medieval`, `nordic`, `eastasian`, `hellenic`, `nether`, `modern`, `undead` (440 files in `default`). Most textures are 128×64. Exceptions: female `aristocrat` and `noble` textures are 128×128 and belong to the dress models `FemaleAristocratModel` and `FemaleNobleModle`; `deliveryman` exists as 64×32 (male) and 256×128 (female) in the medieval and nordic sets. Every outfit Kingdoms uses exists in every style.
- **What Kingdoms uses.** A Kingdoms-owned `PathfinderMob` and renderer with vanilla `PlayerModel` geometry baked at 128×64 (wide/slim), which maps these textures 1:1 for the humanoid parts. Textures are referenced by resource location at runtime (never copied). Women in merchant/official roles use the humanoid `settler`/`teacher` outfits. The renderer accepts only 2:1 humanoid textures (checked from the PNG header), so a texture that is missing or in a different layout falls back to the same name in `default/`, then to a vanilla default skin. No MineColonies entity, model class, citizen data, or colony object is created or used.
