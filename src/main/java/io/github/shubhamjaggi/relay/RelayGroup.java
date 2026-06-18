package io.github.shubhamjaggi.relay;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Suppresses duplicate concurrent calls for the same key, ensuring only one execution
 * runs per key at any given time while all concurrent callers share its result.
 *
 * <h2>The problem</h2>
 * <p>Under load, a cache miss for a popular key can trigger hundreds of concurrent
 * requests to the same expensive downstream call — a database query, an HTTP fetch,
 * a file read. Each request finds the cache empty, starts its own backend call, and
 * all of them return the same bytes. This is called a <em>thundering herd</em>.
 *
 * <h2>How this class solves it</h2>
 * <p>When {@link #execute} is called for a key that already has an in-flight execution,
 * the caller does <em>not</em> start a new one. Instead it blocks on the existing
 * {@link CompletableFuture} and receives the same result when the single execution
 * completes. The first caller to arrive becomes the <em>leader</em>; all subsequent
 * concurrent callers are <em>followers</em>.
 *
 * <p>The deduplication window is exactly the duration of the in-flight call. As soon
 * as it completes — successfully or with an error — the key is removed from the map.
 * The next caller after that starts a new execution from scratch.
 *
 * <h2>Thread safety</h2>
 * <p>This class is fully thread-safe. The implementation relies on
 * {@link ConcurrentHashMap#putIfAbsent} as its atomic primitive: only one thread
 * can win the race to insert a future for a given key, making it the leader. All
 * other threads find the existing future and become followers. No {@code synchronized}
 * blocks or explicit locks are used.
 *
 * <h2>Usage</h2>
 * <p>Create one instance per resource type and reuse it — typically as a field or
 * Spring bean. The same group can deduplicate calls for any number of distinct keys.
 *
 * <pre>{@code
 * public class UserService {
 *     private final RelayGroup<UserProfile> relay = new RelayGroup<>();
 *
 *     public UserProfile fetchProfile(String userId) throws Exception {
 *         // Only one DB call fires even if 100 threads call this concurrently
 *         // with the same userId.
 *         return relay.execute(userId, () -> database.findUser(userId));
 *     }
 * }
 * }</pre>
 *
 * <p>For Spring Boot applications, prefer the {@code @Coalesce} annotation in the
 * {@code io.github.shubhamjaggi.relay.spring} package — it wires this class
 * up automatically via AOP with no boilerplate.
 *
 * @param <V> the type of value produced by the deduplicated work
 * @see io.github.shubhamjaggi.relay.spring.Coalesce
 */
public class RelayGroup<V> {

    /**
     * Holds the in-flight future for each key.
     *
     * <p>{@code putIfAbsent} is the core atomic operation: the first thread to call
     * it for a key gets {@code null} back (becoming the leader) while all subsequent
     * threads get the already-inserted future back (becoming followers).
     */
    private final ConcurrentHashMap<String, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    /**
     * Executes {@code work} for the given key, or blocks until an already-running
     * execution for the same key completes and returns its result.
     *
     * <p><strong>Leader:</strong> If no execution is in flight for {@code key}, this
     * caller becomes the leader. It calls {@code work}, completes the shared future
     * with the result (or exception), removes the key from the map, and returns.
     *
     * <p><strong>Follower:</strong> If an execution is already in flight, this caller
     * blocks on the shared {@link CompletableFuture#get()} until the leader finishes,
     * then returns the same result. The follower's {@code work} supplier is never called.
     *
     * <p><strong>After completion:</strong> The key is removed atomically in a
     * {@code finally} block. The next call for the same key after completion starts
     * a brand new execution — results are <em>not</em> cached.
     *
     * <p><strong>Error propagation:</strong> If the leader throws, all followers receive
     * the same exception (unwrapped from {@link ExecutionException}).
     *
     * @param key  the deduplication key; concurrent calls with the same key share one execution
     * @param work the computation to run; called at most once per concurrent wave
     * @return the result produced by {@code work}
     * @throws Exception the exception thrown by {@code work}, propagated to all callers
     * @throws RuntimeException if this thread is interrupted while waiting as a follower
     */
    public V execute(String key, Callable<V> work) throws Exception {
        CompletableFuture<V> leader = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, leader);

        if (existing != null) {
            // Follower path: another caller is already running the work — join it.
            try {
                return existing.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for in-flight call", e);
            }
        }

        // Leader path: we won the race — run the work and notify all followers.
        try {
            V result = work.call();
            leader.complete(result);
            return result;
        } catch (Exception e) {
            leader.completeExceptionally(e);
            throw e;
        } finally {
            // Always remove the key so the next wave can start fresh.
            inFlight.remove(key, leader);
        }
    }

    /**
     * Non-blocking variant of {@link #execute}. Returns a {@link CompletableFuture}
     * that resolves when the work for {@code key} finishes, whether or not this
     * caller started it.
     *
     * <p>All concurrent callers for the same key receive a reference to the
     * <em>same</em> {@code CompletableFuture} — not independent copies. No thread
     * is blocked; callers chain their logic with {@link CompletableFuture#thenApply}
     * or {@link CompletableFuture#whenComplete}.
     *
     * <p>The {@code work} supplier is called at most once per concurrent wave. It must
     * return a non-null {@code CompletableFuture}. If the supplier itself throws
     * synchronously, the returned future is completed exceptionally.
     *
     * <pre>{@code
     * CompletableFuture<UserProfile> future = relay.executeAsync(userId,
     *         () -> httpClient.getAsync("/users/" + userId));
     *
     * future.thenAccept(profile -> cache.put(userId, profile));
     * }</pre>
     *
     * @param key  the deduplication key
     * @param work supplier of the async computation; called at most once per concurrent wave
     * @return a future shared by all concurrent callers for this key
     */
    public CompletableFuture<V> executeAsync(String key, Supplier<CompletableFuture<V>> work) {
        CompletableFuture<V> leader = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, leader);

        if (existing != null) {
            // Follower path: return the same future the leader is driving.
            return existing;
        }

        // Leader path: start the async work and wire its completion into our future.
        try {
            work.get().whenComplete((result, ex) -> {
                inFlight.remove(key, leader);
                if (ex != null) leader.completeExceptionally(ex);
                else leader.complete(result);
            });
        } catch (Exception e) {
            // work.get() threw synchronously — clean up and fail the future.
            inFlight.remove(key, leader);
            leader.completeExceptionally(e);
        }

        return leader;
    }

    /**
     * Returns the number of keys with in-flight executions at this instant.
     *
     * <p>This is a point-in-time snapshot and may be stale by the time it is read.
     * Useful for monitoring and testing; not suitable for coordination logic.
     *
     * @return the number of currently in-flight calls
     */
    public int inFlightCount() {
        return inFlight.size();
    }
}
