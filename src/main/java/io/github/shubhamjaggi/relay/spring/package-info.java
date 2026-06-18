/**
 * Spring Boot integration for relay-java.
 *
 * <p>This package provides the {@link io.github.shubhamjaggi.relay.spring.Coalesce}
 * annotation, which brings call coalescing to Spring-managed beans via AOP — no
 * boilerplate required:
 *
 * <pre>{@code
 * @Service
 * public class UserService {
 *
 *     @Coalesce(key = "#userId")
 *     public UserProfile fetchProfile(String userId) {
 *         return remoteApi.get(userId);   // called once per concurrent wave, regardless of caller count
 *     }
 * }
 * }</pre>
 *
 * <h2>How it works</h2>
 * <p>{@link io.github.shubhamjaggi.relay.spring.CoalesceAspect} intercepts
 * calls to annotated methods. It evaluates the SpEL {@code key} expression,
 * prepends the method signature to namespace it, and delegates to a shared
 * {@link io.github.shubhamjaggi.relay.RelayGroup}.
 *
 * <h2>Auto-configuration</h2>
 * <p>{@link io.github.shubhamjaggi.relay.spring.RelayAutoConfiguration}
 * is discovered automatically when this library is on the Spring Boot classpath.
 * No {@code @Import} or {@code @Enable*} annotation is needed.
 */
package io.github.shubhamjaggi.relay.spring;
