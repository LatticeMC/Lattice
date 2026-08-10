package com.latticemc.lattice.util.collection;

import it.unimi.dsi.fastutil.objects.AbstractObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;

/**
 * Insertion-ordered behavior set with a running-behavior counter.
 *
 * <p>Adapted from Leaf's {@code BehaviorControlArraySet} in "Replace brain with optimized collection".</p>
 *
 * <p>Original authors: HaHaWTH, Taiyou06, and hayanesuru<br>
 * Original license: GPL-3.0-only<br>
 * Source: https://github.com/Winds-Studio/Leaf/blob/178bcb4/leaf-server/src/main/java/org/dreeam/leaf/util/map/BehaviorControlArraySet.java</p>
 */
public final class BehaviorControlArraySet<E extends LivingEntity> extends AbstractObjectSet<BehaviorControl<? super E>> {
    private static final BehaviorControl<?>[] EMPTY = new BehaviorControl<?>[0];

    private BehaviorControl<? super E>[] values = (BehaviorControl<? super E>[]) EMPTY;
    private int size;
    private int running;

    public BehaviorControlArraySet() {
    }

    /**
     * Allocates the backing array once for a known behavior count.
     *
     * <p>The growth path doubles from two, so a group of nine behaviors otherwise
     * leaves four dead arrays behind and ends up with seven unused slots. Brains
     * are built once per mob and never shrink, so paying for the exact size up
     * front is both less garbage and less retained memory.</p>
     *
     * @param expectedSize the number of behaviors this group will hold; ignored when not positive
     */
    public BehaviorControlArraySet(int expectedSize) {
        if (expectedSize > 0) {
            this.values = (BehaviorControl<? super E>[]) new BehaviorControl<?>[expectedSize];
        }
    }

    public BehaviorControl<? super E>[] raw() {
        return this.values;
    }

    public void incrementRunning() {
        this.running++;
    }

    public void decrementRunning() {
        this.running--;
    }

    public boolean hasRunning() {
        return this.running != 0;
    }

    @Override
    public int size() {
        return this.size;
    }

    @Override
    public boolean isEmpty() {
        return this.size == 0;
    }

    @Override
    public boolean contains(Object object) {
        return this.find(object) >= 0;
    }

    @Override
    public boolean add(BehaviorControl<? super E> behavior) {
        if (this.find(behavior) >= 0) {
            return false;
        }
        if (this.size == this.values.length) {
            this.values = Arrays.copyOf(this.values, this.size == 0 ? 2 : this.size * 2);
        }
        this.values[this.size++] = behavior;
        return true;
    }

    @Override
    public boolean remove(Object object) {
        int index = this.find(object);
        if (index < 0) {
            return false;
        }
        BehaviorControl<? super E> removed = this.values[index];
        int tail = this.size - index - 1;
        if (tail > 0) {
            System.arraycopy(this.values, index + 1, this.values, index, tail);
        }
        this.values[--this.size] = null;
        if (removed.getStatus() == Behavior.Status.RUNNING) {
            this.running--;
        }
        return true;
    }

    @Override
    public void clear() {
        Arrays.fill(this.values, 0, this.size, null);
        this.size = 0;
        this.running = 0;
    }

    @Override
    public ObjectIterator<BehaviorControl<? super E>> iterator() {
        return new ObjectIterator<>() {
            private int next;
            private int current = -1;

            @Override
            public boolean hasNext() {
                return this.next < BehaviorControlArraySet.this.size;
            }

            @Override
            public BehaviorControl<? super E> next() {
                if (!this.hasNext()) {
                    throw new NoSuchElementException();
                }
                this.current = this.next++;
                return Objects.requireNonNull(BehaviorControlArraySet.this.values[this.current]);
            }

            @Override
            public void remove() {
                if (this.current < 0) {
                    throw new IllegalStateException();
                }
                BehaviorControl<? super E> removed = BehaviorControlArraySet.this.values[this.current];
                int tail = BehaviorControlArraySet.this.size - this.current - 1;
                if (tail > 0) {
                    System.arraycopy(BehaviorControlArraySet.this.values, this.current + 1, BehaviorControlArraySet.this.values, this.current, tail);
                }
                BehaviorControlArraySet.this.values[--BehaviorControlArraySet.this.size] = null;
                if (removed.getStatus() == Behavior.Status.RUNNING) {
                    BehaviorControlArraySet.this.running--;
                }
                this.next = this.current;
                this.current = -1;
            }

            @Override
            public void forEachRemaining(Consumer<? super BehaviorControl<? super E>> action) {
                while (this.hasNext()) {
                    action.accept(this.next());
                }
            }
        };
    }

    private int find(Object object) {
        for (int index = this.size - 1; index >= 0; index--) {
            if (Objects.equals(this.values[index], object)) {
                return index;
            }
        }
        return -1;
    }
}
