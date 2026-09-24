package com.minecolonies.kingdoms.world.settlement.structure;

import java.util.Collection;

public interface SettlementStructureSource
{
    String sourceId();
    Collection<SettlementStructureDescriptor> descriptors();
}
