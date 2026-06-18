# How It Works

This document explains the internal mechanics of relay-java for contributors
and anyone curious about the implementation. Reading the README first is recommended.

---

## The core data structure

The entire deduplication mechanism lives in one field:

```java
ConcurrentHashMap<String, CompletableFuture<V>> inFlight
```

- **Key**: the deduplication key string (e.g. `"user-42"`)
- **Value**: a `CompletableFuture` that will be completed when the work for that key finishes

When the map contains an entry for a key, there is an in-flight execution for it. When it
does not, the key is idle and the next caller will start a new execution.

---

## The leader election: `putIfAbsent`

The thread-safe coordination is handled by a single line:

```java
CompletableFuture<V> existing = inFlight.putIfAbsent(key, leader);
```

`ConcurrentHashMap.putIfAbsent` is atomic: it checks whether the key is absent and inserts
the new value in one uninterruptible operation. There is no window between the check and the
insert where another thread can interfere.

- If `existing == null` → the key was not in the map; our `leader` future was inserted; **this thread is the leader**
- If `existing != null` → someone else already inserted a future; **this thread is a follower**

No `synchronized` block, no explicit lock, no `volatile` flag. The entire coordination
is a single atomic map operation.

---

## Execution flow

### Leader path

```
Thread A calls execute("user-42", work)
  │
  ├── leader = new CompletableFuture<>()
  ├── putIfAbsent("user-42", leader) → null      // map was empty; we're the leader
  │
  ├── result = work.call()                       // the actual DB call / HTTP call / etc.
  ├── leader.complete(result)                    // wake up all followers
  ├── return result  ← finally: inFlight.remove("user-42", leader)
  └── (key removed before value is handed back to caller)
```

`return result` initiates the return, but the `finally` block runs first, removing the
key before the value reaches the caller. The `remove(key, value)` two-argument form is
important: it only removes the entry if the value still matches our future. This prevents
a race where a new leader inserts a fresh future for the same key between our `complete`
and our `remove`.

### Follower path

```
Thread B calls execute("user-42", work)  [while Thread A is in flight]
  │
  ├── leader = new CompletableFuture<>()  // created but never used
  ├── putIfAbsent("user-42", leader) → A's future   // map already had an entry
  │
  └── return existing.get()               // block until Thread A completes the future
```

The follower's `leader` future is never inserted into the map and is immediately garbage
collected. The follower simply parks on `existing.get()` and wakes up when Thread A calls
`complete()`.

### After completion

```
Thread C calls execute("user-42", work)  [after Thread A has completed]
  │
  ├── putIfAbsent("user-42", leader) → null   // map was empty again
  └── ... Thread C becomes the new leader
```

There is no caching. Every new "wave" (burst of concurrent callers) starts a fresh execution.

---

## Error propagation

When the leader throws an exception:

```java
} catch (Exception e) {
    leader.completeExceptionally(e);  // store the exception in the future
    throw e;                          // also throw it for the leader's own caller
} finally {
    inFlight.remove(key, leader);     // clean up regardless
}
```

Followers waiting on `existing.get()` receive an `ExecutionException` wrapping the leader's
exception. The `catch (ExecutionException e)` block unwraps it:

```java
Throwable cause = e.getCause();
if (cause instanceof Exception ex) throw ex;   // rethrow original exception type
throw new RuntimeException(cause);             // wrap Error subclasses (rare)
```

This means all callers — leader and followers — see the same original exception type and
message, with no extra wrapper.

---

## The async variant (`executeAsync`)

```java
public CompletableFuture<V> executeAsync(String key, Supplier<CompletableFuture<V>> work)
```

The leader/follower election works identically via `putIfAbsent`. The difference is in
what the follower returns and how the leader drives completion:

**Follower**: returns `existing` directly — the same `CompletableFuture` the leader is
driving. No thread is blocked; followers chain their callbacks on the returned future.

**Leader**: calls `work.get()` to obtain the caller's async computation, then wires its
`whenComplete` into the shared leader future:

```java
work.get().whenComplete((result, ex) -> {
    inFlight.remove(key, leader);
    if (ex != null) leader.completeExceptionally(ex);
    else leader.complete(result);
});
```

The shared future acts as a relay: the leader's async computation resolves first, which
triggers the `whenComplete` callback, which in turn completes the shared future, which
notifies all followers.

---

## The Spring AOP layer

### How `@Coalesce` wires up

When Spring boots up, it finds `RelayAutoConfiguration` via the autoconfiguration
imports file and registers a `CoalesceAspect` bean. Spring then wraps every bean that has
a `@Coalesce`-annotated method in a proxy. Calls to those methods go through
`CoalesceAspect.around()` instead of directly to the method.

```
Caller → Spring proxy → CoalesceAspect.around() → RelayGroup.execute() → real method
```

### Key construction

The deduplication key for the group is built in `CoalesceAspect.buildKey()`:

```
compositeKey = method.toGenericString() + "::" + evaluatedSpelValue
```

Example:
```
public java.lang.String com.example.UserService.fetchProfile(java.lang.String)::user-42
```

The method signature prefix is critical. Without it, `fetchProfile("42")` and
`fetchOrder("42")` would produce the same key (`"42"`) and share an execution — a bug.
By including the full generic method signature, every method gets its own namespace.

### SpEL expression caching

Parsing a SpEL expression (e.g. `"#tenantId + ':' + #resourceId"`) involves lexing and
building an AST. Doing this on every method call would be measurably slow. The aspect
caches parsed `Expression` objects:

```java
expressionCache.computeIfAbsent(
    methodId + "::" + coalesce.key(),
    k -> parser.parseExpression(coalesce.key())
);
```

The cache is keyed by `methodSignature + "::" + spelString` — unique per method+expression
combination. Parsing happens at most once per distinct annotated method; every subsequent
call just evaluates the already-parsed AST.

### The `WrappedThrowable` trick

`RelayGroup.execute()` accepts a `Callable<V>`, which can only declare
`throws Exception`. But `ProceedingJoinPoint.proceed()` can throw any `Throwable`
including `Error` subclasses.

The bridge:
1. Inside the `Callable`: wrap any `Throwable` in a `WrappedThrowable extends RuntimeException`
2. Outside the `Callable` (in `around()`): catch `WrappedThrowable` and rethrow `e.getCause()`

```java
return group.execute(compositeKey, () -> {
    try {
        return pjp.proceed();
    } catch (Throwable t) {
        throw new WrappedThrowable(t);  // RuntimeException — allowed by Callable
    }
});
// ...
} catch (WrappedThrowable e) {
    throw e.getCause();  // restore original type
}
```

`WrappedThrowable` is a private nested class — it never escapes the aspect. Callers always
see the original exception from the annotated method.

---

## Why `CountDownLatch` in concurrent tests?

The concurrent tests need to prove that many threads can arrive at the same key
simultaneously. Simply starting threads in a loop doesn't guarantee they all call
`execute` at the same time — the first thread might complete before the last one starts.

The pattern:

```java
CountDownLatch ready = new CountDownLatch(N);  // all threads signal when ready
CountDownLatch start = new CountDownLatch(1);  // main thread releases them at once

// In each thread:
ready.countDown();   // "I'm ready and parked"
start.await();       // wait for the go signal
group.execute(...);  // now all N threads call this as simultaneously as possible
```

The main thread waits for all `ready` signals before releasing `start`. This ensures all
threads are already scheduled and waiting when the work begins, giving the best chance of
a true concurrent race. The work then sleeps 80 ms to keep the in-flight window open long
enough for all threads to arrive and observe the in-flight future.
