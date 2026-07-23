package net.minecraft.world.level.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.datafixers.util.Pair;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ClimateRTreeParityTest {
    @Test
    void indexedSearchMatchesBruteForce() {
        Random random = new Random(0x4c415454494345L);
        List<Pair<Climate.ParameterPoint, Integer>> entries = new ArrayList<>();
        for (int i = 0; i < 192; i++) {
            entries.add(Pair.of(new Climate.ParameterPoint(
                    interval(random), interval(random), interval(random),
                    interval(random), interval(random), interval(random),
                    random.nextInt(20_001)), i));
        }

        Climate.ParameterList<Integer> parameters = new Climate.ParameterList<>(entries);
        for (int i = 0; i < 50_000; i++) {
            Climate.TargetPoint target = new Climate.TargetPoint(
                    coordinate(random), coordinate(random), coordinate(random),
                    coordinate(random), coordinate(random), coordinate(random));
            Integer expected = parameters.findValueBruteForce(target);
            assertEquals(expected, parameters.findValue(target), "target=" + target);
            assertEquals(expected, parameters.findValue(target), "cached target=" + target);
        }
    }

    private static Climate.Parameter interval(Random random) {
        long first = coordinate(random);
        long second = coordinate(random);
        return new Climate.Parameter(Math.min(first, second), Math.max(first, second));
    }

    private static long coordinate(Random random) {
        return random.nextInt(40_001) - 20_000L;
    }
}
