# relay-java

[![CI](https://github.com/shubhamjaggi/relay-java/actions/workflows/ci.yml/badge.svg)](https://github.com/shubhamjaggi/relay-java/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/shubhamjaggi/relay-java/branch/main/graph/badge.svg)](https://codecov.io/gh/shubhamjaggi/relay-java)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

relay-java prevents duplicate concurrent calls. When many threads call the same method with the same argument at the same time, only one actual execution runs — every thread waiting for the same result receives it from that single execution.

Inspired by Go's [`sync/singleflight`](https://pkg.go.dev/golang.org/x/sync/singleflight).

---

## Key concepts

This section defines every term used in the documentation. If you are familiar with threads, caches, and the thundering herd problem, skip to [Quick start](#quick-start).

### Threads

A **thread** is an independent sequence of instructions executing inside a Java program. A program can have many threads running at the same time. A web server typically assigns one thread per incoming HTTP request, so 200 simultaneous requests means 200 threads running concurrently.

### Concurrent calls

A call is **in-flight** from the moment a thread enters a method until the moment that thread receives the return value. Two calls are **concurrent** when both are in-flight at the same time — thread 1 has started the method call but has not yet received its result, and thread 2 starts the same call before thread 1 finishes.

### Caches

A **cache** is fast in-memory storage that holds the results of expensive operations. Instead of querying a database or calling an external service every time, the application checks the cache first. If the result is present (**cache hit**), it is returned immediately. If not (**cache miss**), the application runs the expensive operation, stores the result in the cache, and returns it.

### The thundering herd problem

When a key is not in the cache, every thread that looks for it will find it missing and independently start the expensive operation, unaware that other threads are doing the same thing.

If 500 threads all check the cache for `user:42` at the same moment:

- Thread 1 finds a cache miss → starts a database query
- Thread 2 finds a cache miss → starts another database query
- Threads 3 through 500 each find a cache miss → each starts its own database query

500 threads fire 500 identical database queries. Every query hits the same database rows and returns the same data. 499 of those queries are duplicate work that wastes database connections and time. This situation — many concurrent threads simultaneously triggering the same expensive operation because none of them can see the others' in-progress results — is called the **thundering herd problem**.

---

## The solution

relay-java tracks which keys have an execution currently in progress. When the first thread starts a call for key `"user:42"`, relay-java records it. When the next 499 threads arrive for the same key while that first call is still running, relay-java makes them wait instead of starting their own calls. When the first call finishes, all 500 threads receive the result.

```
Thread 1  →  cache miss  →  relay-java records "user:42 in progress"  →  DB query starts   ← 1 query
Thread 2  →  cache miss  →  relay-java sees "user:42 in progress"     →  waits
Thread 3  →  cache miss  →  relay-java sees "user:42 in progress"     →  waits
...
Thread 500 → cache miss  →  relay-java sees "user:42 in progress"     →  waits

DB responds  →  relay-java removes "user:42"  →  all 500 threads receive the result
```

500 requests. 1 database query. 499 duplicate queries prevented.

---

## Quick start

### Spring Boot

**Spring Boot** is a Java framework that wires application components together automatically at startup. It discovers components on the classpath and configures them without requiring manual setup code.

Add the dependency (once published to Maven Central):

```xml
<dependency>
    <groupId>io.github.shubhamjaggi</groupId>
    <artifactId>relay-java</artifactId>
    <version>0.1.0</version>
</dependency>
```

Annotate any Spring-managed method with `@Coalesce`:

```java
@Service
public class UserService {

    @Coalesce(key = "#userId")
    public UserProfile fetchProfile(String userId) {
        return remoteProfileService.fetch(userId);
    }
}
```

**`@Service`** is a Spring annotation that tells Spring to create and manage one instance of this class. That instance is then available to be injected into other components that need it.

**`@Coalesce`** is the annotation this library provides. It activates call coalescing for the method. Two concurrent calls are considered duplicates — and therefore coalesced — when their `key` attribute evaluates to the same value at runtime. `key = "#userId"` is a **SpEL expression** explained in the next section.

When relay-java is on the classpath, Spring Boot registers the interception mechanism automatically. No extra configuration or `@Enable*` annotations are needed.

### The `key` attribute and SpEL

The `key` attribute determines which calls are coalesced together. Two concurrent calls are only coalesced when their `key` expressions evaluate to the same string.

`key` is written in **SpEL (Spring Expression Language)**, a language Spring evaluates at runtime. The `#` prefix refers to a method parameter by the name it has in source code.

```java
// #userId refers to the method parameter named userId.
// Two calls are coalesced only when both have the same userId value.
@Coalesce(key = "#userId")
public UserProfile fetchProfile(String userId) { ... }

// Multiple parameters combined with + into one string.
// Single-quoted 'strings' are string literals in SpEL.
// Two calls are coalesced only when both tenantId and resourceId match.
@Coalesce(key = "#tenantId + ':' + #resourceId")
public Resource fetchResource(String tenantId, String resourceId) { ... }

// #request.userId accesses the userId field (or getter) on a parameter object.
@Coalesce(key = "#request.userId")
public UserProfile fetchProfile(ProfileRequest request) { ... }

// A single-quoted string with no # is a constant. All concurrent calls to this
// method share a single execution regardless of arguments.
@Coalesce(key = "'global-config'")
public Config fetchConfig() { ... }
```

---

### Without Spring (plain Java)

`RelayGroup<V>` can be used in any Java application without Spring. Create one instance and share it across threads:

```java
// Create once — typically a field so the same group is used for all calls.
RelayGroup<UserProfile> group = new RelayGroup<>();

// Safe to call from any number of concurrent threads.
// The lambda () -> remoteProfileService.fetch(userId) is called at most once per wave.
UserProfile profile = group.execute(userId, () -> remoteProfileService.fetch(userId));
```

The second argument is a **Callable** — a block of code that returns a value. If a call for the same key is already in progress, the calling thread waits for that call to finish and receives its result. The Callable is only invoked for the first thread in each group of concurrent callers.

### Async variant

`executeAsync` returns a `CompletableFuture<V>` instead of blocking the calling thread:

```java
CompletableFuture<UserProfile> future = group.executeAsync(userId,
        () -> remoteProfileService.fetchAsync(userId));
```

A **`CompletableFuture<V>`** is a Java object that holds the result of a computation that has not finished yet. It is empty when created. Once the computation finishes, it is populated with either a result (accessible via `.get()`) or an exception.

All concurrent callers for the same key receive a reference to the **same** `CompletableFuture` object — not independent copies. No thread blocks. Every caller can register callbacks on the returned future or call `.get()` to wait for the result.

---

## How it works

relay-java maintains a `ConcurrentHashMap<String, CompletableFuture<V>>` called `inFlight`. The map key is the deduplication key string; the map value is a `CompletableFuture` that will be completed when the in-progress call for that key finishes.

**`ConcurrentHashMap`** is a thread-safe map that multiple threads can read and write simultaneously without corrupting the data. Its `putIfAbsent` method is **atomic** — it checks whether a key is absent and inserts a new value as a single uninterruptible step, so no two threads can both believe they inserted first.

When a thread calls `execute("user:42", work)`:

1. It creates a new `CompletableFuture` and calls `putIfAbsent("user:42", future)`.
2. If `putIfAbsent` returns `null`, no entry existed — this thread is the **leader**. It runs the work, calls `future.complete(result)` to notify everyone, then removes the key.
3. If `putIfAbsent` returns a non-null future, another thread already inserted one — this thread is a **follower**. It calls `existing.get()`, which blocks until the leader completes the future, then returns the same result.

```
Thread A  →  putIfAbsent("user:42", future)  →  returns null      →  leader: runs DB call
Thread B  →  putIfAbsent("user:42", future)  →  returns future A  →  follower: waits on future A
Thread C  →  putIfAbsent("user:42", future)  →  returns future A  →  follower: waits on future A

Thread A's DB call finishes  →  future A.complete(result)  →  key removed
Thread B unblocks  →  returns same result
Thread C unblocks  →  returns same result
```

For complete implementation details — including how errors are propagated, how the async variant works, and how Spring AOP intercepts the calls — see [How it works](docs/how-it-works.md).

---

## Error propagation

When the leader's call throws an exception, relay-java stores that exception in the shared future. Every follower that was waiting receives the same exception. After the failure, the key is removed so the next caller can retry from scratch.

```java
@Coalesce(key = "#orderId")
public Order fetchOrder(String orderId) {
    throw new ServiceUnavailableException("downstream is down");
    // Every thread waiting on orderId receives this exception.
    // The next call for orderId after this will run the operation fresh.
}
```

---

## `@Coalesce` vs `@Cacheable`

**`@Cacheable`** is a Spring annotation that stores the result of a method call in a cache backend (Redis, Caffeine, etc.). On the second call with the same key, it returns the stored result without running the method again. The result stays until its TTL expires or is explicitly evicted.

`@Coalesce` stores nothing. It only acts during the instant when a call is in progress.

| | `@Cacheable` | `@Coalesce` |
|---|---|---|
| **Stores the result after the call** | Yes, until TTL/eviction | No |
| **Prevents duplicate in-flight calls** | No | Yes |
| **Requires a cache backend** | Yes | No |
| **Useful for** | Avoiding repeated calls over time | Avoiding concurrent duplicate calls at the same instant |

They complement each other. Use both together to get full coverage:

```java
@Cacheable("profiles")     // returns stored result on the second, third, nth call
@Coalesce(key = "#userId") // prevents duplicate DB calls during the initial cache miss
public UserProfile fetchProfile(String userId) { ... }
```

`@Coalesce` prevents the thundering herd during a cold cache miss. `@Cacheable` prevents repeat calls once the result is stored. Together they handle both the concurrent burst and the ongoing load.

---

## Building and testing

This project uses Maven as its build tool. A **Maven wrapper** (`mvnw` / `mvnw.cmd`) is included, which downloads the required version of Maven automatically on the first run. No manual Maven installation is needed.

```bash
# Linux / macOS
./mvnw verify

# Windows
.\mvnw.cmd verify
```

`verify` compiles the code, runs all tests, and generates a coverage report at `target/site/jacoco/index.html`.

---

## Project structure

```
src/main/java/io/github/shubhamjaggi/relay/
├── package-info.java                    # Package-level Javadoc
├── RelayGroup.java                      # Core implementation — no Spring dependency
└── spring/
    ├── package-info.java                # Package-level Javadoc
    ├── Coalesce.java                    # @Coalesce annotation
    ├── CoalesceAspect.java              # Spring AOP aspect — intercepts @Coalesce methods
    └── RelayAutoConfiguration.java      # Spring Boot autoconfiguration

src/test/java/io/github/shubhamjaggi/relay/
├── RelayGroupTest.java                  # Concurrent correctness tests for RelayGroup
└── spring/
    ├── CoalesceAspectTest.java          # Spring AOP integration tests
    └── RelayAutoConfigurationTest.java  # Autoconfiguration and @ConditionalOnMissingBean tests
```

---

## Limitations

**Spring proxy requirement.** `@Coalesce` works by intercepting method calls through a Spring proxy — a wrapper object Spring places around your bean. The proxy intercepts calls made through the injected bean reference and runs the coalescing logic. However, if a method inside a class calls another method in the same class using `this.methodName()`, it calls the real object directly and the proxy is bypassed. In that case `@Coalesce` is silently skipped — no error is thrown. The same restriction applies to `@Transactional` and `@Cacheable`. See [How it works — Spring AOP](docs/how-it-works.md#the-spring-aop-layer) for a full explanation.

**Workaround:** Extract the annotated method into a separate `@Service` class and inject it.

**Public methods only.** Spring AOP can only intercept `public` methods. Annotating a `private` or `protected` method has no effect.

**No result caching.** `@Coalesce` only acts while a call is in progress. Once a call finishes, its result is discarded from relay-java's state. The next call to the same method runs the operation again. Pair with `@Cacheable` if you also want to reuse results across time.

**Synchronous methods only (v0.1.0).** If a method returns `CompletableFuture`, `@Coalesce` would coalesce the instantaneous creation of the future object rather than the async work it represents. Use `RelayGroup.executeAsync()` directly for async methods. See [FAQ](docs/faq.md#can-i-use-coalesce-on-a-method-that-returns-completablefuture).

---

## Further reading

- [How it works](docs/how-it-works.md) — full explanation of every implementation concept: `ConcurrentHashMap`, `putIfAbsent`, `CompletableFuture`, Spring AOP, SpEL, and more, all from first principles
- [FAQ](docs/faq.md) — specific questions answered in depth

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
