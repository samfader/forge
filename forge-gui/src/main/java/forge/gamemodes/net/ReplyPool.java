package forge.gamemodes.net;

import com.google.common.collect.Maps;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ReplyPool {

    private final Map<Integer, CompletableFuture> pool = Maps.newHashMap();

    // Default timeout in minutes - increased from 5 to 10 minutes for better stability with 3+ players
    private static final int DEFAULT_TIMEOUT_MINUTES = 10;

    // Allow dynamic timeout adjustment based on player count
    private int timeoutMultiplier = 1;

    public ReplyPool() {
    }

    /**
     * Set a timeout multiplier to scale timeout based on number of players.
     * For example, with 3 players, set to 3 to allow 30 minutes timeout instead of 10.
     */
    public void setTimeoutMultiplier(final int multiplier) {
        this.timeoutMultiplier = Math.max(1, multiplier);
    }

    public void initialize(final int index) {
        synchronized (pool) {
            pool.put(index, new CompletableFuture());
        }
    }

    public void complete(final int index, final Object value) {
        synchronized (pool) {
            final CompletableFuture future = pool.get(index);
            if (future != null) {
                future.set(value);
                // Remove completed future to prevent memory leak
                pool.remove(index);
            } else {
                System.err.println("Attempted to complete non-existent reply " + index);
            }
        }
    }

    public Object get(final int index) throws TimeoutException {
        final CompletableFuture future;
        synchronized (pool) {
            future = pool.get(index);
            if (future == null) {
                throw new TimeoutException("Reply pool entry not found for index: " + index);
            }
        }
        try {
            final int timeoutMinutes = DEFAULT_TIMEOUT_MINUTES * timeoutMultiplier;
            return future.get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (final InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            // Clean up the entry after retrieval (successful or timeout)
            synchronized (pool) {
                pool.remove(index);
            }
        }
    }

    /**
     * Cancel all pending replies by completing them with null.
     * This is used when a player is converted to AI to unblock any waiting game threads.
     */
    public void cancelAll() {
        synchronized (pool) {
            for (CompletableFuture future : pool.values()) {
                // Complete with null to unblock waiting threads
                try {
                    future.set(null);
                } catch (Exception e) {
                    // Future may already be completed, that's fine
                }
            }
            pool.clear();
        }
    }

    private static final class CompletableFuture extends FutureTask<Object> {
        public CompletableFuture() {
            super(() -> null);
        }

        @Override
        public void set(final Object v) {
            super.set(v);
        }
    }
}


