# Frequently Asked Questions

---

### Does `@Coalesce` cache results?

No. `@Coalesce` deduplicates concurrent in-flight calls — it does not store results.
Once an execution finishes, the result is discarded from the group's internal state.
The next caller after completion starts a brand new execution.

If you want to store results for reuse across time, combine `@Coalesce` with
`@Cacheable`:

```java
@Cacheable("products")   // stores the result until eviction
@Coalesce(key = "#id")   // deduplicates concurrent misses for the same id
public Product fetchProduct(String id) { ... }
```

`@Cacheable` handles the long tail; `@Coalesce` handles the thundering herd on a
cold cache.

---

### What's the difference between `@Coalesce` and `@Cacheable`?

| | `@Cacheable` | `@Coalesce` |
|---|---|---|
| Stores result | Yes, until TTL/eviction | No |
| Deduplicates in-flight calls | No | Yes |
| Needs a cache store (Redis, Caffeine…) | Yes | No |
| Useful window | Repeated calls over time | Concurrent calls in the same instant |

They solve different problems and work well together.

---

### Can I use `@Coalesce` on a private method?

No. Spring AOP works by wrapping beans in a proxy at runtime. A proxy can only intercept
calls made through the proxy object (i.e., through the injected bean reference). Private
methods and `this.method()` calls within the same class bypass the proxy and are not
intercepted.

This is the same constraint as `@Transactional` and `@Cacheable`.

**Workaround:** Move the annotated method to a separate Spring bean and inject that bean.

---

### Can I use `@Coalesce` on a method that returns `CompletableFuture`?

Not yet — v0.1.0 only supports synchronous methods. Calling `@Coalesce` on a method
returning `CompletableFuture` would coalesce the creation of the future object itself
(which is fast and not the bottleneck) rather than the async work it represents.

Native async support is on the [roadmap](../README.md#roadmap). In the meantime, use
`RelayGroup.executeAsync()` directly:

```java
private final RelayGroup<Product> group = new RelayGroup<>();

public CompletableFuture<Product> fetchProduct(String id) {
    return group.executeAsync(id, () -> remoteApi.fetchAsync(id));
}
```

---

### What is a "wave"?

A wave is the set of concurrent callers that all arrive while a single execution is
in flight for a given key. The first caller in a wave becomes the leader (it runs the
work); all others become followers (they wait for the leader).

When the leader finishes, the wave ends. Any callers that arrive after the leader has
completed form a new wave with a new leader.

---

### Why do my tests sometimes show 2 or 3 executions instead of exactly 1?

Thread scheduling is non-deterministic. The scenario where you see 2 executions:

1. Wave 1 starts: Thread A becomes leader.
2. Thread A finishes: the key is removed from the map.
3. Thread B arrives after the removal: it doesn't see an in-flight future, so it
   becomes the leader of wave 2.

This is correct behavior. If the 80 ms sleep in the work keeps the window open long
enough, all threads arrive during wave 1. On a slow or heavily loaded machine, some
threads might arrive in wave 2.

The assertion `≤3` in the tests is intentionally loose to tolerate this without being
so loose that it misses real bugs.

---

### Is `RelayGroup` thread-safe?

Yes, fully. The implementation relies on `ConcurrentHashMap.putIfAbsent` as its single
atomic primitive. There are no explicit `synchronized` blocks or locks. Multiple threads
can call `execute` and `executeAsync` concurrently for any number of keys.

---

### Can I use `RelayGroup` without Spring?

Yes — the core `RelayGroup` class has no Spring dependencies. Add just the JAR
(without Spring Boot autoconfigure) or use the `optional` dependency scope in your own
library:

```java
// Create once and reuse — typically a field or singleton bean.
private final RelayGroup<UserProfile> group = new RelayGroup<>();

public UserProfile fetchProfile(String userId) throws Exception {
    return group.execute(userId, () -> remoteService.fetch(userId));
}
```

---

### Does it work with virtual threads (Project Loom)?

The implementation works correctly with virtual threads — there are no `synchronized`
blocks that would cause virtual thread pinning. However, followers call
`CompletableFuture.get()` which parks the carrier thread. On Java 21+, if you're using
virtual threads, prefer `executeAsync()` to avoid blocking a carrier.

Explicit virtual-thread optimisation is on the [roadmap](../README.md#roadmap).

---

### What happens if the `key` SpEL expression is null or evaluates to null?

The key is built via Java string concatenation (`methodId + "::" + keyVal`), which
converts a null value to the literal string `"null"`. This means all calls with a null key value are coalesced
together under the `"null"` key. This is rarely the intended behavior — validate your
key inputs to avoid it.

---

### Can two different methods collide on the same key?

No. `CoalesceAspect` prepends the full generic method signature to the SpEL-evaluated
value before passing it to the `RelayGroup`. So even if `fetchProfile("42")`
and `fetchOrder("42")` are both annotated with `@Coalesce(key = "#userId")` and receive
the same argument, their internal keys are:

```
public java.lang.String com.example.UserService.fetchProfile(java.lang.String)::42
public java.lang.String com.example.OrderService.fetchOrder(java.lang.String)::42
```

These are distinct strings — no collision.
