package com.latticemc.lattice.util.collection;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import net.minecraft.world.entity.schedule.Activity;
import org.jspecify.annotations.Nullable;

/**
 * Small insertion-ordered activity map with identity-free dense integer keys.
 *
 * <p>Adapted from Leaf's {@code ActivityArrayMap} in "Replace brain with optimized collection".</p>
 *
 * <p>Original authors: HaHaWTH, Taiyou06, and hayanesuru<br>
 * Original license: GPL-3.0-only<br>
 * Source: https://github.com/Winds-Studio/Leaf/blob/178bcb4/leaf-server/src/main/java/org/dreeam/leaf/util/map/ActivityArrayMap.java</p>
 */
public final class ActivityArrayMap<V> extends AbstractMap<Activity, V> {
    private int[] keys = new int[0];
    private Object[] values = new Object[0];
    private int size;
    private int bits;
    private @Nullable Set<Entry<Activity, V>> entries;
    private @Nullable Set<Activity> keyView;
    private @Nullable Collection<V> valueView;

    public @Nullable V getValue(int activityId) {
        int index = this.find(activityId);
        return index < 0 ? null : (V) this.values[index];
    }

    public V valueAt(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException(index);
        }
        return (V) this.values[index];
    }

    @Override
    public @Nullable V put(Activity activity, @Nullable V value) {
        Objects.requireNonNull(value, "ActivityArrayMap does not support null values");
        int index = this.find(activity.id);
        if (index >= 0) {
            V previous = (V) this.values[index];
            this.values[index] = value;
            return previous;
        }
        this.ensureCapacity();
        this.keys[this.size] = activity.id;
        this.values[this.size] = value;
        this.bits |= 1 << activity.id;
        this.size++;
        return null;
    }

    @Override
    public @Nullable V get(Object key) {
        return key instanceof Activity activity ? this.getValue(activity.id) : null;
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof Activity activity && (this.bits & 1 << activity.id) != 0;
    }

    @Override
    public @Nullable V remove(Object key) {
        if (!(key instanceof Activity activity)) {
            return null;
        }
        int index = this.find(activity.id);
        if (index < 0) {
            return null;
        }
        V previous = (V) this.values[index];
        this.removeAt(index);
        return previous;
    }

    @Override
    public void clear() {
        Arrays.fill(this.values, 0, this.size, null);
        this.size = 0;
        this.bits = 0;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public Set<Entry<Activity, V>> entrySet() {
        if (this.entries == null) {
            this.entries = new EntrySet();
        }
        return this.entries;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link AbstractMap} would route this through {@link #entrySet()} and
     * allocate an entry per element only to call {@code getKey()} on it.</p>
     */
    @Override
    public Set<Activity> keySet() {
        if (this.keyView == null) {
            this.keyView = new KeySet();
        }
        return this.keyView;
    }

    /**
     * {@inheritDoc}
     *
     * <p>See {@link #keySet()}: the values are already dense, so iterating them
     * needs no entry objects at all.</p>
     */
    @Override
    public Collection<V> values() {
        if (this.valueView == null) {
            this.valueView = new Values();
        }
        return this.valueView;
    }

    private int find(int activityId) {
        if ((this.bits & 1 << activityId) == 0) {
            return -1;
        }
        for (int index = 0; index < this.size; index++) {
            if (this.keys[index] == activityId) {
                return index;
            }
        }
        return -1;
    }

    private void ensureCapacity() {
        if (this.size < this.keys.length) {
            return;
        }
        int capacity = Math.max(2, this.keys.length + this.keys.length / 2);
        this.keys = Arrays.copyOf(this.keys, capacity);
        this.values = Arrays.copyOf(this.values, capacity);
    }

    private void removeAt(int index) {
        this.bits &= ~(1 << this.keys[index]);
        int tail = this.size - index - 1;
        if (tail > 0) {
            System.arraycopy(this.keys, index + 1, this.keys, index, tail);
            System.arraycopy(this.values, index + 1, this.values, index, tail);
        }
        this.values[--this.size] = null;
    }

    private final class EntrySet extends AbstractSet<Entry<Activity, V>> {
        @Override
        public int size() {
            return ActivityArrayMap.this.size;
        }

        @Override
        public void clear() {
            ActivityArrayMap.this.clear();
        }

        @Override
        public Iterator<Entry<Activity, V>> iterator() {
            return new ViewIterator<>() {
                @Override
                Entry<Activity, V> valueAt(int index) {
                    // Unlike the key and value views this one still has to hand out
                    // an object per element: a shared instance would alias itself in
                    // anything that collects the entries, such as AbstractSet#toArray.
                    return new MapEntry(ActivityArrayMap.this.keys[index]);
                }
            };
        }
    }

    private final class KeySet extends AbstractSet<Activity> {
        @Override
        public int size() {
            return ActivityArrayMap.this.size;
        }

        @Override
        public boolean contains(Object key) {
            return ActivityArrayMap.this.containsKey(key);
        }

        @Override
        public boolean remove(Object key) {
            return ActivityArrayMap.this.remove(key) != null;
        }

        @Override
        public void clear() {
            ActivityArrayMap.this.clear();
        }

        @Override
        public Iterator<Activity> iterator() {
            return new ViewIterator<>() {
                @Override
                Activity valueAt(int index) {
                    return ActivityRegistryIndex.byId(ActivityArrayMap.this.keys[index]);
                }
            };
        }
    }

    private final class Values extends AbstractCollection<V> {
        @Override
        public int size() {
            return ActivityArrayMap.this.size;
        }

        @Override
        public void clear() {
            ActivityArrayMap.this.clear();
        }

        @Override
        public Iterator<V> iterator() {
            return new ViewIterator<>() {
                @Override
                V valueAt(int index) {
                    return (V) ActivityArrayMap.this.values[index];
                }
            };
        }
    }

    /** Shared cursor for the key and value views, neither of which needs entry objects. */
    private abstract class ViewIterator<T> implements Iterator<T> {
        private int next;
        private int current = -1;

        abstract T valueAt(int index);

        @Override
        public boolean hasNext() {
            return this.next < ActivityArrayMap.this.size;
        }

        @Override
        public T next() {
            if (!this.hasNext()) {
                throw new NoSuchElementException();
            }
            this.current = this.next++;
            return this.valueAt(this.current);
        }

        @Override
        public void remove() {
            if (this.current < 0) {
                throw new IllegalStateException();
            }
            ActivityArrayMap.this.removeAt(this.current);
            this.next = this.current;
            this.current = -1;
        }
    }

    private final class MapEntry implements Entry<Activity, V> {
        private final int activityId;

        private MapEntry(int activityId) {
            this.activityId = activityId;
        }

        @Override
        public Activity getKey() {
            return ActivityRegistryIndex.byId(this.activityId);
        }

        @Override
        public V getValue() {
            return ActivityArrayMap.this.getValue(this.activityId);
        }

        @Override
        public V setValue(V value) {
            return ActivityArrayMap.this.put(this.getKey(), value);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Entry<?, ?> entry
                && Objects.equals(this.getKey(), entry.getKey())
                && Objects.equals(this.getValue(), entry.getValue());
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.getKey()) ^ Objects.hashCode(this.getValue());
        }
    }
}
