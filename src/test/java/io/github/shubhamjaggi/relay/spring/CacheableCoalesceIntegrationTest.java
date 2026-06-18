package io.github.shubhamjaggi.relay.spring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying that {@link Coalesce} and Spring's {@link Cacheable}
 * work correctly together on the same method.
 *
 * <p>The two annotations solve different problems:
 * <ul>
 *   <li>{@code @Cacheable} stores the result and returns it on subsequent calls without
 *       re-executing the method body.</li>
 *   <li>{@code @Coalesce} deduplicates concurrent in-flight calls so that at most one
 *       execution runs per concurrent wave.</li>
 * </ul>
 *
 * <p>Used together, they provide full coverage: the thundering herd at a cold cache miss
 * is suppressed by {@code @Coalesce}, and all subsequent calls are served directly from
 * the cache by {@code @Cacheable} without reaching the method body at all.
 *
 * <p>This test uses a separate Spring context from {@link CoalesceAspectTest} because
 * it requires {@code @EnableCaching} and a {@link CacheManager} bean, which would change
 * the semantics of the other tests.
 *
 * <p>Counter access note: Spring injects CGLIB proxies for {@code @Service} beans.
 * Accessing a field directly on the proxy reaches the proxy's uninitialized field rather
 * than the real target's counter. Counter state is therefore exposed via
 * {@code getCallCount()} and {@code resetCallCount()} methods, which the proxy delegates
 * to the real target.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        CacheableCoalesceIntegrationTest.TestConfig.class,
        CacheableCoalesceIntegrationTest.CachedProfileService.class
})
class CacheableCoalesceIntegrationTest {

    @Configuration
    @EnableAspectJAutoProxy
    @EnableCaching
    static class TestConfig {
        @Bean
        CoalesceAspect coalesceAspect() {
            return new CoalesceAspect();
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("profiles");
        }
    }

    /**
     * A service that combines {@link Cacheable} and {@link Coalesce} on the same method.
     *
     * <p>On a cold cache miss, the request falls through {@code @Cacheable} to
     * {@code @Coalesce}, which ensures at most one actual method invocation runs per
     * concurrent wave. The result is stored in the cache on the way back out.
     *
     * <p>On a warm cache hit, {@code @Cacheable} returns the cached result immediately —
     * the method body is never reached.
     */
    @Service
    static class CachedProfileService {
        private final AtomicInteger callCount = new AtomicInteger();

        public int getCallCount() { return callCount.get(); }
        public void resetCallCount() { callCount.set(0); }

        @Cacheable("profiles")
        @Coalesce(key = "#userId")
        public String fetchProfile(String userId) throws InterruptedException {
            callCount.incrementAndGet();
            Thread.sleep(80);
            return "profile-" + userId;
        }
    }

    @Autowired CachedProfileService service;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void reset() {
        service.resetCallCount();
        cacheManager.getCache("profiles").clear();
    }

    /**
     * On a cold cache miss, many concurrent callers should be coalesced into at most a
     * few actual method executions. All callers should receive the same correct result,
     * and the result should be stored in the cache.
     *
     * <p>See {@link io.github.shubhamjaggi.relay.RelayGroupTest} for the full
     * explanation of the CountDownLatch barrier pattern and the ≤3 bound.
     */
    @Test
    void coldCacheMiss_concurrentCallers_coalesceToFewExecutions() throws InterruptedException {
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
                    results.add(service.fetchProfile("user-1"));
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

        assertEquals(threads, results.size(), "All callers should receive a result");
        assertTrue(results.stream().allMatch(r -> r.equals("profile-user-1")),
                "All callers should receive the same correct result");
        assertTrue(service.getCallCount() <= 3,
                "Expected ≤3 actual method executions during cold cache miss for "
                        + threads + " concurrent callers, got: " + service.getCallCount());
    }

    /**
     * After the first successful call populates the cache, subsequent calls for the
     * same key must be served from the cache. The method body must not execute again.
     */
    @Test
    void warmCacheHit_subsequentCall_doesNotExecuteMethodBody() throws Exception {
        service.fetchProfile("user-1");
        int countAfterFirstCall = service.getCallCount();

        service.fetchProfile("user-1");

        assertEquals(countAfterFirstCall, service.getCallCount(),
                "A warm cache hit must not invoke the method body");
    }

    /**
     * A cache entry for one key does not affect other keys. A different key that is not
     * yet in the cache must still execute the method body.
     */
    @Test
    void warmCacheHit_differentKey_stillExecutesMethodBody() throws Exception {
        service.fetchProfile("user-1");
        int countAfterFirst = service.getCallCount();

        service.fetchProfile("user-2");

        assertTrue(service.getCallCount() > countAfterFirst,
                "A different key not present in the cache must execute the method body");
    }
}
