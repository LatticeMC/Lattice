package net.minecraft.world.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.Test;

class InsideBlockEffectApplierTestSuite {
    @Test
    void preservesEffectTypePhaseAndStepOrderAcrossReuse() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        InsideBlockEffectApplier.StepBasedCollector collector = new InsideBlockEffectApplier.StepBasedCollector();
        Entity entity = mock(Entity.class);
        when(entity.isAlive()).thenReturn(true);
        List<String> calls = new ArrayList<>();
        InsideBlockEffectType first = InsideBlockEffectType.values()[0];
        InsideBlockEffectType second = InsideBlockEffectType.values()[1];

        collector.advanceStep(0, BlockPos.ZERO);
        collector.runAfter(first, ignored -> calls.add("step-0-first-after"));
        collector.runBefore(second, ignored -> calls.add("step-0-second-before"));
        collector.runBefore(first, ignored -> calls.add("step-0-first-before"));
        collector.runAfter(second, ignored -> calls.add("step-0-second-after"));

        collector.advanceStep(1, BlockPos.ZERO);
        collector.runBefore(first, ignored -> calls.add("step-1-first-before"));
        collector.applyAndClear(entity);

        assertEquals(
            List.of(
                "step-0-first-before",
                "step-0-first-after",
                "step-0-second-before",
                "step-0-second-after",
                "step-1-first-before"
            ),
            calls
        );

        collector.applyAndClear(entity);
        assertEquals(5, calls.size());
    }
}
