package forge.util;

import java.util.concurrent.*;

public class ThreadUtil {
    static {
        System.out.printf("(ThreadUtil first call): Running with priority %d%n", Thread.currentThread().getPriority());
    }

    private static class WorkerThreadFactory implements ThreadFactory {
        private int countr = 0;
        private String prefix = "";

        public WorkerThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        public Thread newThread(Runnable r) {
            return new Thread(r, prefix + "-" + countr++);
        }
    }

    private final static ExecutorService gameThreadPool = Executors.newCachedThreadPool(new WorkerThreadFactory("Game"));
    private static ExecutorService getGameThreadPool() { return gameThreadPool; }
    private final static ScheduledExecutorService scheduledPool = Executors.newScheduledThreadPool(2, new WorkerThreadFactory("Delayed"));
    private static ScheduledExecutorService getScheduledPool() { return scheduledPool; }

    // This pool is designed to parallel CPU or IO intensive tasks like parse cards or download images, assuming a load factor of 0.5
    public final static ExecutorService getComputingPool(float loadFactor) {
        return Executors.newFixedThreadPool((int)(Runtime.getRuntime().availableProcessors() / (1-loadFactor)));
    }

    // GraalVM Web Image runs on a single JS thread: Thread.start() is a silent
    // no-op, so anything handed to an executor never runs and every timed get()
    // returns null instead of failing. Set -Dforge.synchronous=true on that
    // target to keep the work on the calling thread.
    private static final boolean SYNCHRONOUS = Boolean.getBoolean("forge.synchronous");

    public static boolean isSynchronous() {
        return SYNCHRONOUS;
    }

    public static boolean isMultiCoreSystem() {
        return !SYNCHRONOUS && Runtime.getRuntime().availableProcessors() > 1;
    }

    public static void invokeInGameThread(Runnable toRun) {
        if (SYNCHRONOUS) {
            toRun.run();
            return;
        }
        getGameThreadPool().execute(toRun);
    }

    public static ScheduledFuture<?> delay(int milliseconds, Runnable inputUpdater) {
        return getScheduledPool().schedule(inputUpdater, milliseconds, TimeUnit.MILLISECONDS);
    }

    public static boolean isGameThread() {
        return SYNCHRONOUS || Thread.currentThread().getName().startsWith("Game");
    }

    private static ExecutorService service = Executors.newWorkStealingPool();
    public static ExecutorService getServicePool() {
        return service;
    }
    public static void refreshServicePool() {
        service = Executors.newWorkStealingPool();
    }
    public static <T> T limit(Callable<T> task, long millis){
        Future<T> future = null;
        T result;
        if (SYNCHRONOUS) {
            try {
                return task.call();
            } catch (Exception e) {
                return null;
            }
        }
        try {
            future = service.submit(task);
            result = future.get(millis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            result = null;
        } finally {
            if (future != null)
                future.cancel(true);
        }
        return result;
    }
    public static <T> T executeWithTimeout(Callable<T> task, int milliseconds) {
        if (SYNCHRONOUS) {
            try {
                return task.call();
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }
        ExecutorService executor = Executors.newCachedThreadPool();
        Future<T> future = executor.submit(task);
        T result;
        try {
            result = future.get(milliseconds, TimeUnit.MILLISECONDS); 
        }
        catch (Exception e) { //handle timeout and other exceptions
            e.printStackTrace();
            result = null;
        }
        finally {
           future.cancel(true);
        }
        return result;
    }
}