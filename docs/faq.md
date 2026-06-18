# Frequently Asked Questions

---

### Does `@Coalesce` cache results?

No. `@Coalesce` deduplicates **in-flight** concurrent calls — calls that are running right now, at this moment. It does not store results anywhere.

"In-flight" means a call has entered the method body but has not yet returned a value. If 50 threads call the same method with the same key while one execution is already in-flight, all 50 wait for that one execution to finish. When it finishes, relay-java removes the result from its internal tracking state. It is not stored anywhere for future callers. The next call — even one millisecond later — will run the full operation again from scratch.

If you want to avoid calling the database repeatedly over time, use `@Cacheable`. It stores the result in a cache (backed by Redis, Caffeine, or another backend) and returns the stored result on subsequent calls without running the method body again:

```java
@Cacheable("products")  // stores the result; returns it on the second call, third call, etc.
@Coalesce(key = "#id")  // prevents duplicate DB calls during the initial cache miss
public Product fetchProduct(String id) { ... }
```

`@Cacheable` handles avoiding repeat calls over time. `@Coalesce` handles the burst of concurrent calls that happens during a cache miss. They solve different problems and work well together.

---

### What is the difference between `@Coalesce` and `@Cacheable`?

They solve different problems.

**`@Cacheable`** (from `org.springframework.cache.annotation`) stores the return value of a method call in a cache backend. On any subsequent call with the same key, Spring returns the stored value immediately without executing the method body. The stored value persists until its time-to-live (TTL) expires or the entry is explicitly evicted from the cache.

**`@Coalesce`** stores nothing. It only acts while a call for a given key is currently executing. If two threads call the same `@Coalesce`-annotated method with the same key at the same moment, only one method body executes — the other thread waits and then receives the same result. As soon as the method body finishes, `@Coalesce` discards all tracking for that key.

| | `@Cacheable` | `@Coalesce` |
|---|---|---|
| Stores the result after the call | Yes, until TTL/eviction | No |
| Prevents duplicate in-flight calls | No | Yes |
| Requires a cache backend (Redis, Caffeine, etc.) | Yes | No |
| When it is useful | Avoiding repeated calls over minutes or hours | Avoiding concurrent duplicate calls in the same instant |

`@Cacheable` alone does not prevent the thundering herd: if 500 threads all check a cold cache at the same moment, `@Cacheable` lets each thread see a cache miss and start its own database call. `@Coalesce` fills this gap by collapsing those 500 concurrent calls into one. The two annotations together provide full coverage.

---

### Can I use `@Coalesce` on a private method?

No. `@Coalesce` uses **Spring AOP**, which intercepts method calls through a proxy. When you declare a class as a Spring bean (for example with `@Service`), Spring does not give other components a direct reference to your class instance. Instead, it gives them a reference to a **proxy** — a generated wrapper object. Every call to a method on the injected reference goes to the proxy first, the proxy checks whether any aspect applies, and if it does, it runs the aspect before calling through to the real method.

A proxy can only intercept calls that come through the proxy object itself. When a method inside a class calls another method in the same class using `this.methodName()`, it calls the real instance directly — the proxy is not involved. `private` methods can only be called by other methods in the same class, always via `this` — never through the proxy. So Spring AOP cannot intercept them. Annotating a private method with `@Coalesce` has no effect and produces no error.

This same restriction applies to `@Transactional` and `@Cacheable`.

**Workaround:** Move the method you want to annotate into a separate class annotated with `@Service`. Inject that class into the original class and call the method through the injected reference. The injected reference is the proxy, so the annotation will be intercepted correctly.

---

### Can I use `@Coalesce` on a method that returns `CompletableFuture`?

Not effectively in v0.1.0. Here is why.

`@Coalesce` wraps the intercepted **method body** with deduplication logic. The method body for an async method that returns `CompletableFuture` runs almost instantly — it creates a `CompletableFuture` object and returns it. The actual work (the database call, the HTTP request) runs later, in a different thread, after the method has already returned. `@Coalesce` would deduplicate the instantaneous act of creating the future object, not the async work the future represents.

For async deduplication, use `RelayGroup.executeAsync()` directly:

```java
// One RelayGroup instance per resource type, typically a field.
private final RelayGroup<Product> group = new RelayGroup<>();

public CompletableFuture<Product> fetchProduct(String id) {
    // The Supplier is only called for the first thread in each concurrent wave.
    // All concurrent callers receive the same CompletableFuture.
    return group.executeAsync(id, () -> remoteApi.fetchAsync(id));
}
```

`executeAsync` accepts a `Supplier<CompletableFuture<V>>` — a function that, when called, returns a future. relay-java calls the supplier only for the leader thread. All other concurrent callers for the same key receive the same `CompletableFuture` the leader is driving.

A **`CompletableFuture<V>`** is a Java object that represents a computation that has not yet finished. It is empty when created. When the computation finishes, it is populated with a result (accessible via `.get()`) or an exception. No thread blocks while waiting — callers can register callbacks or call `.get()` themselves when they are ready to use the result.

