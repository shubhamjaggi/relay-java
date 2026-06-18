package io.github.shubhamjaggi.relay.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring-managed method as eligible for call coalescing.
 *
 * <p>When multiple threads call the same annotated method concurrently with arguments
 * that resolve to the <em>same key</em>, only one actual execution runs. All concurrent
 * callers block until that execution completes and then receive the same result.
 * Subsequent calls (after the in-flight execution finishes) start fresh — results
 * are not cached.
 *
 * <h2>The {@code key} attribute</h2>
 * <p>{@code key} is a <a href="https://docs.spring.io/spring-framework/docs/current/reference/html/core.html#expressions">
 * Spring Expression Language (SpEL)</a> expression evaluated against the method's
 * parameters. This is the same syntax used by {@code @Cacheable}.
 *
 * <p>Common patterns:
 * <ul>
 *   <li>{@code key = "#userId"} — single parameter</li>
 *   <li>{@code key = "#tenantId + ':' + #resourceId"} — composite key</li>
 *   <li>{@code key = "#request.userId"} — nested property access</li>
 *   <li>{@code key = "'global'"} — static key (all callers share one execution)</li>
 * </ul>
 *
 * <h2>Proxy requirement</h2>
 * <p>Because this annotation is backed by Spring AOP, it only takes effect when the
 * method is called through the Spring proxy — i.e., through the injected bean, not
 * via {@code this.method()} within the same class. This is the same constraint that
 * applies to {@code @Transactional} and {@code @Cacheable}.
 *
 * <h2>Method visibility</h2>
 * <p>The annotated method must be {@code public}. Spring AOP cannot intercept
 * {@code private} or {@code protected} methods.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @Service
 * public class ProductService {
 *
 *     // 500 concurrent requests for product "ABC-123" fire at most one DB call per concurrent wave.
 *     @Coalesce(key = "#productId")
 *     public Product fetchProduct(String productId) {
 *         return database.findProduct(productId);
 *     }
 *
 *     // Composite key: coalesces per tenant + resource combination.
 *     @Coalesce(key = "#tenantId + ':' + #resourceId")
 *     public Resource fetchResource(String tenantId, String resourceId) {
 *         return remoteApi.get(tenantId, resourceId);
 *     }
 * }
 * }</pre>
 *
 * <p>Spring Boot auto-configuration registers the backing AOP aspect automatically
 * when this library is on the classpath. No {@code @Enable*} annotation is needed.
 *
 * @see CoalesceAspect
 * @see io.github.shubhamjaggi.relay.RelayGroup
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Coalesce {

    /**
     * SpEL expression that resolves to the deduplication key.
     *
     * <p>Concurrent calls where this expression evaluates to the same string are
     * coalesced into a single execution. The expression is evaluated once per call
     * before the method is invoked.
     *
     * @return a non-null SpEL expression string
     */
    String key();
}
