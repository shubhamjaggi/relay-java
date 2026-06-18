# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - 2026-06-19

### Added
- `RelayGroup<V>` — core implementation with no external dependencies
  - `execute(String key, Callable<V> work)` — blocking, shares result across concurrent callers
  - `executeAsync(String key, Supplier<CompletableFuture<V>> work)` — non-blocking variant
  - `inFlightCount()` — observable state for monitoring
- `@Coalesce(key = "...")` — Spring annotation with SpEL key expressions (mirrors `@Cacheable` syntax)
- `CoalesceAspect` — AOP-backed, keys namespaced by method signature to prevent cross-method collisions
- `RelayAutoConfiguration` — Spring Boot 3 autoconfiguration via zero-config dependency addition
- Error propagation: all concurrent callers for a failing key receive the same exception; at most one backend call fires per concurrent wave
- Concurrent correctness tests using `CountDownLatch` — proves coalescing under real thread contention
- GitHub Actions CI with test report artifact upload

[Unreleased]: https://github.com/shubhamjaggi/relay-java/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/shubhamjaggi/relay-java/releases/tag/v0.1.0
