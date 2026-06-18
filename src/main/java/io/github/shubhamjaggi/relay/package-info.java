/**
 * Core relay implementation with no external dependencies.
 *
 * <p>The central class is {@link io.github.shubhamjaggi.relay.RelayGroup},
 * which suppresses duplicate concurrent calls for the same key. It can be used
 * standalone in any Java application:
 *
 * <pre>{@code
 * RelayGroup<Product> relay = new RelayGroup<>();
 *
 * // Called from 200 concurrent threads — only 1 DB query fires.
 * Product p = relay.execute(productId, () -> database.find(productId));
 * }</pre>
 *
 * <p>For Spring Boot applications, see the
 * {@link io.github.shubhamjaggi.relay.spring} package, which provides
 * the {@code @Coalesce} annotation as a zero-boilerplate alternative.
 */
package io.github.shubhamjaggi.relay;
