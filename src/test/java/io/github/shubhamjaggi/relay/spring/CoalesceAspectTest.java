package io.github.shubhamjaggi.relay.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link CoalesceAspect} verifying that the {@link Coalesce}
 * annotation correctly coalesces concurrent calls through Spring AOP.
 *
 * <h2>Test context</h2>
 * <p>A minimal Spring context is assembled with {@link SpringExtension} —
 * just the {@link CoalesceAspect} bean and the test service beans. This avoids
 * loading full Spring Boot autoconfiguration while still exercising the real
 * proxy-based AOP interception that production code goes through.
 *
 * <h2>What these tests cover that unit tests of RelayGroup do not</h2>
 * <ul>
 *   <li>SpEL key evaluation ({@code #userId} resolved from actual method arguments)</li>
 *   <li>Composite SpEL keys ({@code #tenantId + ':' + #resourceId})</li>
 *   <li>Key namespacing — the same SpEL value for two different methods must not collide</li>
 *   <li>Exception type preservation — original exception type must reach the caller, not a wrapper</li>
 *   <li>Checked exception propagation — not just {@link RuntimeException}</li>
 *   <li>Coalescing triggered by the annotation, not by direct API calls</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        CoalesceAspectTest.TestConfig.class,
        CoalesceAspectTest.ProfileService.class,
        CoalesceAspectTest.CompositeKeyService.class
})
class CoalesceAspectTest {

    /**
     * Minimal Spring configuration: enable AspectJ auto-proxy (so Spring wraps beans
     * with AOP proxies) and register the aspect as a bean.
     */
    @Configuration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        CoalesceAspect coalesceAspect() {
            return new CoalesceAspect();
        }
    }

    /**
     * A checked exception used to verify that {@link Coalesce} preserves the original
     * exception type through the {@link CoalesceAspect}'s internal {@code WrappedThrowable} bridge.
     */
    static class DataNotFoundException extends Exception {
        DataNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Primary service for single-key and concurrent coalescing tests.
     *
     * <ul>
     *   <li>{@code getProfile} — fast, for sequential and key-isolation tests</li>
     *   <li>{@code getSlowProfile} — sleeps 80 ms to hold the in-flight window open</li>
     *   <li>{@code getProfileAndFail} — always throws {@link RuntimeException}</li>
     *   <li>{@code findOrFail} — throws a checked {@link DataNotFoundException}</li>
     * </ul>
     */
    @Service
    static class ProfileService {
        final AtomicInteger callCount = new AtomicInteger();

        @Coalesce(key = "#userId")
        public String getProfile(String userId) {
            callCount.incrementAndGet();
            return "profile-" + userId;
        }

        @Coalesce(key = "#userId")
        public String getSlowProfile(String userId) throws InterruptedException {
            callCount.incrementAndGet();
            Thread.sleep(80);
            return "slow-profile-" + userId;
        }

        @Coalesce(key = "#userId")
        public String getProfileAndFail(String userId) {
            throw new RuntimeException("downstream error");
        }

        @Coalesce(key = "#id")
        public String findOrFail(String id) throws DataNotFoundException {
            throw new DataNotFoundException("not found: " + id);
        }
    }

    /**
     * Service for composite-key tests, verifying that SpEL expressions with
     * multiple parameters (e.g. {@code #tenantId + ':' + #resourceId}) are
     * evaluated correctly and produce distinct deduplication keys.
     */
    @Service
    static class CompositeKeyService {
        final AtomicInteger callCount = new AtomicInteger();

        @Coalesce(key = "#tenantId + ':' + #resourceId")
        public String fetchResource(String tenantId, String resourceId) throws InterruptedException {
            callCount.incrementAndGet();
            Thread.sleep(80);
            return tenantId + "/" + resourceId;
        }
    }

    @Autowired ProfileService profileService;
    @Autowired CompositeKeyService compositeKeyService;

    @BeforeEach
    void resetCounters() {
        profileService.callCount.set(0);
        compositeKeyService.callCount.set(0);
    }

    // ── Basic correctness ──────────────────────────────────────────────────────

    @Test
    void singleCall_returnsCorrectResult() throws Exception {
        assertEquals("profile-42", profileService.getProfile("42"));
    }

    @Test
    void differentKeys_eachExecuteOnce() throws Exception {
        profileService.getProfile("A");
        profileService.getProfile("B");
        assertEquals(2, profileService.callCount.get());
    }

    @Test
    void sequentialCalls_sameKey_eachExecute() throws Exception {
        // No in-flight overlap — each call completes before the next starts.
        profileService.getProfile("X");
        profileService.getProfile("X");
        assertEquals(2, profileService.callCount.get());
    }

    // ── Concurrent coalescing ──────────────────────────────────────────────────

    /**
     * The core annotation-level coalescing test: 30 threads all call {@code getSlowProfile}
     * with the same userId simultaneously. Only one method body should execute;
     * all 30 callers should receive the correct result.
     *
     * <p>See {@link io.github.shubhamjaggi.relay.RelayGroupTest} for
     * the full explanation of the CountDownLatch barrier pattern and the ≤3 bound.
     */
    @Test
    void concurrentCalls_sameKey_onlyOneExecutes() throws InterruptedException {
        int threads = 30;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(profileService.getSlowProfile("user-1"));
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(threads, results.size(), "All callers should get a result");
        assertTrue(results.stream().allMatch(r -> r.equals("slow-profile-user-1")),
                "All callers should receive the same result");
        assertTrue(profileService.callCount.get() <= 3,
                "Expected ≤3 actual method executions for " + threads + " concurrent callers, got: "
                        + profileService.callCount.get());
    }

    // ── Composite SpEL keys ────────────────────────────────────────────────────

    /**
     * Verifies that a composite SpEL key — one that combines multiple method
     * parameters — is evaluated correctly and coalesces calls with the same
     * tenant+resource pair.
     */
    @Test
    void compositeKey_concurrentCallsSamePair_onlyOneExecutes() throws InterruptedException {
        int threads = 20;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(compositeKeyService.fetchResource("acme", "report-1"));
                } catch (Exception e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));

        assertEquals(threads, results.size());
        assertTrue(results.stream().allMatch(r -> r.equals("acme/report-1")));
        assertTrue(compositeKeyService.callCount.get() <= 3,
                "Expected ≤3 executions for " + threads + " concurrent callers on composite key");
    }

    @Test
    void compositeKey_differentPairs_runIndependently() throws Exception {
        // acme:report-1 and acme:report-2 differ in resourceId — they must not coalesce.
        compositeKeyService.fetchResource("acme", "report-1");
        compositeKeyService.fetchResource("acme", "report-2");
        assertEquals(2, compositeKeyService.callCount.get());
    }

    @Test
    void compositeKey_differentTenants_runIndependently() throws Exception {
        // acme:report-1 and beta:report-1 differ in tenantId — they must not coalesce.
        compositeKeyService.fetchResource("acme", "report-1");
        compositeKeyService.fetchResource("beta", "report-1");
        assertEquals(2, compositeKeyService.callCount.get());
    }

    // ── Exception propagation ──────────────────────────────────────────────────

    @Test
    void runtimeException_propagatesToCaller_withOriginalMessage() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> profileService.getProfileAndFail("bad-user"));
        assertEquals("downstream error", ex.getMessage());
    }

    /**
     * Verifies that a checked exception thrown by an annotated method is preserved —
     * the caller receives the original exception type, not a {@link RuntimeException}
     * wrapper from the {@link CoalesceAspect}'s internal {@code WrappedThrowable} bridge.
     */
    @Test
    void checkedException_typeAndMessage_arePreservedThroughCoalescing() {
        DataNotFoundException ex = assertThrows(DataNotFoundException.class,
                () -> profileService.findOrFail("missing-id"));
        assertEquals("not found: missing-id", ex.getMessage());
    }

    // ── Key namespacing ────────────────────────────────────────────────────────

    /**
     * Verifies that the composite key includes the method signature, preventing
     * coalescing across different methods even when they share the same SpEL
     * expression and receive the same argument value.
     *
     * <p>Both {@code getProfile} and {@code getSlowProfile} use {@code key = "#userId"}.
     * Called sequentially with the same argument, they must each execute independently.
     */
    @Test
    void coalesceKey_namespacedByMethod_noCollisionAcrossDistinctMethods() throws Exception {
        profileService.getProfile("shared-key");
        assertEquals(1, profileService.callCount.get());

        // getSlowProfile uses the same SpEL expression and same argument value,
        // but is a different method — it must not be suppressed.
        profileService.callCount.set(0);
        profileService.getSlowProfile("shared-key");
        assertEquals(1, profileService.callCount.get(),
                "getSlowProfile should execute independently from getProfile");
    }
}
