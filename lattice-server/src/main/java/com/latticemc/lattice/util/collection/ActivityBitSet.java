package com.latticemc.lattice.util.collection;

import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Bit-set backed activity set adapted from Leaf's Brain collection optimization.
 *
 * <p>Original authors: HaHaWTH, Taiyou06, and hayanesuru<br>
 * Original license: GPL-3.0-only<br>
 * Source: https://github.com/Winds-Studio/Leaf/blob/178bcb4/leaf-server/src/main/java/org/dreeam/leaf/util/map/ActivityBitSet.java</p>
 */
public final class ActivityBitSet extends AbstractObjectSet<Activity> {
    private int bits;
    private boolean dirty = true;

    public boolean consumeDirty() {
        if (!this.dirty) {
            return false;
        }
        this.dirty = false;
        return true;
    }

    public int bits() {
        return this.bits;
    }

    @Override
    public boolean add(Activity activity) {
        int mask = 1 << activity.id;
        if ((this.bits & mask) != 0) {
            return false;
        }
        this.bits |= mask;
        this.dirty = true;
        return true;
    }

    @Override
    public boolean remove(Object object) {
        if (!(object instanceof Activity activity)) {
            return false;
        }
        int mask = 1 << activity.id;
        if ((this.bits & mask) == 0) {
            return false;
        }
        this.bits &= ~mask;
        this.dirty = true;
        return true;
    }

    @Override
    public boolean contains(Object object) {
        return object instanceof Activity activity && (this.bits & 1 << activity.id) != 0;
    }

    @Override
    public ObjectIterator<Activity> iterator() {
        return new ObjectIterator<>() {
            private int next = this.findNext(0);

            private int findNext(int from) {
                int index = from;
                while (index < ActivityRegistryIndex.SIZE && (ActivityBitSet.this.bits & 1 << index) == 0) {
                    index++;
                }
                return index;
            }

            @Override
            public boolean hasNext() {
                return this.next < ActivityRegistryIndex.SIZE;
            }

            @Override
            public Activity next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                Activity activity = ActivityRegistryIndex.byId(this.next);
                this.next = this.findNext(this.next + 1);
                return activity;
            }
        };
    }

    @Override
    public int size() {
        return Integer.bitCount(this.bits);
    }

    @Override
    public void clear() {
        this.bits = 0;
        this.dirty = true;
    }

    @Override
    public boolean equals(Object object) {
        return object == this || object instanceof Set<?> set && set.size() == this.size() && this.containsAll(set);
    }

    @Override
    public int hashCode() {
        int hash = 0;
        for (int id = 0; id < ActivityRegistryIndex.SIZE; id++) {
            if ((this.bits & 1 << id) != 0) {
                hash += ActivityRegistryIndex.byId(id).hashCode();
            }
        }
        return hash;
    }
}
