package io.github.shubhamjaggi.relay.spring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Spring Boot auto-configuration for relay-java.
 *
 * <p>This class is discovered automatically by Spring Boot's autoconfiguration
 * mechanism when the library is on the classpath — no {@code @Import} or
 * {@code @Enable*} annotation is needed in your application code. The entry point
 * is {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 *
 * <h2>What this registers</h2>
 * <ul>
 *   <li>{@link CoalesceAspect} — the AOP aspect that intercepts {@link Coalesce}-annotated
 *       methods and routes them through a {@link io.github.shubhamjaggi.relay.RelayGroup}</li>
 *   <li>{@code @EnableAspectJAutoProxy} — enables Spring's AspectJ proxy infrastructure
 *       so that the aspect is applied to matching beans</li>
 * </ul>
 *
 * <h2>Customisation</h2>
 * <p>If you declare your own {@link CoalesceAspect} bean in your application context,
 * this auto-configuration backs off via {@link ConditionalOnMissingBean} and your
 * bean is used instead. This lets you supply a custom implementation or configuration
 * without fighting the auto-wiring.
 *
 * @see Coalesce
 * @see CoalesceAspect
 */
@AutoConfiguration
@EnableAspectJAutoProxy
public class RelayAutoConfiguration {

    /**
     * Creates the {@link CoalesceAspect} bean that powers the {@link Coalesce} annotation.
     *
     * <p>Conditional on no existing {@link CoalesceAspect} bean — if your application
     * defines its own, this one is skipped.
     *
     * @return a new {@link CoalesceAspect} instance
     */
    @Bean
    @ConditionalOnMissingBean
    public CoalesceAspect coalesceAspect() {
        return new CoalesceAspect();
    }
}