Native annotation-based async support (`@CoalesceAsync`) is on the [roadmap](../README.md#roadmap).

---

### What is a "wave"?

A **wave** is the complete set of concurrent callers that share a single execution for a given key.

The first thread to call `execute()` for a key that has no in-flight execution becomes the **leader** of wave 1. It runs the work. Every thread that calls `execute()` for the same key while the leader is still running becomes a **follower** in wave 1. Followers block until the leader finishes and then receive its result.

When the leader finishes and removes the key from the internal map, wave 1 ends. The next thread that calls `execute()` for that key starts wave 2 as a new leader.

There is no time boundary for a wave. A wave ends exactly when the in-flight execution finishes, which could be milliseconds or seconds depending on what the work does.

---

### Why do my tests sometimes show 2 or 3 executions instead of exactly 1?

This is correct behavior in edge cases, not a bug.

relay-java deduplicates calls that arrive while an execution is **currently in progress**. If the leader finishes and removes the key from the map before some threads have arrived, those threads will see an empty map and start their own executions.

A scenario where you see 2 executions:

1. Thread 1 starts an execution for key `"x"` (wave 1 begins).
2. Thread 1's execution finishes. relay-java removes the key from the map (wave 1 ends).
3. Thread 2 calls `execute("x", ...)` after step 2. It sees no in-flight execution and starts its own (wave 2 begins).

Thread 2 and Thread 1 were not truly concurrent for that key — Thread 2 arrived after Thread 1 had already finished. relay-java correctly treated Thread 2 as a new independent caller.

The concurrent tests use work that sleeps for 80 milliseconds to keep the in-flight window open long enough for all test threads to arrive during wave 1 on most machines. The tests assert `≤ 3` executions rather than exactly 1, because on a very slow or heavily loaded machine, thread scheduling may delay some threads long enough that they arrive after the first wave ends and start a second (or rarely third) wave. The bound `≤ 3` is tight enough to catch real bugs — a broken implementation would produce a count equal to the total number of threads, not just 2 or 3.

---

### Is `RelayGroup` thread-safe?

Yes, fully. All mutable state in `RelayGroup` lives in a single `ConcurrentHashMap`. All operations on that map go through `putIfAbsent` and the two-argument `remove`, both of which are atomic operations on `ConcurrentHashMap`. Multiple threads can call `execute` and `executeAsync` simultaneously for any number of keys without any external synchronization.

`RelayGroup` contains no `synchronized` blocks and holds no locks itself.

---

### Can I use `RelayGroup` without Spring?

Yes. `RelayGroup` is in the `io.github.shubhamjaggi.relay` package, which has no Spring dependencies at all. It requires only the Java standard library.

Create one `RelayGroup` instance per resource type and reuse it across all threads. A common pattern is to make it a field:

```java
// One instance, shared across all callers.
private final RelayGroup<UserProfile> group = new RelayGroup<>();

public UserProfile fetchProfile(String userId) throws Exception {
    // This is safe to call from any number of concurrent threads.
    // Only one remoteService.fetch() call runs per concurrent wave per userId.
    return group.execute(userId, () -> remoteService.fetch(userId));
}
```

`execute(key, work)` takes a key string and a `Callable` — a block of code that returns a value and can throw an exception. If a call for the same key is already in progress, the calling thread blocks until that call finishes and receives its result.

---

### Does it work with virtual threads (Project Loom)?

**Virtual threads** (stable in Java 21 via Project Loom) are threads managed by the JVM rather than the operating system. They are designed to be cheap to create and to block without consuming an OS thread while blocked. The JVM parks a blocked virtual thread and reuses its underlying OS thread ("carrier thread") for other virtual threads.

One concern with virtual threads is **pinning**: when a virtual thread enters a `synchronized` block, the JVM cannot move it to a different carrier thread while it is blocked, which negates the benefit of virtual threads. relay-java has no `synchronized` blocks, so this concern does not apply.

However, followers in `RelayGroup.execute()` call `CompletableFuture.get()`, which blocks the calling thread until the future is completed. On virtual threads, `get()` parks the virtual thread (the carrier is released), which is efficient. The blocking is correct.

If you want to avoid blocking threads entirely — virtual or otherwise — use `executeAsync()`. It returns the `CompletableFuture` immediately without blocking any thread. Callers can register callbacks with `thenApply`, `thenAccept`, or similar methods to run code when the result is available.

Explicit optimization and testing for virtual threads is on the [roadmap](../README.md#roadmap).

---

### What happens if the `key` SpEL expression evaluates to null?

SpEL expressions can evaluate to `null`. For example, `@Coalesce(key = "#userId")` evaluates to `null` when `userId` is `null`.

relay-java builds the final deduplication key by concatenating the method signature and the evaluated SpEL value using Java string concatenation, which converts `null` to the literal string `"null"`:

```
compositeKey = methodSignature + "::" + null
             = "public ...fetchProfile(java.lang.String)::null"
```

This means all concurrent callers whose SpEL expression evaluates to `null` are coalesced under the same `"null"` key — all of them share one execution. If different callers pass `null` for different reasons and expect independent calls, they will incorrectly share a single execution.

Validate your key inputs before reaching the annotated method to ensure the key expression will not produce `null`.

---

### Can two different methods collide on the same deduplication key by accident?

No. `CoalesceAspect` prepends the full method signature to the SpEL-evaluated value before passing it to `RelayGroup`:

```
compositeKey = method.toGenericString() + "::" + evaluatedSpelValue
```

`method.toGenericString()` returns a string that includes the declaring class, return type, method name, and parameter types. For example:

```
public java.lang.String com.example.UserService.fetchProfile(java.lang.String)
```

Even if `fetchProfile("42")` and `fetchOrder("42")` are both annotated with `@Coalesce(key = "#id")` and called with the same argument, their composite keys are:

```
public java.lang.String com.example.UserService.fetchProfile(java.lang.String)::42
public java.lang.String com.example.OrderService.fetchOrder(java.lang.String)::42
```

These are different strings. No collision.
