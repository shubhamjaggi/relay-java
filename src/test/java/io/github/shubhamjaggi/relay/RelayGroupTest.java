package io.github.shubhamjaggi.relay;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RelayGroup} covering both the synchronous {@code execute}
 * and the asynchronous {@code executeAsync} APIs.
 *
 * <h2>Concurrent test strategy</h2>
 * <p>The core correctness property — "only one execution per concurrent wave" — cannot
 * be proven by sequential tests. The concurrent tests use two {@link CountDownLatch}es
 * to create a controlled race condition:
 * <ol>
 *   <li>All threads signal {@code ready} and then block on {@code start}.</li>
 *   <li>The main thread releases {@code start}, causing all threads to call
 *       {@link RelayGroup#execute} as simultaneously as the JVM scheduler allows.</li>
 *   <li>The work sleeps for 80 ms, keeping the in-flight window open long enough
 *       for all threads to arrive and become followers before the leader finishes.</li>
 * </ol>
 *
 * <p>The assertion uses {@code ≤3} rather than exactly {@code 1} to tolerate the
 * unlikely but possible case where the first execution finishes and a second wave
 * begins before all threads have arrived. This makes the test deterministic on
 * slow CI machines without being so loose that it fails to catch regressions.
 */
class RelayGroupTest {

    @Test
    void execute_singleCaller_returnsExpectedValue() throws Exception {
        RelayGroup<String> group = new RelayGroup<>();
        assertEquals("hello", group.execute("k", () -> "hello"));
    }

    @Test
    void execute_sequentialCalls_runEachTime() throws Exception {
        // After one call completes, the key is removed from the map.
        // The next call must start a fresh execution — not reuse the previous result.
        RelayGroup<Integer> group = new RelayGroup<>();
        AtomicInteger counter = new AtomicInteger();

        group.execute("k", counter::incrementAndGet);
        group.execute("k", counter::incrementAndGet);

        assertEquals(2, counter.get(), "Sequential calls should each execute independently");
    }

    @Test
    void execute_differentKeys_runIndependently() throws Exception {
        // Keys are independent — no coalescing happens across different keys.
        RelayGroup<String> group = new RelayGroup<>();
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();

        group.execute("a", () -> { aCount.incrementAndGet(); return "a"; });
        group.execute("b", () -> { bCount.incrementAndGet(); return "b"; });

        assertEquals(1, aCount.get());
        assertEquals(1, bCount.get());
    }

    /**
     * Verifies the core contract: 50 threads calling {@code execute} simultaneously
     * for the same key should produce at most a handful of actual work executions.
     *
     * <p>The work sleeps 80 ms so that all 50 threads arrive and park on the shared
     * future before the leader returns. See the class-level Javadoc for the full
     * explanation of the ≤3 assertion bound.
     */
    @Test
    void execute_concurrentCallers_sameKeySuppressesDuplicates() throws InterruptedException {
        RelayGroup<String> group = new RelayGroup<>();
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(50);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(50);

        Callable<String> slowWork = () -> {
            executions.incrementAndGet();
            Thread.sleep(80);
            return "result";
        };

        for (int i = 0; i < 50; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    group.execute("key", slowWork);
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        ready.await();   // wait until all threads are parked on start
        start.countDown(); // release the herd simultaneously
        assertTrue(done.await(10, TimeUnit.SECONDS), "All threads should finish within 10s");

        assertTrue(executions.get() <= 3,
                "Expected ≤3 actual executions out of 50 concurrent callers, got: " + executions.get());
    }

    /**
     * Verifies that when the leader throws, all followers receive the same exception.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Leader thread starts work and signals {@code leaderStarted}.</li>
     *   <li>Follower thread arrives, finds the leader's future in the map, and blocks.</li>
     *   <li>Main thread releases {@code followerReady}, unblocking the leader to throw.</li>
     *   <li>Both threads should catch an exception tracing back to the original {@code boom}.</li>
     * </ol>
     */
    @Test
    void execute_leaderFails_followersReceiveSameException() throws InterruptedException {
        RelayGroup<String> group = new RelayGroup<>();
        RuntimeException boom = new RuntimeException("boom");

        CountDownLatch leaderStarted = new CountDownLatch(1);
        List<Throwable> caught = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(2);

        Thread leader = new Thread(() -> {
            try {
                group.execute("k", () -> {
                    leaderStarted.countDown();
                    Thread.sleep(50); // hold the key in-flight so follower has time to join
                    throw boom;
                });
            } catch (Exception e) {
                caught.add(e);
            } finally {
                done.countDown();
            }
        });

        Thread follower = new Thread(() -> {
            try {
                leaderStarted.await(); // wait until leader is in-flight, then join
                group.execute("k", () -> { throw new AssertionError("follower should never run work"); });
            } catch (Exception e) {
                caught.add(e);
            } finally {
                done.countDown();
            }
        });

        leader.start();
        follower.start();
        assertTrue(done.await(5, TimeUnit.SECONDS));

        assertEquals(2, caught.size(), "Both leader and follower should have caught an exception");
        assertTrue(caught.stream().allMatch(e -> e == boom || e.getCause() == boom),
                "Both exceptions should trace back to the original boom");
    }

    @Test
    void execute_afterFailure_nextCallStartsFresh() throws Exception {
        // After the leader fails, the key is removed. The next call is not tainted
        // by the previous failure — it starts a brand new execution.
        RelayGroup<String> group = new RelayGroup<>();
        try {
            group.execute("k", () -> { throw new RuntimeException("first call fails"); });
        } catch (Exception ignored) {}

        AtomicInteger callCount = new AtomicInteger();
        group.execute("k", () -> { callCount.incrementAndGet(); return "ok"; });

        assertEquals(1, callCount.get(), "A fresh call after a failure should execute normally");
    }

    @Test
    void inFlightCount_isZeroWhenIdle() {
        RelayGroup<String> group = new RelayGroup<>();
        assertEquals(0, group.inFlightCount());
    }

    /**
     * Verifies that {@link RelayGroup#inFlightCount()} reflects work in progress.
     *
     * <p>The work is pinned open with a latch so we can observe the count mid-flight,
     * then released so we can verify it drops back to zero after completion.
     */
    @Test
    void inFlightCount_tracksActiveWork() throws InterruptedException {
        RelayGroup<String> group = new RelayGroup<>();
        CountDownLatch workBlocked = new CountDownLatch(1);
        CountDownLatch workStarted = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            try {
                group.execute("k", () -> {
                    workStarted.countDown();
                    workBlocked.await();
                    return "done";
                });
            } catch (Exception e) {
                Thread.currentThread().interrupt();
            }
        });
        t.setDaemon(true);
        t.start();

        workStarted.await();
        assertEquals(1, group.inFlightCount(), "One key should be in-flight while work is blocked");

        workBlocked.countDown();
        t.join(2000);
        assertEquals(0, group.inFlightCount(), "Count should return to zero after completion");
    }

    /**
     * Same coalescing guarantee for the non-blocking {@code executeAsync} path.
     *
     * <p>All 20 calls are issued before any future resolves (no {@code await} between
     * submissions), so they all race against the same in-flight future. The test then
     * waits for all of them and verifies both the suppression count and the results.
     */
    @Test
    void executeAsync_concurrentCallers_sameKeySuppressesDuplicates() throws Exception {
        RelayGroup<String> group = new RelayGroup<>();
        AtomicInteger executions = new AtomicInteger();
        List<CompletableFuture<String>> futures = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            futures.add(group.executeAsync("key", () -> CompletableFuture.supplyAsync(() -> {
                executions.incrementAndGet();
                try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "result";
            })));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);

        assertTrue(executions.get() <= 3,
                "Expected ≤3 actual executions for 20 async callers, got: " + executions.get());
        assertTrue(futures.stream().allMatch(f -> {
            try { return "result".equals(f.get()); } catch (Exception e) { return false; }
        }), "All futures should resolve to 'result'");
    }

    @Test
    void executeAsync_sequentialCalls_runEachTime() throws Exception {
        // Same as the synchronous case: no in-flight overlap → no coalescing.
        RelayGroup<String> group = new RelayGroup<>();
        AtomicInteger callCount = new AtomicInteger();

        group.executeAsync("k", () -> CompletableFuture.completedFuture(String.valueOf(callCount.incrementAndGet())))
             .get(1, TimeUnit.SECONDS);
        group.executeAsync("k", () -> CompletableFuture.completedFuture(String.valueOf(callCount.incrementAndGet())))
             .get(1, TimeUnit.SECONDS);

        assertEquals(2, callCount.get(), "Sequential async calls should each execute independently");
    }

    @Test
    void executeAsync_differentKeys_runIndependently() throws Exception {
        RelayGroup<String> group = new RelayGroup<>();
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();

        group.executeAsync("a", () -> CompletableFuture.completedFuture(String.valueOf(aCount.incrementAndGet())))
             .get(1, TimeUnit.SECONDS);
        group.executeAsync("b", () -> CompletableFuture.completedFuture(String.valueOf(bCount.incrementAndGet())))
             .get(1, TimeUnit.SECONDS);

        assertEquals(1, aCount.get());
        assertEquals(1, bCount.get());
    }

    /**
     * Verifies that when the leader's async work fails, all followers' futures also
     * complete exceptionally with the same cause.
     *
     * <p>Sequence:
     * <ol>
     *   <li>Leader's future is placed in the map; its async work starts.</li>
     *   <li>Follower calls {@code executeAsync} and receives the same future.</li>
     *   <li>Async work is released to fail — both futures (same object) resolve exceptionally.</li>
     * </ol>
     */
    @Test
    void executeAsync_leaderFails_followersReceiveException() throws InterruptedException {
        RelayGroup<String> group = new RelayGroup<>();
        CountDownLatch leaderStarted = new CountDownLatch(1);
        CountDownLatch allowFailure = new CountDownLatch(1);

        CompletableFuture<String> leaderFuture = group.executeAsync("k", () ->
                CompletableFuture.supplyAsync(() -> {
                    leaderStarted.countDown();
                    try { allowFailure.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    throw new RuntimeException("async-fail");
                })
        );

        leaderStarted.await();

        // Follower joins the in-flight future; its work supplier is never called.
        CompletableFuture<String> followerFuture = group.executeAsync("k", () -> {
            throw new AssertionError("follower supplier should not be called");
        });

        allowFailure.countDown();

        ExecutionException leaderEx = assertThrows(ExecutionException.class,
                () -> leaderFuture.get(5, TimeUnit.SECONDS));
        ExecutionException followerEx = assertThrows(ExecutionException.class,
                () -> followerFuture.get(5, TimeUnit.SECONDS));

        assertEquals("async-fail", leaderEx.getCause().getMessage());
        assertEquals("async-fail", followerEx.getCause().getMessage());
    }

    @Test
    void executeAsync_supplierThrowsSynchronously_futureCompletesExceptionally() throws Exception {
        // If the work supplier itself throws (before returning a CompletableFuture),
        // the returned future must complete exceptionally rather than leaving callers hanging.
        RelayGroup<String> group = new RelayGroup<>();

        CompletableFuture<String> future = group.executeAsync("k", () -> {
            throw new RuntimeException("supplier-boom");
        });

        assertTrue(future.isCompletedExceptionally(), "Future must be completed exceptionally");
        ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertEquals("supplier-boom", ex.getCause().getMessage());
    }

    @Test
    void execute_nullReturnValue_isAllowed() throws Exception {
        // Null is a valid result — some caches and lookups legitimately return null
        // to indicate "not found". The group must not treat null as a missing entry.
        RelayGroup<String> group = new RelayGroup<>();
        assertNull(group.execute("k", () -> null));
    }
}
