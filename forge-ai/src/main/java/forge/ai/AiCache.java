package forge.ai;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;

import forge.game.Game;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class AiCache {

    // stores result + args as vector, per game: an entry holds its arguments and
    // its result, which are game scoped objects, so one map for the whole process
    // keeps every finished game reachable. A host that runs many games in one JVM
    // or isolate never gets that memory back.
    private final static Map<Game, Multimap<String, List<Object>>> byGame = new IdentityHashMap<>();

    public static boolean identity(Object a, Object b) {
        return a == b;
    }

    // Finished games are dropped here rather than through a listener: forge-game
    // cannot call into forge-ai, and the map only ever holds one entry per game
    // in progress.
    private static Multimap<String, List<Object>> cacheFor(final Game game) {
        synchronized (byGame) {
            byGame.keySet().removeIf(Game::isGameOver);
            return byGame.computeIfAbsent(game,
                    g -> Multimaps.synchronizedMultimap(ArrayListMultimap.create()));
        }
    }

    // the cache is shared between the players of one game for calculations that
    // can be shared, but that also means unwanted collisions need to be considered:
    // for that you can pass Functions that compare the args
    public static <T> T getCached(String key, Game game, Supplier<T> func, List<BiFunction<Object, Object, Boolean>> argsCheck, Object... args) {
        // TODO would like a good strategy to derive default key, but there's no clean way to obtain the method name
        Multimap<String, List<Object>> dataMap = cacheFor(game);
        // Guava's synchronizedMultimap requires manual synchronization when
        // iterating a returned collection view, else a concurrent put or clear
        // from another thread of the same game throws CME.
        synchronized (dataMap) {
            for (List<Object> cached : dataMap.get(key)) {
                boolean hit = true;
                for (int i = 0; i < args.length; i++) {
                    BiFunction<Object, Object, Boolean> checker = argsCheck == null ? Object::equals : argsCheck.get(i);
                    if (!checker.apply(args[i], cached.get(i + 1))) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    return (T) cached.get(0);
                }
            }
        }
        T result = func.get();
        List<Object> cached = Lists.newArrayList(result);
        cached.addAll(Arrays.asList(args));
        dataMap.put(key, cached);
        return result;
    }

    // TODO add different scopes + staleness indicator
    public static void clear(final Game game) {
        synchronized (byGame) {
            byGame.keySet().removeIf(Game::isGameOver);
            Multimap<String, List<Object>> dataMap = byGame.get(game);
            if (dataMap != null) {
                dataMap.clear();
            }
        }
    }

}
