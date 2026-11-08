package ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.util;

import static org.junit.jupiter.api.Assertions.*;

import ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccess;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.junit.jupiter.api.Test;

class PropertyIndexTestSuite {
    private static class TestState extends StateHolder<String, TestState> {
        TestState(Reference2ObjectArrayMap<Property<?>, Comparable<?>> values) { super("test", values, null); }
    }

    @Test void integerValueIdsRespectNonZeroMinAndRejectOverflowValues() {
        for (int min : new int[]{0, 4, 17}) {
            IntegerProperty property = IntegerProperty.create("bounded", min, min + 2);
            PropertyAccess<Integer> access = property;
            Map<Map<Property<?>, Comparable<?>>, TestState> universe = new HashMap<>();
            for (int value = min; value <= min + 2; ++value) {
                assertEquals(value - min, access.moonrise$getIdFor(value));
                var values = new Reference2ObjectArrayMap<Property<?>, Comparable<?>>();
                values.put(property, value);
                universe.put(values, new TestState(values));
            }
            universe.values().forEach(state -> state.populateNeighbours(universe));
            for (int invalid : new int[]{min - 1, min + 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                assertEquals(-1, access.moonrise$getIdFor(invalid));
                for (TestState state : universe.values()) {
                    assertThrows(IllegalArgumentException.class, () -> state.setValue(property, invalid));
                    assertThrows(IllegalArgumentException.class, () -> state.trySetValue(property, invalid));
                }
            }
            assertThrows(NullPointerException.class, () -> access.moonrise$getIdFor(null));
        }
    }

    @Test void sparseGlobalIdsKeepBoundedStorageAndAllTransitions() throws Exception {
        for (int gap : new int[]{15, 4097}) {
            BooleanProperty first = BooleanProperty.create("early");
            for (int i = 0; i < gap; i++) BooleanProperty.create("discarded");
            BooleanProperty last = BooleanProperty.create("late");
            Map<Map<Property<?>, Comparable<?>>, TestState> universe = new HashMap<>();
            for (boolean a : new boolean[]{false, true}) for (boolean b : new boolean[]{false, true}) {
                var values = new Reference2ObjectArrayMap<Property<?>, Comparable<?>>();
                values.put(first, a); values.put(last, b); universe.put(values, new TestState(values));
            }
            universe.values().forEach(state -> state.populateNeighbours(universe));
            Field tableField = StateHolder.class.getDeclaredField("optimisedTable"); tableField.setAccessible(true);
            Field denseField = ZeroCollidingReferenceStateTable.class.getDeclaredField("propertyToIndexer"); denseField.setAccessible(true);
            for (var entry : universe.entrySet()) {
                TestState state = entry.getValue(); assertNull(denseField.get(tableField.get(state)));
                assertEquals(entry.getKey(), state.getValues());
                for (boolean value : new boolean[]{false, true}) for (BooleanProperty property : List.of(first, last)) {
                    var target = new Reference2ObjectArrayMap<Property<?>, Comparable<?>>(entry.getKey()); target.put(property, value);
                    assertSame(universe.get(target), state.setValue(property, value));
                }
                assertFalse(state.hasProperty(BooleanProperty.create("early")));
            }
        }
    }

    @Test void allCombinationsTransitionsAndForeignProperties() {
        BooleanProperty first = BooleanProperty.create("first");
        BooleanProperty gap = BooleanProperty.create("unused");
        IntegerProperty second = IntegerProperty.create("second", 0, 3);
        Map<Map<Property<?>, Comparable<?>>, TestState> universe = new HashMap<>();
        for (boolean flag : new boolean[]{false, true}) for (int number = 0; number < 4; number++) {
            var values = new Reference2ObjectArrayMap<Property<?>, Comparable<?>>();
            values.put(second, number); values.put(first, flag);
            universe.put(values, new TestState(values));
        }
        universe.values().forEach(state -> state.populateNeighbours(universe));
        BooleanProperty foreign = BooleanProperty.create("first");
        for (var entry : universe.entrySet()) {
            TestState state = entry.getValue();
            assertEquals(entry.getKey(), state.getValues());
            assertEquals(List.of(second, first), new ArrayList<>(state.getProperties()));
            for (boolean flag : new boolean[]{false, true}) {
                Map<Property<?>, Comparable<?>> target = new Reference2ObjectArrayMap<>(entry.getKey()); target.put(first, flag);
                assertSame(universe.get(target), state.setValue(first, flag));
            }
            for (int number = 0; number < 4; number++) {
                Map<Property<?>, Comparable<?>> target = new Reference2ObjectArrayMap<>(entry.getKey()); target.put(second, number);
                assertSame(universe.get(target), state.setValue(second, number));
            }
            assertFalse(state.hasProperty(gap)); assertFalse(state.hasProperty(foreign));
            assertThrows(IllegalArgumentException.class, () -> state.setValue(second, 4));
        }
        var empty = new ZeroCollidingReferenceStateTable<>(List.of());
        assertFalse(empty.hasProperty(first)); assertNull(empty.get(0, first));
        assertNull(empty.set(0, foreign, true));
        Object fallback = new Object(); assertSame(fallback, empty.trySet(0, foreign, true, fallback));
        // set validates a value first; trySet returns the missing-property default first.
        assertThrows(NullPointerException.class, () -> empty.set(0, foreign, null));
        assertSame(fallback, empty.trySet(0, foreign, null, fallback));
    }

    public static void main(String[] args) throws Exception {
        var allocation = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId(), before = allocation.getThreadAllocatedBytes(thread);
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        long bootstrapBytes = allocation.getThreadAllocatedBytes(thread) - before;
        Field tableField = StateHolder.class.getDeclaredField("optimisedTable"); tableField.setAccessible(true);
        Field indexField = ZeroCollidingReferenceStateTable.class.getDeclaredField("propertyToIndexer"); indexField.setAccessible(true);
        var tables = new IdentityHashMap<Object, Boolean>();
        long states = 0;
        for (var block : BuiltInRegistries.BLOCK) for (var state : block.getStateDefinition().getPossibleStates()) {
            states++; tables.put(tableField.get(state), true);
        }
        for (var fluid : BuiltInRegistries.FLUID) for (var state : fluid.getStateDefinition().getPossibleStates()) {
            states++; tables.put(tableField.get(state), true);
        }
        long slots = 0, entries = 0;
        for (Object table : tables.keySet()) {
            Object index = indexField.get(table);
            if (index == null) {
                Field sparse = ZeroCollidingReferenceStateTable.class.getDeclaredField("sparsePropertyToIndexer"); sparse.setAccessible(true);
                index = sparse.get(table);
            }
            if (index instanceof Object[] array) { slots += array.length; for (Object item : array) if (item != null) entries++; }
            else if (index instanceof Map<?, ?> map) {
                entries += map.size();
                Field values = index.getClass().getDeclaredField("value"); values.setAccessible(true);
                slots += ((Object[])values.get(index)).length;
            }
        }
        System.out.println("states=" + states + " shared_tables=" + tables.size() + " index_slots=" + slots
            + " entries=" + entries + " bootstrap_thread_allocated_bytes=" + bootstrapBytes);
    }
}
