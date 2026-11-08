package ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.util;

import ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccess;
import ca.spottedleaf.moonrise.patches.blockstate_propertyaccess.PropertyAccessStateHolder;
import com.sun.management.ThreadMXBean;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.Fluid;

/** Standalone semantic and allocation baseline for property-index layouts. */
public final class PropertyIndexAuditBenchmark {
    private static final Field TABLE_FIELD = field(StateHolder.class, "optimisedTable");
    private static final Field DENSE_FIELD = field(ZeroCollidingReferenceStateTable.class, "propertyToIndexer");
    private static final Field SPARSE_FIELD = field(ZeroCollidingReferenceStateTable.class, "sparsePropertyToIndexer");
    private static final Field FIRST_ID_FIELD = field(ZeroCollidingReferenceStateTable.class, "firstPropertyId");
    private static final Object DEFAULT = new Object();
    private static volatile long sink;

    private PropertyIndexAuditBenchmark() {}

    public static void main(String[] args) throws Exception {
        final int warmup = Integer.getInteger("propertyIndex.warmup", 32);
        final int samples = Integer.getInteger("propertyIndex.samples", 21);
        final int rounds = Integer.getInteger("propertyIndex.rounds", 1024);
        if (warmup < 0 || samples < 1 || rounds < 1) {
            throw new IllegalArgumentException("warmup must be >= 0 and samples/rounds must be >= 1");
        }

        final ThreadMXBean allocation = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (!allocation.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Current-thread allocation counter is unavailable");
        }
        allocation.setThreadAllocatedMemoryEnabled(true);
        final long threadId = Thread.currentThread().threadId();

        final long bootstrapBefore = allocation.getThreadAllocatedBytes(threadId);
        SharedConstants.tryDetectVersion();
        final java.io.PrintStream stdout = System.out;
        final java.io.PrintStream stderr = System.err;
        try {
            Bootstrap.bootStrap();
        } finally {
            // Bootstrap redirects console streams; keep standalone diagnostics observable.
            System.setOut(stdout);
            System.setErr(stderr);
        }
        final long bootstrapAllocated = allocation.getThreadAllocatedBytes(threadId) - bootstrapBefore;

        final BootstrapStats bootstrap = collectBootstrapStats();
        final List<Fixture> fixtures = createFixtures();
        for (Fixture fixture : fixtures) {
            fixture.verify();
        }

        final ArrayList<BenchCase> cases = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            cases.addAll(fixture.benchmarkCases());
        }
        final List<RealTable> realTables = collectRealTables();
        for (RealTable table : realTables) {
            cases.addAll(table.benchmarkCases());
        }

        final ArrayList<Measurement> measurements = new ArrayList<>();
        for (int sample = -warmup; sample < samples; ++sample) {
            for (int caseIndex = 0; caseIndex < cases.size(); ++caseIndex) {
                final BenchCase benchCase = cases.get((sample & 1) == 0
                        ? caseIndex : cases.size() - caseIndex - 1);
                final long bytesBefore = allocation.getThreadAllocatedBytes(threadId);
                if (bytesBefore < 0L) {
                    throw new IllegalStateException("Current-thread allocation counter returned an invalid value");
                }
                final long start = System.nanoTime();
                final long checksum = benchCase.runBatch(rounds);
                final long elapsedNs = System.nanoTime() - start;
                final long allocatedBytes = allocation.getThreadAllocatedBytes(threadId) - bytesBefore;
                if (elapsedNs < 0L || allocatedBytes < 0L) {
                    throw new AssertionError("Invalid timing or allocation delta");
                }
                final long expected = benchCase.expectedBatchChecksum(rounds);
                if (checksum != expected) {
                    throw new AssertionError(benchCase.name + " batch checksum mismatch: expected="
                            + expected + " actual=" + checksum);
                }
                sink = checksum;
                if (sample >= 0) {
                    measurements.add(new Measurement(benchCase, sample, rounds, checksum, elapsedNs, allocatedBytes));
                }
            }
        }

