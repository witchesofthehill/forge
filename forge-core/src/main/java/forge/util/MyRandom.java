/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.util;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * <p>
 * MyRandom class.<br>
 * Preferably all Random numbers should be retrieved using this wrapper class
 * </p>
 * 
 * @author Forge
 * @version $Id$
 */
public class MyRandom {
    private static final ThreadLocal<AtomicLong> calls = ThreadLocal.withInitial(AtomicLong::new);
    private static final ThreadLocal<Random> random =
            ThreadLocal.withInitial(() -> new CountingRandom(new SecureRandom()));

    private static final class CountingRandom extends Random {
        private final Random delegate;
        CountingRandom(Random delegate) { super(0); this.delegate = delegate; }
        @Override protected int next(int bits) {
            calls.get().incrementAndGet();
            if (TRACE) { trace(); }
            return delegate.nextInt() >>> (32 - bits);
        }
        @Override public void setSeed(long seed) {
            if (delegate != null) delegate.setSeed(seed);
        }
    }
    public static long callCount() { return calls.get().get(); }

    private static final boolean TRACE = Boolean.getBoolean("forge.undo.debug");
    private static final ThreadLocal<Map<String, LongAdder>> SITES =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    private static void trace() {
        final StringBuilder key = new StringBuilder(Thread.currentThread().getName()).append(" <- ");
        int shown = 0;
        for (final StackTraceElement e : new Throwable().getStackTrace()) {
            final String c = e.getClassName();
            if (c.startsWith("java.") || c.startsWith("jdk.") || c.startsWith("forge.util.MyRandom")) { continue; }
            key.append(c.substring(c.lastIndexOf('.') + 1)).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber()).append(shown < 3 ? " < " : "");
            if (++shown == 4) { break; }
        }
        SITES.get().computeIfAbsent(key.toString(), k -> new LongAdder()).increment();
    }

    public static String drainTrace(final int top) {
        final StringBuilder sb = new StringBuilder();
        final Map<String, LongAdder> sites = SITES.get();
        sites.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(top)
                .forEach(e -> sb.append("\n    ").append(e.getValue().sum()).append("x ").append(e.getKey()));
        sites.clear();
        return sb.toString();
    }

    public static boolean percentTrue(final int percent) {
        return percent > MyRandom.getRandom().nextInt(100);
    }

    /**
     * Gets the random.
     *
     * @return the random
     */
    public static Random getRandom() {
        return MyRandom.random.get();
    }

    /**
     * Sets the random provider. Used for deterministic simulation.
     * Thread-local: only affects the calling thread.
     * @param rng the random
     */
    public static void setRandom(Random rng) {
        MyRandom.random.set(rng instanceof CountingRandom ? rng : new CountingRandom(rng));
    }

    public static int[] splitIntoRandomGroups(final int value, final int numGroups) {
        int[] groups = new int[numGroups];

        for (int i = 0; i < value; i++) {
            groups[getRandom().nextInt(numGroups)]++;
        }

        return groups;
    }
}
