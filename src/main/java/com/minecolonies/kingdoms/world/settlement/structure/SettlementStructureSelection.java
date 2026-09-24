package com.minecolonies.kingdoms.world.settlement.structure;

public record SettlementStructureSelection(SettlementStructureDescriptor descriptor, StructureTransform transform)
{
    public SettlementStructureSelection
    {
        if (!descriptor.supportedTransforms().contains(transform))
            throw new IllegalArgumentException("Unsupported structure transform");
    }
}
