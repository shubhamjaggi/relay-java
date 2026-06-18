package io.github.shubhamjaggi.relay.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link RelayAutoConfiguration} using Spring Boot's
 * {@link ApplicationContextRunner}, which assembles a real (but lightweight)
 * application context without starting a server.
 *
 * <p>These tests verify two things:
 * <ol>
 *   <li>The autoconfiguration registers a {@link CoalesceAspect} bean when none exists.</li>
 *   <li>The {@link org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean}
 *       condition causes the autoconfiguration to back off when the application provides
 *       its own {@link CoalesceAspect} — ensuring users can override without conflict.</li>
 * </ol>
 */
class RelayAutoConfigurationTest {

    /**
     * Bootstraps a context with {@link RelayAutoConfiguration} applied,
     * simulating what happens when this library is on a Spring Boot application's classpath.
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RelayAutoConfiguration.class));

    @Test
    void autoConfiguration_registersCoalesceAspect() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(CoalesceAspect.class)
        );
    }

    @Test
    void autoConfiguration_aspectBeanIsNamedCoalesceAspect() {
        contextRunner.run(context ->
                assertThat(context).hasBean("coalesceAspect")
        );
    }

    /**
     * Verifies that {@code @ConditionalOnMissingBean} causes the autoconfiguration
     * to back off when the application declares its own {@link CoalesceAspect} bean.
     *
     * <p>The context must still have exactly one {@link CoalesceAspect} — the user's
     * custom one — and must not register a second one from autoconfiguration.
     */
    @Test
    void autoConfiguration_backsOffWhenUserProvidesCoalesceAspect() {
        contextRunner
                .withUserConfiguration(CustomAspectConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CoalesceAspect.class);
                    assertThat(context).hasBean("customCoalesceAspect");
                    assertThat(context).doesNotHaveBean("coalesceAspect");
                });
    }

    /**
     * Simulates a user-provided {@link CoalesceAspect} bean — the scenario where
     * someone wants to supply a custom subclass or manually configured instance.
     */
    @Configuration
    static class CustomAspectConfig {
        @Bean
        CoalesceAspect customCoalesceAspect() {
            return new CoalesceAspect();
        }
    }
}
