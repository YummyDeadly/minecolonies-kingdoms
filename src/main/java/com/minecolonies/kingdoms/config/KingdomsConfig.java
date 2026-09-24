package com.minecolonies.kingdoms.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

public final class KingdomsConfig
{
    public static final Server SERVER;
    public static final ModConfigSpec SERVER_SPEC;

    static
    {
        final ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    private KingdomsConfig()
    {
    }

    public static final class Server
    {
        public final ModConfigSpec.BooleanValue simulationEnabled;
        public final ModConfigSpec.IntValue batchSize;
        public final ModConfigSpec.IntValue strategicIntervalTicks;
        public final ModConfigSpec.IntValue economyIntervalTicks;
        public final ModConfigSpec.LongValue maxWorkNanosPerTick;
        public final ModConfigSpec.IntValue activeRadius;
        public final ModConfigSpec.IntValue deactivationRadius;
        public final ModConfigSpec.BooleanValue caravanEnabled;
        public final ModConfigSpec.IntValue caravanMaterializationRadius;
        public final ModConfigSpec.IntValue caravanDematerializationRadius;
        public final ModConfigSpec.IntValue caravanMaxPhysical;
        public final ModConfigSpec.IntValue caravanMaxPhysicalPerPlayer;
        public final ModConfigSpec.IntValue caravanUpdateIntervalTicks;
        public final ModConfigSpec.IntValue caravanLocalWaypointDistance;
        public final ModConfigSpec.IntValue caravanStuckTimeoutTicks;
        public final ModConfigSpec.IntValue caravanPathRetryIntervalTicks;
        public final ModConfigSpec.BooleanValue caravanDebugNames;
        public final ModConfigSpec.BooleanValue settlementEnabled;
        public final ModConfigSpec.ConfigValue<List<? extends String>> settlementAllowedDimensions;
        public final ModConfigSpec.IntValue settlementRegionSize;
        public final ModConfigSpec.IntValue settlementDensityPercent;
        public final ModConfigSpec.IntValue settlementMinimumDistance;
        public final ModConfigSpec.IntValue settlementCandidateCount;
        public final ModConfigSpec.IntValue settlementSampleRadius;
        public final ModConfigSpec.IntValue settlementMaximumSlope;
        public final ModConfigSpec.IntValue settlementMaximumRoughness;
        public final ModConfigSpec.IntValue settlementVillageWeight;
        public final ModConfigSpec.IntValue settlementTownWeight;
        public final ModConfigSpec.IntValue settlementTradingTownWeight;
        public final ModConfigSpec.IntValue settlementCastleWeight;
        public final ModConfigSpec.IntValue settlementFortWeight;
        public final ModConfigSpec.BooleanValue worldgenModifyExistingChunks;
        public final ModConfigSpec.BooleanValue roadsEnabled;
        public final ModConfigSpec.IntValue roadsSoftMaximumDistance;
        public final ModConfigSpec.IntValue roadsHardMaximumDistance;
        public final ModConfigSpec.IntValue roadsMaximumDegree;
        public final ModConfigSpec.IntValue roadsGridSize;
        public final ModConfigSpec.IntValue roadsSearchMargin;
        public final ModConfigSpec.IntValue roadsMaximumPathNodes;
        public final ModConfigSpec.DoubleValue roadsSlopeCost;
        public final ModConfigSpec.DoubleValue roadsWaterCost;
        public final ModConfigSpec.DoubleValue roadsElevationCost;
        public final ModConfigSpec.DoubleValue roadsMaximumGroundGrade;
        public final ModConfigSpec.IntValue roadsMaximumBridgeSpan;
        public final ModConfigSpec.IntValue roadsMaximumBridgeBankDelta;
        public final ModConfigSpec.IntValue roadsRavineDepthThreshold;
        public final ModConfigSpec.IntValue roadsMaximumFinePathPoints;
        public final ModConfigSpec.IntValue roadsPhysicalRefinementRadius;
        public final ModConfigSpec.BooleanValue roadsClearReplaceableVegetation;
        public final ModConfigSpec.BooleanValue growthEnabled;
        public final ModConfigSpec.BooleanValue citizensEnabled;
        public final ModConfigSpec.IntValue citizensMaterializationRadius;
        public final ModConfigSpec.IntValue citizensDematerializationRadius;
        public final ModConfigSpec.IntValue citizensMaxPerSettlement;
        public final ModConfigSpec.IntValue citizensMaxGlobal;
        public final ModConfigSpec.IntValue citizensMaxPerPlayer;
        public final ModConfigSpec.IntValue citizensUpdateIntervalTicks;
        public final ModConfigSpec.IntValue citizensMaxRosterPerSettlement;
        public final ModConfigSpec.IntValue citizensSpawnsPerCycle;
        public final ModConfigSpec.IntValue citizensRespawnCooldownTicks;
        public final ModConfigSpec.IntValue citizensStuckTimeoutTicks;
        public final ModConfigSpec.BooleanValue contractsEnabled;
        public final ModConfigSpec.IntValue contractsMaxOpenPerSettlement;
        public final ModConfigSpec.LongValue contractsResourceCooldownTicks;
        public final ModConfigSpec.LongValue contractsUnacceptedCooldownTicks;
        public final ModConfigSpec.IntValue contractsMaxActivePerPlayer;
        public final ModConfigSpec.LongValue contractsOfferLifetimeTicks;
        public final ModConfigSpec.LongValue contractsDurationTicks;
        public final ModConfigSpec.LongValue contractsOfferRefreshTicks;
        public final ModConfigSpec.IntValue contractsInteractionRadius;
        public final ModConfigSpec.LongValue contractsHistoryRetentionTicks;
        public final ModConfigSpec.IntValue contractsMaxHistory;
        public final ModConfigSpec.IntValue reputationFailPenalty;
        public final ModConfigSpec.IntValue reputationAbandonPenalty;
        public final ModConfigSpec.IntValue reputationKillPenalty;
        public final ModConfigSpec.BooleanValue diplomacyEnabled;
        public final ModConfigSpec.BooleanValue banditsEnabled;
        public final ModConfigSpec.IntValue banditsEvaluationIntervalTicks;
        public final ModConfigSpec.IntValue banditsMaterializationRadius;
        public final ModConfigSpec.IntValue banditsDematerializationRadius;
        public final ModConfigSpec.IntValue banditsMaxPerEncounter;
        public final ModConfigSpec.IntValue banditsMaxPhysicalGlobal;
        public final ModConfigSpec.IntValue banditsMaxPhysicalPerPlayer;
        public final ModConfigSpec.IntValue banditsSettlementExclusionRadius;
        public final ModConfigSpec.DoubleValue banditsBaseThreat;
        public final ModConfigSpec.DoubleValue banditsThreatStep;
        public final ModConfigSpec.LongValue banditsEncounterCooldownTicks;
        public final ModConfigSpec.DoubleValue banditsAmbushChanceAtMaxThreat;
        public final ModConfigSpec.LongValue banditsAbstractResolveTicks;
        public final ModConfigSpec.DoubleValue banditsRoadblockThreshold;
        public final ModConfigSpec.LongValue banditsRoadblockLifetimeTicks;
        public final ModConfigSpec.LongValue banditsSuppressionTicks;
        public final ModConfigSpec.IntValue banditsMaxActiveEncounters;
        public final ModConfigSpec.IntValue diplomacyEvaluationIntervalTicks;
        public final ModConfigSpec.IntValue growthEvaluationIntervalTicks;
        public final ModConfigSpec.IntValue growthMaxSettlementsPerCycle;
        public final ModConfigSpec.IntValue growthMaximumBuildingsPerSettlement;
        public final ModConfigSpec.IntValue growthMaximumExpansionRadius;
        public final ModConfigSpec.IntValue growthPopulationIntervalTicks;
        public final ModConfigSpec.IntValue growthPopulationHardCap;
        public final ModConfigSpec.IntValue settlementMaximumCutDepth;
        public final ModConfigSpec.IntValue settlementMaximumFillDepth;
        public final ModConfigSpec.IntValue settlementMaximumPadHeightVariance;
        public final ModConfigSpec.IntValue settlementMaximumRetainingWallHeight;
        public final ModConfigSpec.IntValue settlementMaximumTerrainWorkVolume;
        public final ModConfigSpec.IntValue localStreetMaximumSearchNodes;
        public final ModConfigSpec.IntValue localStreetMaximumTerrainAdjustment;
        public final ModConfigSpec.IntValue localStreetMaximumBridgeSpan;
        public final ModConfigSpec.IntValue localStreetMaximumLength;

        private Server(final ModConfigSpec.Builder builder)
        {
            builder.push("simulation");
            simulationEnabled = builder
                .comment("Enables the server-authoritative strategic simulation.")
                .define("enabled", true);
            batchSize = builder
                .comment("Maximum number of colonies processed in one server tick.")
                .defineInRange("batchSize", 5, 1, 1_000);
            strategicIntervalTicks = builder
                .comment("Delay between the starts of strategic update cycles.")
                .defineInRange("strategicIntervalTicks", 600, 20, 72_000);
            economyIntervalTicks = builder
                .comment("Minimum delay between economy updates for one colony.")
                .defineInRange("economyIntervalTicks", 1_000, 20, 72_000);
            maxWorkNanosPerTick = builder
                .comment("Soft time budget for Kingdoms simulation work in one server tick.")
                .defineInRange("maxWorkNanosPerTick", 2_000_000L, 100_000L, 50_000_000L);
            activeRadius = builder
                .comment("Distance at which an abstract colony becomes active.")
                .defineInRange("activeRadius", 256, 16, 4_096);
            deactivationRadius = builder
                .comment("Distance beyond which an active colony becomes abstract. Effective value is always greater than activeRadius.")
                .defineInRange("deactivationRadius", 320, 32, 8_192);
            builder.pop();

            builder.push("trade");
            tradeEnabled = builder
                .comment("Enables automatic abstract trade between eligible NPC colonies.")
                .define("enabled", true);
            tradeMatchIntervalTicks = builder
                .comment("Delay between automatic offer/demand matching cycles.")
                .defineInRange("matchIntervalTicks", 1_200, 20, 72_000);
            tradeShipmentIntervalTicks = builder
                .comment("Delay between abstract shipment lifecycle checks.")
                .defineInRange("shipmentIntervalTicks", 20, 1, 1_200);
            tradeMaxRoutesPerColony = builder
                .comment("Maximum non-broken automatic routes involving one colony.")
                .defineInRange("maxRoutesPerColony", 8, 1, 128);
            tradeMaxAbstractDistance = builder
                .comment("Maximum same-dimension distance for automatic abstract trade.")
                .defineInRange("maxAbstractTradeDistance", 4_096, 16, 100_000);
            tradeMinimumShipmentAmount = builder
                .comment("Smallest automatic strategic resource shipment.")
                .defineInRange("minimumShipmentAmount", 10, 1, 1_000_000);
            tradeMaximumShipmentAmount = builder
                .comment("Largest automatic strategic resource shipment.")
                .defineInRange("maximumShipmentAmount", 300, 1, 10_000_000);
            tradeBaseTravelTicks = builder
                .comment("Base abstract shipment travel time.")
                .defineInRange("baseTravelTicks", 200, 1, 1_000_000);
            tradeTravelTicksPerBlock = builder
                .comment("Additional abstract travel ticks per block of route distance.")
                .defineInRange("travelTicksPerBlock", 2.0D, 0.0D, 1_000.0D);
            tradeRoutePauseGraceCycles = builder
                .comment("Unmatched cycles before an active route becomes paused.")
                .defineInRange("routePauseGraceCycles", 3, 1, 100);
            tradeExportSafetyBufferPercent = builder
                .comment("Extra percentage of desired reserve retained by exporters.")
                .defineInRange("exportSafetyBufferPercent", 10.0D, 0.0D, 1_000.0D);
            builder.pop();

            builder.push("caravan");
            caravanEnabled = builder.comment("Enables physical caravan materialization near players.")
                .define("enabled", true);
            caravanMaterializationRadius = builder.comment("Distance at which an abstract shipment may materialize.")
                .defineInRange("materializationRadius", 128, 16, 2_048);
            caravanDematerializationRadius = builder.comment("Distance beyond which a physical caravan dematerializes.")
                .defineInRange("dematerializationRadius", 192, 32, 4_096);
            caravanMaxPhysical = builder.comment("Global cap for physical caravans.")
                .defineInRange("maxPhysicalCaravans", 16, 1, 256);
            caravanMaxPhysicalPerPlayer = builder.comment("Physical caravan cap assigned to one observing player.")
                .defineInRange("maxPhysicalCaravansPerPlayer", 4, 1, 64);
            caravanUpdateIntervalTicks = builder.comment("Ticks between caravan manager updates.")
                .defineInRange("updateIntervalTicks", 10, 1, 200);
            caravanLocalWaypointDistance = builder.comment("Distance ahead used for local navigation waypoints.")
                .defineInRange("localWaypointDistance", 64, 8, 256);
            caravanStuckTimeoutTicks = builder.comment("No-progress timeout before safe abstract fallback.")
                .defineInRange("stuckTimeoutTicks", 400, 20, 72_000);
            caravanPathRetryIntervalTicks = builder.comment("Ticks between navigation path retries.")
                .defineInRange("pathRetryIntervalTicks", 40, 1, 1_200);
            caravanDebugNames = builder.comment("Shows caravan role and identifiers above physical members.")
                .define("debugNames", false);
            builder.pop();

            builder.push("settlement");
            settlementEnabled = builder.comment("Enables deterministic NPC settlement planning in new terrain.")
                .define("enabled", true);
            settlementAllowedDimensions = builder.comment("Dimension ids in which settlements may be planned.")
                .defineList("allowedDimensions", List.of("minecraft:overworld"), value -> value instanceof String text && !text.isBlank());
            settlementRegionSize = builder.defineInRange("regionSize", 1024, 128, 8192);
            settlementDensityPercent = builder.defineInRange("densityPercent", 70, 0, 100);
            settlementMinimumDistance = builder.defineInRange("minimumDistance", 640, 0, 8192);
            settlementCandidateCount = builder.defineInRange("candidateCount", 4, 1, 64);
            settlementSampleRadius = builder.defineInRange("sampleRadius", 32, 4, 128);
            settlementMaximumSlope = builder.defineInRange("maximumSlope", 10, 0, 64);
            settlementMaximumRoughness = builder.defineInRange("maximumRoughness", 18, 0, 128);
            builder.push("typeWeights");
            settlementVillageWeight = builder.defineInRange("village", 45, 0, 1000);
            settlementTownWeight = builder.defineInRange("town", 25, 0, 1000);
            settlementTradingTownWeight = builder.defineInRange("tradingTown", 15, 0, 1000);
            settlementCastleWeight = builder.defineInRange("castle", 8, 0, 1000);
            settlementFortWeight = builder.defineInRange("fort", 7, 0, 1000);
            builder.pop();
            worldgenModifyExistingChunks = builder.comment("Allows automatic settlement/road blocks in already-generated chunks. Keep false for existing worlds.")
                .define("modifyExistingChunks", false);
            builder.push("terrainShaping");
            settlementMaximumCutDepth = builder.defineInRange("maximumCutDepth", 4, 0, 16);
            settlementMaximumFillDepth = builder.defineInRange("maximumFillDepth", 4, 0, 16);
            settlementMaximumPadHeightVariance = builder.defineInRange("maximumPadHeightVariance", 8, 0, 32);
            settlementMaximumRetainingWallHeight = builder.defineInRange("maximumRetainingWallHeight", 5, 0, 16);
            settlementMaximumTerrainWorkVolume = builder.defineInRange("maximumTerrainWorkVolume", 4096, 0, 65536);
            builder.pop();
            builder.push("localStreets");
            localStreetMaximumSearchNodes = builder.defineInRange("maximumSearchNodes", 4096, 64, 32768);
            localStreetMaximumTerrainAdjustment = builder.defineInRange("maximumTerrainAdjustment", 2, 0, 8);
            localStreetMaximumBridgeSpan = builder.defineInRange("maximumBridgeSpan", 5, 0, 32);
            localStreetMaximumLength = builder.defineInRange("maximumLength", 192, 16, 512);
            builder.pop();
            builder.pop();

            builder.push("roads");
            roadsEnabled = builder.comment("Enables the deterministic global settlement road graph.").define("enabled", true);
            roadsSoftMaximumDistance = builder.defineInRange("softMaximumDistance", 1536, 64, 100000);
            roadsHardMaximumDistance = builder.defineInRange("hardMaximumDistance", 2048, 64, 100000);
            roadsMaximumDegree = builder.defineInRange("maximumDegree", 4, 1, 16);
            roadsGridSize = builder.defineInRange("coarseGridSize", 64, 8, 128);
            roadsSearchMargin = builder.defineInRange("searchMargin", 256, 0, 4096);
            roadsMaximumPathNodes = builder.defineInRange("maximumPathNodes", 2500, 64, 1000000);
            roadsSlopeCost = builder.defineInRange("slopeCost", 10.0D, 0.0D, 1000.0D);
            roadsWaterCost = builder.defineInRange("waterCost", 120.0D, 0.0D, 10000.0D);
            roadsElevationCost = builder.defineInRange("elevationCost", 1.5D, 0.0D, 1000.0D);
            roadsMaximumGroundGrade = builder.defineInRange("maximumGroundGrade", 1.0D, 0.0D, 1.0D);
            roadsMaximumBridgeSpan = builder.defineInRange("maximumBridgeSpan", 64, 1, 128);
            roadsMaximumBridgeBankDelta = builder.defineInRange("maximumBridgeBankDelta", 3, 0, 32);
            roadsRavineDepthThreshold = builder.defineInRange("ravineDepthThreshold", 5, 1, 64);
            roadsMaximumFinePathPoints = builder.defineInRange("maximumFinePathPoints", 16384, 64, 1000000);
            roadsPhysicalRefinementRadius = builder.defineInRange("physicalRefinementRadius", 3, 0, 8);
            roadsClearReplaceableVegetation = builder.define("clearReplaceableVegetation", false);
            builder.pop();

            builder.push("citizens");
            citizensEnabled = builder.comment("Materializes bounded physical representatives of NPC_ABSTRACT settlement populations near players.")
                .define("enabled", true);
            citizensMaterializationRadius = builder.comment("Distance from a settlement anchor at which representatives appear.")
                .defineInRange("materializationRadius", 64, 16, 256);
            citizensDematerializationRadius = builder.comment("Distance beyond which they disappear again (kept at least 8 above the materialization radius).")
                .defineInRange("dematerializationRadius", 96, 24, 320);
            citizensMaxPerSettlement = builder.defineInRange("maxPhysicalCitizensPerSettlement", 12, 0, 64);
            citizensMaxGlobal = builder.defineInRange("maxPhysicalCitizensGlobal", 64, 0, 512);
            citizensMaxPerPlayer = builder.defineInRange("maxPhysicalCitizensPerPlayer", 24, 0, 256);
            citizensUpdateIntervalTicks = builder.defineInRange("updateIntervalTicks", 20, 1, 200);
            citizensMaxRosterPerSettlement = builder.comment("Persistent recognisable representatives per settlement (not the population).")
                .defineInRange("maxRosterPerSettlement", 16, 1, 48);
            citizensSpawnsPerCycle = builder.comment("Representatives that may appear per settlement per update, so arrival is gradual.")
                .defineInRange("spawnsPerCycle", 2, 1, 16);
            citizensRespawnCooldownTicks = builder.comment("Delay before a representative killed in the world reappears; the logical population is unchanged.")
                .defineInRange("respawnCooldownTicks", 1200, 0, 72000);
            citizensStuckTimeoutTicks = builder.defineInRange("stuckTimeoutTicks", 160, 20, 2400);
            builder.pop();

            builder.push("contracts");
            contractsEnabled = builder.comment("Settlements post delivery contracts for their resource shortages; players accept them from officials and merchants.")
                .define("enabled", true);
            contractsMaxOpenPerSettlement = builder.comment("Open (offered or accepted) contracts per settlement.")
                .defineInRange("maxOpenPerSettlement", 3, 0, 8);
            contractsMaxActivePerPlayer = builder.defineInRange("maxActivePerPlayer", 3, 0, 16);
            contractsOfferLifetimeTicks = builder.comment("How long an unaccepted offer stays posted.")
                .defineInRange("offerLifetimeTicks", 48_000L, 1_200L, 2_400_000L);
            contractsDurationTicks = builder.comment("Time a player has to deliver after accepting.")
                .defineInRange("durationTicks", 72_000L, 1_200L, 2_400_000L);
            contractsOfferRefreshTicks = builder.comment("Minimum time between new offers from one settlement.")
                .defineInRange("offerRefreshTicks", 2_400L, 0L, 240_000L);
            contractsResourceCooldownTicks = builder.comment("After a contract for a resource closes, no new one for that resource from the same settlement for this long.")
                .defineInRange("resourceCooldownTicks", 12_000L, 0L, 720_000L);
            contractsUnacceptedCooldownTicks = builder.comment("Cooldown after an offer for a resource expired unaccepted.")
                .defineInRange("unacceptedCooldownTicks", 24_000L, 0L, 720_000L);
            contractsInteractionRadius = builder.comment("Players must be this close to a settlement's anchor to accept or deliver.")
                .defineInRange("interactionRadius", 96, 16, 512);
            contractsHistoryRetentionTicks = builder.defineInRange("historyRetentionTicks", 168_000L, 0L, 2_400_000L);
            contractsMaxHistory = builder.defineInRange("maxHistory", 256, 0, 4096);
            reputationFailPenalty = builder.comment("Reputation lost when an accepted contract expires.")
                .defineInRange("failReputationPenalty", 6, 0, 100);
            reputationAbandonPenalty = builder.defineInRange("abandonReputationPenalty", 4, 0, 100);
            reputationKillPenalty = builder.comment("Reputation lost for killing a settlement's representative.")
                .defineInRange("killReputationPenalty", 10, 0, 100);
            builder.pop();

            builder.push("diplomacy");
            diplomacyEnabled = builder.comment("Relations between neighbouring NPC factions evolve with trade and competition for scarce resources.")
                .define("enabled", true);
            diplomacyEvaluationIntervalTicks = builder.defineInRange("evaluationIntervalTicks", 24_000, 1_200, 720_000);
            builder.pop();

            builder.push("bandits");
            banditsEnabled = builder.comment("Bandit threat on trade roads, caravan ambushes, and roadblocks. Disabling keeps the state but removes physical bandits and stops new activity.")
                .define("enabled", true);
            banditsEvaluationIntervalTicks = builder.comment("Ticks between road threat evaluations.")
                .defineInRange("evaluationIntervalTicks", 1_200, 200, 72_000);
            banditsMaterializationRadius = builder.comment("Distance at which an active encounter shows physical bandits.")
                .defineInRange("materializationRadius", 48, 16, 128);
            banditsDematerializationRadius = builder.comment("Distance beyond which physical bandits return to abstract (at least 8 more than above).")
                .defineInRange("dematerializationRadius", 80, 24, 192);
            banditsMaxPerEncounter = builder.defineInRange("maxBanditsPerEncounter", 5, 1, 12);
            banditsMaxPhysicalGlobal = builder.defineInRange("maxPhysicalBanditsGlobal", 24, 0, 256);
            banditsMaxPhysicalPerPlayer = builder.defineInRange("maxPhysicalBanditsPerPlayer", 10, 0, 64);
            banditsSettlementExclusionRadius = builder.comment("No bandit activity within this distance of any settlement anchor.")
                .defineInRange("settlementExclusionRadius", 192, 32, 1_024);
            banditsBaseThreat = builder.comment("Threat every eligible road has (0-100).")
                .defineInRange("baseThreat", 5.0D, 0.0D, 100.0D);
            banditsThreatStep = builder.comment("Largest threat change per evaluation (smoothing).")
                .defineInRange("threatStep", 5.0D, 0.5D, 50.0D);
            banditsEncounterCooldownTicks = builder.comment("Minimum time between encounters on one road.")
                .defineInRange("encounterCooldownTicks", 6_000L, 0L, 720_000L);
            banditsAmbushChanceAtMaxThreat = builder.comment("Chance that a caravan is ambushed on a road of threat 100 (scales linearly down to threat 15).")
                .defineInRange("ambushChanceAtMaxThreat", 0.6D, 0.0D, 1.0D);
            banditsAbstractResolveTicks = builder.comment("How long an unobserved ambush holds the caravan before it is decided.")
                .defineInRange("abstractResolveTicks", 2_400L, 200L, 72_000L);
            banditsRoadblockThreshold = builder.comment("Road threat at which bandits set up a roadblock.")
                .defineInRange("roadblockThreshold", 70.0D, 1.0D, 100.0D);
            banditsRoadblockLifetimeTicks = builder.defineInRange("roadblockLifetimeTicks", 24_000L, 1_200L, 720_000L);
            banditsSuppressionTicks = builder.comment("After bandits are defeated, their road stays suppressed this long.")
                .defineInRange("suppressionTicks", 24_000L, 0L, 720_000L);
            banditsMaxActiveEncounters = builder.defineInRange("maxActiveEncounters", 16, 0, 256);
            builder.pop();

            builder.push("growth");
            growthEnabled = builder.comment("Enables low-frequency autonomous growth for procedural NPC_ABSTRACT settlements.")
                .define("enabled", true);
            growthEvaluationIntervalTicks = builder.comment("Ticks between bounded settlement growth cycles.")
                .defineInRange("evaluationIntervalTicks", 6_000, 200, 720_000);
            growthMaxSettlementsPerCycle = builder.comment("Maximum settlements evaluated in one growth cycle.")
                .defineInRange("maxSettlementsPerCycle", 4, 1, 1_000);
            growthMaximumBuildingsPerSettlement = builder.comment("Hard cap for procedural growth buildings per settlement.")
                .defineInRange("maximumBuildingsPerSettlement", 12, 1, 128);
            growthMaximumExpansionRadius = builder.comment("Maximum plot-center distance from the settlement anchor.")
                .defineInRange("maximumExpansionRadius", 160, 48, 1_024);
            growthPopulationIntervalTicks = builder.comment("Minimum ticks between conservative population growth checks.")
                .defineInRange("populationIntervalTicks", 24_000, 1_200, 7_200_000);
            growthPopulationHardCap = builder.comment("Global hard cap for one procedural settlement population.")
                .defineInRange("populationHardCap", 120, 1, 10_000);
            builder.pop();
        }

        public int effectiveDeactivationRadius()
        {
            return Math.max(deactivationRadius.get(), activeRadius.get() + 16);
        }

        public int effectiveCaravanDematerializationRadius()
        {
            return Math.max(caravanDematerializationRadius.get(), caravanMaterializationRadius.get() + 16);
        }

        public final ModConfigSpec.BooleanValue tradeEnabled;
        public final ModConfigSpec.IntValue tradeMatchIntervalTicks;
        public final ModConfigSpec.IntValue tradeShipmentIntervalTicks;
        public final ModConfigSpec.IntValue tradeMaxRoutesPerColony;
        public final ModConfigSpec.IntValue tradeMaxAbstractDistance;
        public final ModConfigSpec.IntValue tradeMinimumShipmentAmount;
        public final ModConfigSpec.IntValue tradeMaximumShipmentAmount;
        public final ModConfigSpec.IntValue tradeBaseTravelTicks;
        public final ModConfigSpec.DoubleValue tradeTravelTicksPerBlock;
        public final ModConfigSpec.IntValue tradeRoutePauseGraceCycles;
        public final ModConfigSpec.DoubleValue tradeExportSafetyBufferPercent;
    }
}
