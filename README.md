# relay-java

[![CI](https://github.com/shubhamjaggi/relay-java/actions/workflows/ci.yml/badge.svg)](https://github.com/shubhamjaggi/relay-java/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/shubhamjaggi/relay-java/branch/main/graph/badge.svg)](https://codecov.io/gh/shubhamjaggi/relay-java)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

Suppress duplicate concurrent calls for the same key. When many callers request the same resource simultaneously, only **one** execution runs — all callers share the result.

Inspired by Go's [`sync/singleflight`](https://pkg.go.dev/golang.org/x/sync/singleflight).

---

## The problem

Cache misses under load create a **thundering herd**:

```
t=0ms  →  request-1  →  cache miss  →  DB query starts
t=1ms  →  request-2  →  cache miss  →  DB query starts   ← duplicate!
t=1ms  →  request-3  →  cache miss  →  DB query starts   ← duplicate!
...
t=0ms  →  request-N  →  cache miss  →  DB query starts   ← N duplicates total
```

A spike of 100 concurrent users requesting `user:42` — all with a cold cache — fires 100 identical DB queries. Each one wakes up a DB connection, competes for row locks, and returns the same bytes.

`@Cacheable` solves the *caching* problem (result reuse after the first call), but doesn't help during the brief window while the first call is still in flight.

---

## The solution

```
t=0ms  →  request-1  →  cache miss  →  DB query starts   ← leader
t=1ms  →  request-2  →  cache miss  →  waits for leader  ← coalesced
t=1ms  →  request-3  →  cache miss  →  waits for leader  ← coalesced
...
t=80ms →  DB responds →  all 100 callers receive the same result
```

100 requests. 1 DB call. Zero duplicate work.

---

## Quick start

### Spring Boot (annotation-driven)

Add the dependency (once published to Maven Central):

```xml
<dependency>
    <groupId>io.github.shubhamjaggi</groupId>
    <artifactId>relay-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

Annotate any Spring-managed method with `@Coalesce`. The `key` is a SpEL expression — the same syntax as `@Cacheable`:

```java
@Service
public class UserService {

    @Coalesce(key = "#userId")
    public UserProfile fetchProfile(String userId) {
        return remoteProfileService.fetch(userId);   // only called once per concurrent wave
    }
}
```

Auto-configuration registers the AOP aspect automatically — no `@Enable*` annotations needed.

### More SpEL key examples

```java
// Single parameter
@Coalesce(key = "#userId")
public UserProfile fetchProfile(String userId) { ... }

// Composite key — coalesces per tenant + resource pair
@Coalesce(key = "#tenantId + ':' + #resourceId")
public Resource fetchResource(String tenantId, String resourceId) { ... }

// Nested property access
@Coalesce(key = "#request.userId")
public UserProfile fetchProfile(ProfileRequest request) { ... }

// Static key — all callers share a single execution regardless of arguments
@Coalesce(key = "'global-config'")
public Config fetchConfig() { ... }
```

---

### Without Spring (plain Java)

```java
RelayGroup<UserProfile> group = new RelayGroup<>();

// Safe to call from any number of threads concurrently.
UserProfile profile = group.execute(userId, () -> remoteProfileService.fetch(userId));
```

### Async variant

```java
CompletableFuture<UserProfile> future = group.executeAsync(userId,
        () -> remoteProfileService.fetchAsync(userId));
```

All concurrent callers for the same key receive the **same** `CompletableFuture` — no thread is blocked waiting.

---

## How it works

```
Thread A  ──► putIfAbsent("user:42", future)  →  null    →  leader: starts DB call
Thread B  ──► putIfAbsent("user:42", future)  →  future  →  follower: blocks on future.get()
Thread C  ──► putIfAbsent("user:42", future)  →  future  →  follower: blocks on future.get()

DB responds ──► future.complete(result)  →  map.remove("user:42")
                └─► Thread A returns result
                └─► Thread B unblocks, returns same result
                └─► Thread C unblocks, returns same result
```

The deduplication window is the exact duration of the in-flight call — not a TTL, not a lease, not a lock. As soon as the call completes (success or failure), the next caller starts fresh.

The implementation is lock-free: `ConcurrentHashMap.putIfAbsent` provides the atomic "check-then-insert" guarantee without a `synchronized` block.

---

## Error propagation

If the leader fails, **all** followers receive the same exception — the same behavior as Go's `singleflight`. After failure the key is cleared, so the next caller retries normally.

```java
@Coalesce(key = "#orderId")
public Order fetchOrder(String orderId) {
    throw new ServiceUnavailableException("downstream is down");
    // Every caller waiting on this orderId receives ServiceUnavailableException.
    // The next wave of callers will try again fresh.
}
```

---

## `@Coalesce` vs `@Cacheable`

| | `@Cacheable` | `@Coalesce` |
|---|---|---|
| **What it stores** | Result, until eviction | Nothing — deduplicates only while the call is in-flight |
| **Solves** | Repeated lookups for the same key over time | Concurrent duplicate calls in the same instant |
| **TTL / eviction** | Required | Not applicable |
| **Use together?** | Yes — pair them for full coverage | |

They complement each other. A common production pattern:

```java
@Cacheable("profiles")
@Coalesce(key = "#userId")
public UserProfile fetchProfile(String userId) { ... }
```

`@Coalesce` prevents the thundering herd on a cold cache; `@Cacheable` prevents the repeat calls after the first result is stored.

---

## Building and testing

```bash
# Linux / macOS
./mvnw verify

# Windows
.\mvnw.cmd verify
```

The first run downloads Maven 3.9.6 automatically — no installation required. A coverage report is written to `target/site/jacoco/index.html`.

---

## Project structure

```
src/main/java/io/github/shubhamjaggi/relay/
├── package-info.java                    # Package-level Javadoc
├── RelayGroup.java                      # Core — no Spring dependency
└── spring/
    ├── package-info.java                # Package-level Javadoc
    ├── Coalesce.java                    # @Coalesce annotation
    ├── CoalesceAspect.java              # AOP aspect (SpEL key evaluation)
    └── RelayAutoConfiguration.java

src/test/java/io/github/shubhamjaggi/relay/
├── RelayGroupTest.java                  # Concurrent correctness tests for core API
└── spring/
    ├── CoalesceAspectTest.java          # Spring integration tests
    └── RelayAutoConfigurationTest.java  # Autoconfiguration and @ConditionalOnMissingBean tests
```

---

## Limitations

**Spring proxy requirement.** `@Coalesce` only fires when the method is called through
the injected Spring bean — not via `this.method()` within the same class. This is the
same constraint as `@Transactional` and `@Cacheable`. If you call an annotated method
on `this`, the annotation is silently skipped.

**Synchronous methods only.** Methods returning `CompletableFuture` are not coalesced
at the async level in v0.1.0 — use `RelayGroup.executeAsync()` directly for that
case. See [FAQ: Can I use @Coalesce on a method that returns CompletableFuture?](docs/faq.md).

**Public methods only.** Spring AOP cannot intercept `private` or `protected` methods.

**No result caching.** `@Coalesce` deduplicates in-flight calls only. After the execution
finishes, nothing is stored. Pair with `@Cacheable` for result reuse across time.

---

## Further reading

- [How it works](docs/how-it-works.md) — internal mechanics: leader election, `putIfAbsent`, SpEL caching, the `WrappedThrowable` technique
- [FAQ](docs/faq.md) — common questions answered precisely

---

## Roadmap

- [ ] `@CoalesceAsync` — native support for methods returning `CompletableFuture`
- [ ] Micrometer metrics — suppression ratio, in-flight count gauge
- [ ] Virtual thread compatibility (Project Loom)
- [ ] Publish to Maven Central

---

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for how to build, test, and submit a PR.

Please read our [Code of Conduct](CODE_OF_CONDUCT.md) before participating.

---

## License

Apache License 2.0 — see [LICENSE](LICENSE).
