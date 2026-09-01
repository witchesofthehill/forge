package forge.game;

import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic counters for the four-seat decision-cost investigation (#817).
 *
 * Wall clock on this workload is not a measurement: games diverge run to run
 * even at a fixed seed, so game length swamps whatever is under test. A count
 * does not. These say how many times the engine walks the battlefield for a
 * single decision, and how that number moves with board size.
 *
 * Off unless {@code -Dforge.engineCounters=true}. The flag is static final and
 * read at class init, so every guarded increment folds away when it is off.
 * That is the point: an earlier round of this (9d14d1511bf) used atomics and
 * two nanoTime calls per checkStaticAbilities and had to be reverted.
 *
 * Measurement scaffolding. Do not merge into the fork's manabrew branch.
 */
public final class EngineCounters {
    public static final boolean ENABLED = Boolean.getBoolean("forge.engineCounters");

    /** Entries into CardProperty.cardHasProperty. */
    public static long cardHasProperty;
    /** Entries into Card.isValid, one per card per restriction match. */
    public static long cardIsValid;
    /** Calls to CardLists.getValidCards, and cards handed to them. */
    public static long validCardsCalls;
    public static long validCardsExamined;
    /** Restriction strings re-split on every getValidCards call. */
    public static long restrictionSplits;
    /** checkStaticAbilities passes that ran, and passes skipped by the hold. */
    public static long staticPasses;
    public static long staticPassesHeld;
    /** Sum over passes of the continuous static abilities collected. */
    public static long staticAbilitiesSeen;
    /** Calls to StaticAbilityContinuous.getAffectedCards. */
    public static long affectedCardsCalls;
    /** Times an AI seat took priority and enumerated what it could do. */
    public static long aiPriority;
    /** Battlefield size at the last static pass. */
    public static int battlefield;

    /**
     * Time inside the two methods, outermost call only so recursion is not
     * counted twice. nanoTime is not free, so subtract the calibration: these
     * are two nanoTime calls per entry, and CALIBRATION_NANOS says what a pair
     * costs on this machine.
     */
    public static long cardHasPropertyNanos;
    public static long cardIsValidNanos;
    public static int propertyDepth;
    public static int validDepth;

    /** Cost of the pair of nanoTime calls one timed entry adds, in nanoseconds. */
    public static final long CALIBRATION_NANOS = ENABLED ? calibrate() : 0;

    /** Kept non-final and read back so the calibration loop cannot fold away. */
    private static long sink;

    private static long calibrate() {
        long best = Long.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            final int n = 200_000;
            final long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                final long a = System.nanoTime();
                sink += System.nanoTime() - a;
            }
            final long each = (System.nanoTime() - t0) / n;
            if (each < best) {
                best = each;
            }
        }
        return best;
    }

    /** How often each property string reaches the equals chain. */
    private static final Map<String, long[]> PROPERTIES = new TreeMap<>();

    /** Who asked for a validity filter, sampled, and how big the list was. */
    private static final Map<String, long[]> CALLERS = new TreeMap<>();
    private static final StackWalker WALKER = ENABLED
            ? StackWalker.getInstance(java.util.Collections.emptySet(), 12)
            : null;
    /** One call in SAMPLE is walked. A stack walk costs far more than the count. */
    private static final int SAMPLE = 64;
    private static long callTick;

    private EngineCounters() {}

    /** Cards handed to a validity filter, counted without draining an iterator. */
    public static void countValidCards(Iterable<forge.game.card.Card> cardList) {
        validCardsCalls++;
        // FCollectionView extends Collection, so this covers the card collections too.
        final int size = cardList instanceof java.util.Collection
                ? ((java.util.Collection<?>) cardList).size() : 0;
        validCardsExamined += size;
        if ((++callTick % SAMPLE) == 0) {
            sampleCaller(size);
        }
    }

    /**
     * The two frames above CardLists, and the size of the list they handed it.
     * Sampled: a stack walk costs far more than the count it annotates, so this
     * says where the calls come from, not what they cost.
     */
    private static void sampleCaller(int size) {
        final String where = WALKER.walk(frames -> frames
                .map(f -> f.getClassName() + "." + f.getMethodName())
                .filter(name -> !name.startsWith("forge.game.EngineCounters")
                        && !name.startsWith("forge.game.card.CardLists"))
                .limit(2)
                .reduce((a, b) -> a + " <- " + b)
                .orElse("?"));
        final long[] cell = CALLERS.computeIfAbsent(where, w -> new long[2]);
        cell[0]++;
        cell[1] += size;
    }

    public static void property(String property) {
        PROPERTIES.computeIfAbsent(property, p -> new long[1])[0]++;
    }

    public static String snapshotJson() {
        StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        num(sb, "cardHasProperty", cardHasProperty).append(',');
        num(sb, "cardIsValid", cardIsValid).append(',');
        num(sb, "validCardsCalls", validCardsCalls).append(',');
        num(sb, "validCardsExamined", validCardsExamined).append(',');
        num(sb, "restrictionSplits", restrictionSplits).append(',');
        num(sb, "staticPasses", staticPasses).append(',');
        num(sb, "staticPassesHeld", staticPassesHeld).append(',');
        num(sb, "staticAbilitiesSeen", staticAbilitiesSeen).append(',');
        num(sb, "affectedCardsCalls", affectedCardsCalls).append(',');
        num(sb, "aiPriority", aiPriority).append(',');
        num(sb, "cardHasPropertyNanos", cardHasPropertyNanos).append(',');
        num(sb, "cardIsValidNanos", cardIsValidNanos).append(',');
        num(sb, "calibrationNanos", CALIBRATION_NANOS).append(',');
        num(sb, "battlefield", battlefield);
        return sb.append('}').toString();
    }

    /** Sampled getValidCards callers: calls seen, and cards they handed over. */
    public static String callersJson() {
        StringBuilder sb = new StringBuilder(4096)
                .append("{\"sample\":").append(SAMPLE).append(",\"callers\":{");
        boolean first = true;
        for (Map.Entry<String, long[]> e : CALLERS.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":[")
              .append(e.getValue()[0]).append(',').append(e.getValue()[1]).append(']');
        }
        return sb.append("}}").toString();
    }

    /** The property histogram, biggest first. Ask for this once, at the end. */
    public static String propertiesJson() {
        StringBuilder sb = new StringBuilder(4096).append('{');
        boolean first = true;
        for (Map.Entry<String, long[]> e : PROPERTIES.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":").append(e.getValue()[0]);
        }
        return sb.append('}').toString();
    }

    private static StringBuilder num(StringBuilder sb, String key, long value) {
        return sb.append('"').append(key).append("\":").append(value);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