        System.out.println("bootstrap unique_tables=" + bootstrap.tables + " dense_tables=" + bootstrap.denseTables
                + " sparse_tables=" + bootstrap.sparseTables + " index_slots=" + bootstrap.slots
                + " index_entries=" + bootstrap.entries + " current_thread_allocated_bytes=" + bootstrapAllocated
                + " note=slots_and_entries_are_not_retained_heap_or_index_exclusive_allocation");
        System.out.println("benchmark warmup=" + warmup + " samples=" + samples + " rounds=" + rounds
                + " synthetic_fixtures=" + fixtures.size() + " real_tables=" + realTables.size()
                + " measured_cases=" + cases.size());
        for (Measurement measurement : measurements) {
            final BenchCase c = measurement.benchCase;
            System.out.println("sample kind=" + c.kind + " scenario=" + c.name + " operation=" + c.operation
                    + " sample=" + measurement.sample + " ns=" + measurement.elapsedNs
                    + " bytes=" + measurement.allocatedBytes + " operations=" + (long)rounds * c.operationsPerRound
                    + " checksum=" + measurement.checksum + " span=" + c.span + " propertyCount=" + c.propertyCount
                    + " route=" + c.route + " warmup=" + warmup + " samples=" + samples + " rounds=" + rounds);
        }
    }

    private static List<Fixture> createFixtures() {
        final ArrayList<Fixture> fixtures = new ArrayList<>();
        fixtures.add(new Fixture("synthetic-no-property", List.of()));
        fixtures.add(new Fixture("synthetic-single-property", List.of(IntegerProperty.create("single", 4, 6))));

        final IntegerProperty continuousFirst = IntegerProperty.create("continuous_first", 2, 3);
        final IntegerProperty continuousSecond = IntegerProperty.create("continuous_second", 7, 9);
        fixtures.add(new Fixture("synthetic-multiple-contiguous", List.of(continuousSecond, continuousFirst)));

        final IntegerProperty denseFirst = IntegerProperty.create("dense_first", 0, 1);
        IntegerProperty.create("dense_gap_one", 0, 1);
        IntegerProperty.create("dense_gap_two", 0, 1);
        final IntegerProperty denseLast = IntegerProperty.create("dense_last", 5, 7);
        fixtures.add(new Fixture("synthetic-dense-empty-slots", List.of(denseLast, denseFirst)));

        final IntegerProperty sparseFirst = IntegerProperty.create("sparse_first", 3, 4);
        for (int i = 0; i < 64; ++i) {
            IntegerProperty.create("sparse_gap_" + i, 0, 1);
        }
        final IntegerProperty sparseLast = IntegerProperty.create("sparse_last", 9, 10);
        fixtures.add(new Fixture("synthetic-sparse-high-span", List.of(sparseLast, sparseFirst)));
        return fixtures;
    }

    private static List<RealTable> collectRealTables() throws IllegalAccessException {
        final IdentityHashMap<Object, RealTable> unique = new IdentityHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (StateHolder<?, ?> state : block.getStateDefinition().getPossibleStates()) {
                addRealTable(unique, state);
            }
        }
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            for (StateHolder<?, ?> state : fluid.getStateDefinition().getPossibleStates()) {
                addRealTable(unique, state);
            }
        }
        final ArrayList<RealTable> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing((RealTable table) -> table.name));
        return result;
    }

    private static void addRealTable(IdentityHashMap<Object, RealTable> unique, StateHolder<?, ?> state)
            throws IllegalAccessException {
        final Object table = TABLE_FIELD.get(state);
        if (unique.containsKey(table)) {
            return;
        }
        final @SuppressWarnings("unchecked") ZeroCollidingReferenceStateTable<Object, StateHolder<?, ?>> typedTable =
                (ZeroCollidingReferenceStateTable<Object, StateHolder<?, ?>>)table;
        final List<Property<?>> properties = new ArrayList<>(typedTable.getProperties());
        final Layout layout = layout(table, properties, false);
        final String name = state.getClass().getSimpleName() + "#" + properties.size() + "#"
                + layout.firstId + "#" + layout.span + "#" + unique.size();
        unique.put(table, new RealTable(name, state, typedTable, properties, layout));
    }

    private static BootstrapStats collectBootstrapStats() throws IllegalAccessException {
        final IdentityHashMap<Object, Boolean> unique = new IdentityHashMap<>();
        long slots = 0L;
        long entries = 0L;
        long denseTables = 0L;
        long sparseTables = 0L;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (StateHolder<?, ?> state : block.getStateDefinition().getPossibleStates()) {
                final Object table = TABLE_FIELD.get(state);
                if (unique.put(table, Boolean.TRUE) == null) {
                    final Object[] dense = (Object[])DENSE_FIELD.get(table);
                    if (dense != null) {
                        ++denseTables;
                        slots += dense.length;
                        for (Object indexer : dense) {
                            if (indexer != null) ++entries;
                        }
                    } else {
                        ++sparseTables;
                        final Int2ObjectOpenHashMap<?> sparse = (Int2ObjectOpenHashMap<?>)SPARSE_FIELD.get(table);
                        entries += sparse.size();
                        slots += fastutilValueCapacity(sparse);
                    }
                }
            }
        }
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            for (StateHolder<?, ?> state : fluid.getStateDefinition().getPossibleStates()) {
                final Object table = TABLE_FIELD.get(state);
                if (unique.put(table, Boolean.TRUE) == null) {
                    final Object[] dense = (Object[])DENSE_FIELD.get(table);
                    if (dense != null) {
                        ++denseTables;
                        slots += dense.length;
                        for (Object indexer : dense) {
                            if (indexer != null) ++entries;
                        }
                    } else {
                        ++sparseTables;
                        final Int2ObjectOpenHashMap<?> sparse = (Int2ObjectOpenHashMap<?>)SPARSE_FIELD.get(table);
                        entries += sparse.size();
                        slots += fastutilValueCapacity(sparse);
                    }
                }
            }
        }
        return new BootstrapStats(unique.size(), denseTables, sparseTables, slots, entries);
    }

    private static int fastutilValueCapacity(Int2ObjectOpenHashMap<?> map) throws IllegalAccessException {
        final Field values = field(map.getClass(), "value");
        return ((Object[])values.get(map)).length;
    }

    private static Layout layout(Object table, List<Property<?>> properties) throws IllegalAccessException {
        return layout(table, properties, true);
    }

    private static Layout layout(Object table, List<Property<?>> properties, boolean includeIndexers)
            throws IllegalAccessException {
        final Object[] dense = (Object[])DENSE_FIELD.get(table);
        final int firstId = (int)FIRST_ID_FIELD.get(table);
        final Int2ObjectOpenHashMap<?> sparse = (Int2ObjectOpenHashMap<?>)SPARSE_FIELD.get(table);
        final Int2ObjectOpenHashMap<Object> indexers = includeIndexers ? new Int2ObjectOpenHashMap<>() : null;
        final int span;
        final String route;
        if (dense != null) {
            span = dense.length;
            route = "dense";
            for (int i = 0; i < dense.length; ++i) {
                if (dense[i] != null && indexers != null) indexers.put(firstId + i, dense[i]);
            }
        } else {
            route = "sparse";
            int max = firstId - 1;
            for (Property<?> property : properties) {
                max = Math.max(max, propertyId(property));
                if (indexers != null) indexers.put(propertyId(property), sparse.get(propertyId(property)));
            }
            span = properties.isEmpty() ? 0 : max - firstId + 1;
        }
        return new Layout(firstId, span, route, indexers);
    }

    private static int propertyId(Property<?> property) {
        return ((PropertyAccess<?>)property).moonrise$getId();
    }

    private static Field field(Class<?> owner, String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class Fixture {
        final String name;
        final List<IntegerProperty> properties;
        final Map<Map<Property<?>, Comparable<?>>, TestState> universe = new HashMap<>();
        final IdentityHashMap<TestState, Long> expectedIndices = new IdentityHashMap<>();
        final ZeroCollidingReferenceStateTable<String, TestState> table;
        final Layout layout;

        Fixture(String name, List<IntegerProperty> properties) {
            this.name = name;
            this.properties = List.copyOf(properties);
            buildUniverse(0, new Reference2ObjectArrayMap<>());
            if (universe.isEmpty()) throw new AssertionError("Fixture universe is empty: " + name);
            universe.values().forEach(state -> state.populateNeighbours(universe));
            final TestState first = universe.values().iterator().next();
            this.table = table(first);
            try {
                this.layout = layout(table, new ArrayList<>(table.getProperties()));
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private void buildUniverse(int propertyIndex, Reference2ObjectArrayMap<Property<?>, Comparable<?>> values) {
            if (propertyIndex == properties.size()) {
                final Reference2ObjectArrayMap<Property<?>, Comparable<?>> key =
                        new Reference2ObjectArrayMap<>(values);
                final TestState state = new TestState(key);
                universe.put(key, state);
                expectedIndices.put(state, oracleIndex(key, properties));
                return;
            }
            final IntegerProperty property = properties.get(propertyIndex);
            for (int value = property.min; value <= property.max; ++value) {
                values.put(property, value);
                buildUniverse(propertyIndex + 1, values);
            }
            values.remove(property);
        }

        void verify() {
            final TestState representative = universe.values().iterator().next();
            final List<Property<?>> expectedVisible = new ArrayList<>(properties);
            if (!new ArrayList<>(representative.getProperties()).equals(expectedVisible)) {
                throw new AssertionError(name + " visible property order changed");
            }
            for (Map.Entry<Map<Property<?>, Comparable<?>>, TestState> entry : universe.entrySet()) {
                final TestState state = entry.getValue();
                final long expectedIndex = oracleIndex(entry.getKey(), properties);
                if (table.getIndex(state) != expectedIndex || state.moonrise$getTableIndex() != expectedIndex) {
                    throw new AssertionError(name + " mixed-radix index mismatch");
                }
                for (IntegerProperty property : properties) {
                    final int current = (Integer)entry.getKey().get(property);
                    for (int value = property.min; value <= property.max; ++value) {
                        final Reference2ObjectArrayMap<Property<?>, Comparable<?>> target =
                                new Reference2ObjectArrayMap<>(entry.getKey());
                        target.put(property, value);
                        final TestState expected = universe.get(target);
                        if (table.get(expectedIndex, property) != current) {
                            throw new AssertionError(name + " get mismatch");
                        }
                        if (table.set(expectedIndex, property, value) != expected
                                || table.trySet(expectedIndex, property, value, state) != expected) {
                            throw new AssertionError(name + " transition mismatch");
                        }
                    }
                    final int invalid = property.max + 1;
                    if (table.set(expectedIndex, property, invalid) != null
                            || table.trySet(expectedIndex, property, invalid, state) != null) {
                        throw new AssertionError(name + " invalid transition semantics changed");
                    }
                    expect(IllegalArgumentException.class, () -> state.setValue(property, invalid));
                    expect(IllegalArgumentException.class, () -> state.trySetValue(property, invalid));
                    expect(NullPointerException.class, () -> table.set(expectedIndex, property, null));
                    expect(NullPointerException.class, () -> table.trySet(expectedIndex, property, null, state));
                }
                if (!state.getValues().equals(entry.getKey())) {
                    throw new AssertionError(name + " state values mismatch");
                }
            }

            final IntegerProperty foreign = IntegerProperty.create(properties.isEmpty() ? "foreign" : properties.getFirst().getName(), 0, 1);
            if (representative.hasProperty(foreign)) {
                throw new AssertionError(name + " foreign hasProperty mismatch");
            }
            if (representative.hasProperty(null)) throw new AssertionError(name + " null hasProperty mismatch");
            if (table.get(representative.moonrise$getTableIndex(), foreign) != null
                    || table.trySet(representative.moonrise$getTableIndex(), foreign, 1, representative) != representative
                    || table.set(representative.moonrise$getTableIndex(), foreign, 1) != null) {
                throw new AssertionError(name + " foreign property semantics changed");
            }
            expect(IllegalArgumentException.class, () -> representative.getValue(foreign));
            expect(IllegalArgumentException.class, () -> representative.setValue(foreign, 1));
            if (representative.trySetValue(foreign, 1) != representative
                    || representative.trySetValue(null, null) != representative) {
                throw new AssertionError(name + " trySet fallback semantics changed");
            }
            expect(NullPointerException.class, () -> representative.getValue(null));
            expect(NullPointerException.class, () -> representative.setValue(null, null));
            expect(NullPointerException.class, () -> table.get(representative.moonrise$getTableIndex(), null));
            expect(NullPointerException.class, () -> table.set(representative.moonrise$getTableIndex(), null, null));
            expect(NullPointerException.class,
                    () -> table.trySet(representative.moonrise$getTableIndex(), null, null, representative));
            if (table.trySet(representative.moonrise$getTableIndex(), foreign, null, representative) != representative) {
                throw new AssertionError(name + " missing-property null trySet fallback semantics changed");
            }
        }

        List<BenchCase> benchmarkCases() {
            final ArrayList<BenchCase> cases = new ArrayList<>();
            final TestState state = universe.values().iterator().next();
            final Int2ObjectOpenHashMap<Object> byId = layout.indexers;
            final LookupPair lookup = new LookupPair(layout.firstId, layout.span, byId);
            for (Operation operation : Operation.values()) {
                cases.add(new TableBenchCase("synthetic", name, operation, table, state,
                        new ArrayList<>(table.getProperties()), foreignProperty(), layout));
            }
            cases.add(new KernelBenchCase(name, lookup, true));
            cases.add(new KernelBenchCase(name, lookup, false));
            return cases;
        }

        private IntegerProperty foreignProperty() {
            final String foreignName = properties.isEmpty() ? name + "_foreign" : properties.getFirst().getName();
            return IntegerProperty.create(foreignName, 0, 1);
        }

        private static ZeroCollidingReferenceStateTable<String, TestState> table(TestState state) {
            try {
                @SuppressWarnings("unchecked")
                final ZeroCollidingReferenceStateTable<String, TestState> result =
                        (ZeroCollidingReferenceStateTable<String, TestState>)TABLE_FIELD.get(state);
                return result;
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }

    private static final class TestState extends StateHolder<String, TestState> {
        TestState(Reference2ObjectArrayMap<Property<?>, Comparable<?>> values) {
            super("property-index-audit", values, null);
        }
    }

    private static long oracleIndex(Map<Property<?>, Comparable<?>> values, List<IntegerProperty> insertionOrder) {
        final ArrayList<IntegerProperty> sorted = new ArrayList<>(insertionOrder);
        sorted.sort(Comparator.comparingInt(PropertyIndexAuditBenchmark::propertyId));
        long index = 0L;
        long multiple = 1L;
        for (IntegerProperty property : sorted) {
            final int valueId = (Integer)values.get(property) - property.min;
            index += valueId * multiple;
            multiple *= (long)property.max - property.min + 1L;
        }
        return index;
    }

    private static void expect(Class<? extends Throwable> expected, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) return;
            throw new AssertionError("Expected " + expected.getName() + " but caught " + actual, actual);
        }
        throw new AssertionError("Expected " + expected.getName() + " but no exception was thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Throwable; }

    private enum Operation { HAS_PROPERTY, GET_INDEX, GET, TRY_SET }

    private abstract static class BenchCase {
        final String kind;
        final String name;
        final Operation operation;
        final int span;
        final int propertyCount;
        final String route;
        final int operationsPerRound;

        BenchCase(String kind, String name, Operation operation, int span, int propertyCount,
                  String route, int operationsPerRound) {
            this.kind = kind;
            this.name = name;
            this.operation = operation;
            this.span = span;
            this.propertyCount = propertyCount;
            this.route = route;
            this.operationsPerRound = operationsPerRound;
        }

        abstract long runRound(int round);
        abstract long expectedRound(int round);

        final long runBatch(int rounds) {
            long checksum = 0L;
            for (int round = 0; round < rounds; ++round) checksum += runRound(round);
            return checksum;
        }

        final long expectedBatchChecksum(int rounds) {
            long checksum = 0L;
            for (int round = 0; round < rounds; ++round) checksum += expectedRound(round);
            return checksum;
        }
    }

    private static final class TableBenchCase extends BenchCase {
        @SuppressWarnings("rawtypes")
        final ZeroCollidingReferenceStateTable table;
        final StateHolder<?, ?> state;
        final List<Property<?>> properties;
        final Property<?> foreign;
        final long tableIndex;
        final Property<?>[] queryProperties;
        final Object[] replacements;
        final long[] expectedResultIndices;
        final long[] expectedValueIds;

        TableBenchCase(String kind, String name, Operation operation, ZeroCollidingReferenceStateTable<?, ?> table,
                       StateHolder<?, ?> state, List<Property<?>> properties, Property<?> foreign,
                       Layout layout) {
            super(kind, name, operation, layout.span, properties.size(), layout.route,
                    switch (operation) {
                        case HAS_PROPERTY, GET, TRY_SET -> properties.size() + 1;
                        case GET_INDEX -> 1;
                    });
            this.table = (ZeroCollidingReferenceStateTable)table;
            this.state = state;
            this.properties = properties;
            this.foreign = foreign;
            this.tableIndex = tableIndex(state.getValues(), properties);
            if (((PropertyAccessStateHolder)state).moonrise$getTableIndex() != tableIndex) {
                throw new AssertionError(name + " state index mismatch");
            }
            this.queryProperties = properties.toArray(Property<?>[]::new);
            this.replacements = new Object[queryProperties.length];
            this.expectedResultIndices = new long[queryProperties.length];
            this.expectedValueIds = new long[queryProperties.length];
            for (int i = 0; i < queryProperties.length; ++i) {
                final Property<?> property = queryProperties[i];
                final Object current = state.getValues().get(property);
                final Object replacement = nextValue(property, current);
                this.replacements[i] = replacement;
                final Map<Property<?>, Comparable<?>> target = new IdentityHashMap<>(state.getValues());
                target.put(property, (Comparable<?>)replacement);
                this.expectedResultIndices[i] = tableIndex(target, properties);
                this.expectedValueIds[i] = propertyValueId(property, current);
            }
        }

        @Override long runRound(int round) {
            return switch (operation) {
                case GET_INDEX -> table.getIndex(state);
                case HAS_PROPERTY -> {
                    long value = 0L;
                    for (Property<?> property : queryProperties) if (table.hasProperty(property)) ++value;
                    if (table.hasProperty(foreign)) value += properties.size() + 1L;
                    yield value;
                }
                case GET -> {
                    long value = 0L;
                    for (int i = 0; i < queryProperties.length; ++i) {
                        final Object result = table.get(tableIndex, queryProperties[i]);
                        if (result == null || propertyValueId(queryProperties[i], result) != expectedValueIds[i]) {
                            throw new AssertionError(name + " get mismatch");
                        }
                        value += expectedValueIds[i];
                    }
                    if (table.get(tableIndex, foreign) != null) throw new AssertionError(name + " foreign get mismatch");
                    value += -1L;
                    yield value;
                }
                case TRY_SET -> {
                    long value = 0L;
                    for (int i = 0; i < queryProperties.length; ++i) {
                        final Object result = table.trySet(tableIndex, queryProperties[i],
                                (Comparable)replacements[i], state);
                        final long resultIndex = stateIndex(result);
                        if (resultIndex != expectedResultIndices[i]) throw new AssertionError(name + " trySet mismatch");
                        value += resultIndex;
                    }
                    final Object result = table.trySet(tableIndex, foreign, 1, state);
                    if (result != state) throw new AssertionError(name + " foreign trySet mismatch");
                    value += tableIndex;
                    yield value;
                }
            };
        }

        @Override long expectedRound(int round) {
            return switch (operation) {
                case GET_INDEX -> tableIndex;
                case HAS_PROPERTY -> properties.size();
                case GET -> {
                    long value = 0L;
                    for (long expectedValueId : expectedValueIds) value += expectedValueId;
                    value += -1L;
                    yield value;
                }
                case TRY_SET -> {
                    long value = 0L;
                    for (long expectedResultIndex : expectedResultIndices) value += expectedResultIndex;
                    value += tableIndex;
                    yield value;
                }
            };
        }

        private static long stateIndex(Object value) {
            return ((PropertyAccessStateHolder)value).moonrise$getTableIndex();
        }

        private static long tableIndex(Map<Property<?>, Comparable<?>> values, List<Property<?>> properties) {
            final List<Property<?>> sorted = new ArrayList<>(properties);
            sorted.sort(Comparator.comparingInt(PropertyIndexAuditBenchmark::propertyId));
            long index = 0L;
            long multiple = 1L;
            for (Property<?> property : sorted) {
                index += propertyValueId(property, values.get(property)) * multiple;
                multiple *= property.getPossibleValues().size();
            }
            return index;
        }
    }

    private static final class KernelBenchCase extends BenchCase {
        final LookupPair lookup;
        final boolean array;
        KernelBenchCase(String name, LookupPair lookup, boolean array) {
            super("kernel-ab", name, null, lookup.span, lookup.intMap.size(), array ? "offset-array" : "int-map",
                    lookup.queries.length);
            this.lookup = lookup;
            this.array = array;
        }
        @Override long runRound(int round) { return array ? lookup.runArray(round) : lookup.runMap(round); }
        @Override long expectedRound(int round) { return lookup.expectedRound(round); }
    }

    private static final class LookupPair {
        final int firstId;
        final int span;
        final Object[] offset;
        final Int2ObjectOpenHashMap<Object> intMap = new Int2ObjectOpenHashMap<>();
        final int[] queries;
        final Object[] expectedValues;
        final long[] tokens;

        LookupPair(int firstId, int span, Int2ObjectOpenHashMap<Object> indexers) {
            this.firstId = firstId;
            this.span = span;
            this.offset = new Object[span];
            for (var entry : indexers.int2ObjectEntrySet()) {
                final int id = entry.getIntKey();
                final Object indexer = entry.getValue();
                if (id >= firstId && id - firstId < offset.length) offset[id - firstId] = indexer;
                intMap.put(id, indexer);
            }
            final int low = firstId - 1;
            final int high = span == 0 ? firstId + 1 : firstId + span;
            this.queries = new int[high - low + 1];
            this.expectedValues = new Object[queries.length];
            this.tokens = new long[queries.length];
            for (int i = 0; i < queries.length; ++i) queries[i] = low + i;
            for (int i = 0; i < queries.length; ++i) {
                final int id = queries[i];
                final Object wanted = indexers.get(id);
                expectedValues[i] = wanted;
                tokens[i] = token(id);
                final Object arrayValue = arrayGet(id);
                final Object mapValue = intMap.get(id);
                if (arrayValue != wanted || mapValue != wanted) {
                    throw new AssertionError("Offset array and Int2ObjectOpenHashMap lookup differ at id=" + id);
                }
            }
        }

        Object arrayGet(int id) {
            final int offset = id - firstId;
            return offset < 0 || offset >= this.offset.length ? null : this.offset[offset];
        }

        long runArray(int round) {
            long checksum = 0L;
            for (int i = 0; i < queries.length; ++i) {
                final int queryIndex = (i + round) % queries.length;
                final int id = queries[queryIndex];
                final Object wanted = expectedValues[queryIndex];
                final long token = tokens[queryIndex];
                if (arrayGet(id) != wanted) throw new AssertionError("Offset-array lookup mismatch");
                checksum += token;
            }
            return checksum;
        }

        long runMap(int round) {
            long checksum = 0L;
            for (int i = 0; i < queries.length; ++i) {
                final int queryIndex = (i + round) % queries.length;
                if (intMap.get(queries[queryIndex]) != expectedValues[queryIndex]) {
                    throw new AssertionError("Int-map lookup mismatch");
                }
                checksum += tokens[queryIndex];
            }
            return checksum;
        }

        long expectedRound(int round) {
            long checksum = 0L;
            for (int i = 0; i < queries.length; ++i) {
                checksum += tokens[(i + round) % queries.length];
            }
            return checksum;
        }

        private static long token(int id) { return (long)id - Integer.MIN_VALUE + 1L; }
    }

    private static long propertyValueId(Property<?> property, Object value) {
        return ((PropertyAccess)property).moonrise$getIdFor((Comparable)value);
    }

    private static Object nextValue(Property<?> property, Object value) {
        final PropertyAccess access = (PropertyAccess)property;
        final int count = property.getPossibleValues().size();
        final int currentId = access.moonrise$getIdFor((Comparable)value);
        return access.moonrise$getById((currentId + 1) % count);
    }

    private static final class RealTable {
        final String name;
        final StateHolder<?, ?> state;
        final ZeroCollidingReferenceStateTable<?, ?> table;
        final List<Property<?>> properties;
        final Layout layout;
        final Property<?> foreign;

        RealTable(String name, StateHolder<?, ?> state, ZeroCollidingReferenceStateTable<?, ?> table,
                  List<Property<?>> properties, Layout layout) {
            this.name = name;
            this.state = state;
            this.table = table;
            this.properties = properties;
            this.layout = layout;
            final String foreignName = properties.isEmpty()
                    ? "property_index_real_foreign_" + layout.firstId
                    : properties.getFirst().getName();
            this.foreign = IntegerProperty.create(foreignName, 0, 1);
        }

        List<BenchCase> benchmarkCases() {
            final ArrayList<BenchCase> result = new ArrayList<>();
            for (Operation operation : Operation.values()) {
                result.add(new TableBenchCase("real", name, operation, table, state, properties, foreign, layout));
            }
            return result;
        }
    }

    private record Layout(int firstId, int span, String route, Int2ObjectOpenHashMap<Object> indexers) {}
    private record BootstrapStats(long tables, long denseTables, long sparseTables, long slots, long entries) {}
    private record Measurement(BenchCase benchCase, int sample, int rounds, long checksum,
                              long elapsedNs, long allocatedBytes) {}
}
