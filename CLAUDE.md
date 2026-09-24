# MineColonies: Kingdoms — development contract

This file is the persistent contract for agent-driven development of this addon. It was assembled from the requirements the project owner stated across Phases 6.6–8. When a phase specification says something more specific, the specification wins for that phase.

## Project

- NeoForge 1.21.1 addon (NeoForge 21.1.80, Java 21) on top of MineColonies `1.1.1396-1.21.1-snapshot` and Structurize 1.0.832.
- Not a git repository. Reconstruct state from files, docs, and tests, not from memory or old summaries.
- JDK: `D:\Mods\.toolchains\temurin21\jdk-21.0.12.1+1`. Gradle sometimes fails with "Failed to download ... version_manifest.json"; retry, since `--offline` does not work.

## Hard rules

- No Mixins, no reflection, and no changes to MineColonies sources. Do not copy MineColonies assets; referencing them at runtime by resource location is fine.
- Do not create fake MineColonies colonies, citizens, Town Halls, claims, work orders, fake players, or fake colony IDs.
- Never force-load chunks. Physical work happens only in loaded (entity-ticking) chunks.
- Never modify the user's worlds (`runs/server/world`, `runs/client/saves/*` that you did not create). Test on copies, and restore any temporary config (`server.properties`, client `options.txt`) afterwards.
- Do not rewrite systems that passed acceptance (settlement generation, terrain shaping, local streets, Road Geometry V2, citizens, Phase 7 contracts/reputation/relations) unless a concrete, proven defect requires it.
- Do not declare graphical acceptance done. Hand manual Minecraft checks to the user as an exact checklist with real commands.

## Architecture principles

- **Strategic state is authoritative; entities are temporary representations.** Caravans, citizens, and bandits materialize near players with hysteresis and caps, are never saved to chunks (or are discarded as orphans), and never own economy, cargo, population, or rewards.
- **Single writer per domain**, audited:
  - player reputation: `ReputationService` → `ReputationRegistry`;
  - faction relations: `DiplomacyService` → `Faction.relations`;
  - contracts: `ContractService` → `ContractRegistry`;
  - shipments: `TradeManager` and `TradeShipment` transitions;
  - bandit encounters: `EncounterService`.
- **Exactly-once transactions** with persisted flags for anything that moves value (cargo, rewards, reputation). A restart must never reroll, repeat, or lose a result.
- **Bounded work.** Coarse periodic evaluation, indexed lookups, hard caps on searches and entities, and no per-block persistent maps. Expose timings and counters through commands.
- **Presentation boundaries.** Citizen and bandit entities call services and never hold authority. Commands are always a complete fallback and debug interface.

## Every phase

1. Audit the current code before editing; run the baseline tests.
2. Keep `docs/PHASE_<n>_TASKS.md` up to date as the execution checklist.
3. Add deterministic unit tests for every invariant; run `gradlew clean test build --no-build-cache --rerun-tasks`.
4. Run a dedicated-server smoke test on a copy world (temporary RCON) and an integrated-client startup regression (`runClient -PquickPlaySingleplayer=<copy>`; see `docs/SMOKE_TEST.md` for the scripted datapack approach).
5. Bump the save schema only when persistent data changes; migrate sequentially and test migration and round trip.
6. Perform a merge-blocking review (first without editing), fix blockers with regression tests, and repeat.
7. Update README, ARCHITECTURE, ROADMAP, SMOKE_TEST, and the phase document, then give the user a manual checklist.
