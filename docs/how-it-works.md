# How It Works

This document explains the internal mechanics of relay-java from first principles. Every Java and Spring concept used in the implementation is defined here before it is used. Reading the [README](../README.md) first is recommended.

---

## Prerequisites

This section defines every concept the implementation relies on. If you skip a term here and encounter it later in the document, come back to this section.

### Threads and concurrent execution

A **thread** is an independent sequence of instructions that a running Java program executes. Each thread has its own call stack — its own sequence of method calls in progress — and runs independently of other threads. A Java program can have many threads running at the same time.

Two calls are **concurrent** when both are executing at the same time in different threads. Concurrency creates a specific class of bug: if two threads both read and then write to the same piece of data, each may overwrite the other's result because the CPU can interleave their instructions in any order. Operations on shared data must be designed carefully to remain correct under this interleaving.

### HashMap and ConcurrentHashMap

A **HashMap** is a data structure that maps keys to values and retrieves values by key in constant time. The standard `java.util.HashMap` is not safe for concurrent use: if two threads modify it at the same time, the internal structure can become corrupt.

**`ConcurrentHashMap`** (`java.util.concurrent.ConcurrentHashMap`) is a thread-safe variant. Multiple threads can read and write it simultaneously without data corruption. Internally it divides the map into segments and uses hardware-level compare-and-swap operations so that most operations do not block each other. Reading from a `ConcurrentHashMap` never blocks; writing to it blocks only the specific key being written, not the whole map.

### Atomic operations

An operation is **atomic** if it completes as a single, indivisible unit. While an atomic operation is in progress, no other thread can observe a partially completed state — from every other thread's perspective, the operation has either not yet started or has fully completed.

The problem with non-atomic operations on shared data is a **race condition** — a bug where the outcome depends on which thread happens to run first. For example, a non-atomic "check then insert":

```
Thread A: checks "is key X in the map?" → No
Thread B: checks "is key X in the map?" → No    ← both see "No" simultaneously
Thread A: inserts key X
Thread B: inserts key X                          ← overwrites Thread A's entry
```

Both threads believe they inserted first. The outcome depends on which CPU instruction happened to run last — unpredictable and non-reproducible.

### `putIfAbsent`

`ConcurrentHashMap.putIfAbsent(key, value)` is an atomic operation that:

1. Checks whether `key` is absent from the map.
2. If absent: inserts the key-value pair and returns `null`.
3. If present: leaves the map unchanged and returns the existing value.

Steps 1 and 2 are guaranteed to happen as one uninterruptible unit. No thread can insert for the same key between step 1 and step 2. This makes `putIfAbsent` the correct primitive for "exactly one thread should start the work for this key."

Return value:
- `null` → the key was absent; this thread inserted the new value; **this thread is the first one in**
- non-null → the key was already present; this thread did not change anything; **another thread got there first**

### `CompletableFuture<V>`

`CompletableFuture<V>` (`java.util.concurrent.CompletableFuture`) is a Java class that represents a computation that has not yet finished. It starts empty. Once the computation finishes, it is populated with exactly one of:

- a **result** of type `V` — set by calling `future.complete(result)`
- an **exception** — set by calling `future.completeExceptionally(exception)`

Key operations:

- **`complete(result)`** — stores the result; every thread currently blocked on `get()` is unblocked and returns the result.
- **`completeExceptionally(exception)`** — stores an exception instead of a result; every thread currently blocked on `get()` is unblocked and receives the exception.
- **`get()`** — if the future already has a result, returns it immediately. If not, blocks the calling thread until a result (or exception) arrives.
- **`whenComplete(callback)`** — registers a function to be called when the future is completed. The function receives either `(result, null)` or `(null, exception)` depending on how the future was completed. The callback runs without blocking any thread.

`CompletableFuture` is the mechanism relay-java uses to make followers wait for the leader. The leader puts a future into the map; followers find it and call `get()`, which blocks them. When the leader calls `complete(result)`, all blocked followers unblock and return the result.

### `Callable<V>`

`Callable<V>` (`java.util.concurrent.Callable`) is a Java interface with one method:

```java
V call() throws Exception;
```

It represents a piece of work that returns a value of type `V` and may throw any checked or unchecked exception that is a subclass of `Exception`. You pass the expensive work (a database call, an HTTP call, etc.) to `RelayGroup.execute()` as a lambda that implements this interface:

```java
group.execute("user-42", () -> database.findUser("42"));
//                        ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                        this lambda is the Callable; its body is call()
```

### `Supplier<T>`

`Supplier<T>` (`java.util.function.Supplier`) is a Java interface with one method:

```java
T get();
```

It represents a function that takes no arguments and returns a value. `executeAsync` accepts a `Supplier<CompletableFuture<V>>` — a function that, when called, returns a `CompletableFuture` representing an async computation. Unlike `Callable`, `Supplier.get()` cannot declare checked exceptions.

### The Java `finally` block

In Java, a `try-finally` block guarantees that the code in the `finally` section runs no matter what happens in `try` — whether the method returns normally, or throws an exception:

```java
try {
    return doWork(); // may throw
} finally {
    cleanup(); // always runs — even if doWork() threw, even after "return"
}
```

relay-java uses a `finally` block to ensure the key is always removed from the in-flight map when a call ends, regardless of whether the call succeeded or failed. Without this guarantee, a failed call would leave its key in the map permanently, and all future callers for that key would block on a future that will never be completed.

### What is Spring Boot

**Spring Boot** is a Java framework for building applications. At startup, it scans the classpath and the application's own code for annotated classes, creates instances of them (called **beans**), wires them together by injecting dependencies, and manages their lifecycle. You mark classes with annotations like `@Service` or `@Component`, and Spring handles creating and connecting them.

### What is a Spring bean

A **Spring bean** is an object whose creation and lifecycle Spring manages. You declare a class as a bean with an annotation (`@Service`, `@Component`, `@Repository`, `@Controller`), and Spring creates exactly one instance of it at startup and makes it available to be injected into other beans. When another bean declares a field or constructor parameter of the same type, Spring injects the shared instance there.

### What is AOP

**AOP (Aspect-Oriented Programming)** is a technique for adding behavior to existing methods without modifying those methods' code. In Spring AOP:

- An **aspect** is a class that contains the behavior to add (in this case, call coalescing).
- A **pointcut** is a pattern that specifies which methods the aspect applies to (in this case, methods annotated with `@Coalesce`).
- **Around advice** is a method in the aspect that wraps the intercepted method call — it runs before and after the method, controls whether the method is called at all, and can modify the return value or exception.

### What is a Spring proxy

When Spring AOP applies an aspect to a bean, it does not modify the bean's class file. Instead, Spring replaces the bean with a **proxy** — a generated wrapper object that has the same public interface as the original bean. Every call to a method on the bean goes through the proxy first. The proxy checks whether any aspect applies to that method. If it does, the proxy calls the aspect's advice; the advice then decides whether to call through to the real method (using `pjp.proceed()`).

```
Caller          Spring-injected reference          What the caller sees
  │                        │
  └── calls fetchProfile ──┘──→ proxy.fetchProfile()
                                       │
                                       ├── CoalesceAspect.around() runs
                                       │        │
                                       │        └── calls real.fetchProfile()
                                       │
                                       └── returns result to caller
```

From the caller's perspective, they called `UserService.fetchProfile()`. They do not know a proxy exists. This is why `this.someMethod()` inside a class bypasses the proxy — `this` is a reference to the real object, not the proxy. The aspect never runs for self-calls.

### What is SpEL

**SpEL (Spring Expression Language)** is a language for writing expressions that Spring evaluates at runtime. SpEL expressions appear inside annotation attributes like `@Cacheable(key = "...")` and `@Coalesce(key = "...")`.

SpEL syntax used in `@Coalesce`:

| Expression | Meaning |
|---|---|
| `#userId` | The value of the method parameter named `userId` at call time |
| `#request.userId` | The `userId` field (or `getUserId()` getter) on the `request` parameter |
| `'literal'` | A string literal (single quotes in SpEL; do not confuse with Java's double-quoted strings) |
| `#a + ':' + #b` | Concatenates the value of `a`, the string `":"`, and the value of `b` |

Before SpEL can evaluate an expression, it must parse the expression string into an **AST (Abstract Syntax Tree)** — an internal tree structure representing the expression's operations and operands. Parsing is done once. Evaluation (plugging in the actual runtime values) is done on every call.

---

## The core data structure

The entire deduplication mechanism lives in one field inside `RelayGroup<V>`:

```java
ConcurrentHashMap<String, CompletableFuture<V>> inFlight
```

- **Map key**: the deduplication key string — for example `"user-42"` or the composite key built by `CoalesceAspect`
- **Map value**: a `CompletableFuture<V>` that will be completed when the in-progress call for that key finishes

When an entry exists in the map for a key, an execution is currently in progress for it. When no entry exists, no execution is running and the next caller will start one.

---

## Leader election: `putIfAbsent`

Every call to `execute()` first creates a fresh `CompletableFuture` and attempts to insert it:

```java
CompletableFuture<V> leader = new CompletableFuture<>();
CompletableFuture<V> existing = inFlight.putIfAbsent(key, leader);
```

Because `putIfAbsent` is atomic, exactly one thread wins the race to insert for any given key.

- `existing == null` → the key was absent; this thread's `leader` future is now in the map; **this thread is the leader**
- `existing != null` → another thread's future is already in the map; the `leader` future created by this thread is discarded; **this thread is a follower**

No `synchronized` block, no explicit lock. One atomic map operation determines every thread's role.

---

## Execution flow

### Leader path

```
Thread A calls execute("user-42", work)
  │
  ├── leader = new CompletableFuture<>()
  ├── inFlight.putIfAbsent("user-42", leader) → null   [map was empty; Thread A is leader]
  │
  ├── result = work.call()                              [the actual DB call / HTTP call / etc.]
  ├── leader.complete(result)                           [unblocks all followers]
  │   finally:
  └── inFlight.remove("user-42", leader)               [key removed; next wave starts fresh]
      └── returns result to Thread A's caller
```

The `finally` block always runs — even when `work.call()` throws. `inFlight.remove(key, leader)` uses the two-argument form, which only removes the entry if its current value is exactly the `leader` future this thread inserted. This protects against a race where a new leader for the same key has already inserted a fresh future between this thread's `complete()` call and its `remove()` call.

### Follower path

```
Thread B calls execute("user-42", work)   [while Thread A is still running]
  │
  ├── leader = new CompletableFuture<>()   [created but never inserted]
  ├── inFlight.putIfAbsent("user-42", leader) → Thread A's future  [key was already present]
  │
  └── return existing.get()               [blocks until Thread A calls complete()]
```

Thread B's `leader` future is never inserted into the map and is immediately garbage-collected. Thread B blocks on Thread A's future. When Thread A calls `leader.complete(result)`, `existing.get()` unblocks in Thread B and returns the same result.

### After the wave ends

```
Thread C calls execute("user-42", work)   [after Thread A has finished and removed the key]
  │
  ├── inFlight.putIfAbsent("user-42", leader) → null   [map is empty again]
  └── Thread C is the new leader and starts a fresh execution
```

There is no caching. After a wave ends, the next caller starts a brand new execution. A **wave** is the complete set of concurrent callers — one leader plus all followers — that share a single execution.

---

## Error propagation

When the leader's work throws an exception:

```java
} catch (Exception e) {
    leader.completeExceptionally(e); // store the exception in the future
    throw e;                         // also throw it to the leader's own caller
} finally {
    inFlight.remove(key, leader);    // always clean up; next caller can retry
}
```

`completeExceptionally(e)` stores the exception in the shared future instead of a result. Every follower blocked on `existing.get()` receives a `java.util.concurrent.ExecutionException`.

**`ExecutionException`** is a wrapper Java uses when a `Future.get()` call fails because the computation threw an exception. It is not the original exception — it wraps it. relay-java immediately unwraps it:

```java
} catch (ExecutionException e) {
    Throwable cause = e.getCause();             // unwrap to get the original exception
    if (cause instanceof Exception ex) throw ex;
    throw new RuntimeException(cause);          // handles Error subclasses (rare)
}
```

The result: every caller — leader and followers — receives the same original exception type and message. `ExecutionException` is never visible to the caller.

After a failure, the key is removed by the `finally` block. The next call for the same key starts a fresh execution and can succeed if the underlying problem has resolved.

---

## The async variant (`executeAsync`)

```java
public CompletableFuture<V> executeAsync(String key, Supplier<CompletableFuture<V>> work)
```

The leader/follower election is identical — the same `putIfAbsent` check, the same `null` test. What differs is what happens after the election:

**Follower**: returns the existing `CompletableFuture` directly to the caller, without blocking any thread. The follower's caller receives the same future the leader is driving and can call `.get()` on it or register callbacks.

**Leader**: calls `work.get()` to obtain the caller-supplied future, then connects it to the shared `leader` future using `whenComplete`:

```java
work.get().whenComplete((result, ex) -> {
    inFlight.remove(key, leader);
    if (ex != null) leader.completeExceptionally(ex);
    else             leader.complete(result);
});
```

When the caller's async work eventually finishes (in whatever thread or thread pool it uses), the `whenComplete` callback runs. It removes the key and completes the shared `leader` future, which unblocks or notifies all followers.

If `work.get()` throws synchronously — for example, if the work supplier itself has a bug — the exception is caught, the key is removed, and the shared future is completed exceptionally. This ensures callers always receive a future that eventually resolves; it never hangs indefinitely.

---

## The Spring AOP layer

This section explains how `@Coalesce` integrates with Spring Boot, using the AOP concepts defined in the Prerequisites section.

### How `@Coalesce` wires up automatically

When Spring Boot starts and finds relay-java on the classpath, it loads `RelayAutoConfiguration` via the `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` file. `RelayAutoConfiguration` registers a `CoalesceAspect` bean and enables Spring AOP. Spring's AOP infrastructure then scans all beans for `@Coalesce`-annotated methods and wraps those beans in proxies.

From that point on, every call to a `@Coalesce`-annotated method routes through:

```
Caller → Spring proxy → CoalesceAspect.around() → RelayGroup.execute() → real method
```

`CoalesceAspect.around()` is the around advice method. It receives:
- A `ProceedingJoinPoint` — an object that can call through to the real method via `pjp.proceed()`
- A `Coalesce` annotation instance — containing the `key` attribute value for this specific annotation

The advice builds the deduplication key, calls `RelayGroup.execute()` with that key and the real method wrapped in a `Callable`, and returns the result to the caller.

### Key construction

`CoalesceAspect.buildKey()` builds the final deduplication key:

```
compositeKey = method.toGenericString() + "::" + evaluatedSpelValue
```

`method.toGenericString()` returns the full method signature including class name, return type, method name, and parameter types. For example:

```
public java.lang.String com.example.UserService.fetchProfile(java.lang.String)
```

This signature prefix is critical. Without it, calling `fetchProfile("42")` and `fetchOrder("42")` with the same `@Coalesce(key = "#id")` annotation would produce the same raw key `"42"` and incorrectly share a single execution across two different methods.

With the prefix, the composite keys are distinct strings:

```
public java.lang.String com.example.UserService.fetchProfile(java.lang.String)::42
public java.lang.String com.example.OrderService.fetchOrder(java.lang.String)::42
```

No collision.

### SpEL expression caching

Before SpEL can evaluate an expression like `"#tenantId + ':' + #resourceId"`, it must parse it into an AST. Parsing involves scanning the expression string character by character and building a tree structure — it is measurably slower than evaluation. Doing it on every method call would add unnecessary overhead.

`CoalesceAspect` caches parsed `Expression` objects:

```java
expressionCache.computeIfAbsent(
    methodId + "::" + coalesce.key(),
    k -> parser.parseExpression(coalesce.key())
);
```

The cache key is `methodSignature + "::" + spelExpressionString`, which is unique per distinct method-expression combination. Parsing happens exactly once per unique combination, on the first call. Every subsequent call reuses the already-parsed expression and only runs the evaluation step, which is fast.

### The `WrappedThrowable` technique

`RelayGroup.execute()` accepts a `Callable<V>`, and `Callable.call()` is declared to `throws Exception`. This means the `Callable` can propagate exceptions that are subclasses of `Exception`.

However, `ProceedingJoinPoint.proceed()` — the Spring AOP method that calls through to the real method — is declared to `throws Throwable`. `Throwable` is the root of Java's entire exception and error hierarchy. It includes:

- `Exception` (and its subclasses) — expected failure conditions
- `Error` (and its subclasses such as `OutOfMemoryError`, `StackOverflowError`) — serious JVM-level problems

A `Callable` cannot propagate an `Error` without wrapping it, because `Error` is not a subclass of `Exception`.

`CoalesceAspect` solves this with a private inner class:

```java
private static final class WrappedThrowable extends RuntimeException {
    WrappedThrowable(Throwable cause) { super(cause); }
}
```

`WrappedThrowable extends RuntimeException`, which is a subclass of `Exception`, so it can pass through the `Callable` boundary. Inside the `Callable`, any `Throwable` — whether an `Exception` or an `Error` — is caught and wrapped:

```java
return group.execute(compositeKey, () -> {
    try {
        return pjp.proceed();
    } catch (Throwable t) {
        throw new WrappedThrowable(t); // wraps as RuntimeException; legal in Callable
    }
});
```

Immediately after `execute()` returns or throws, the aspect catches `WrappedThrowable` and re-throws the original cause:

```java
} catch (WrappedThrowable e) {
    throw e.getCause(); // restore the original Throwable type exactly
}
```

`WrappedThrowable` is a private inner class — it never appears in a stack trace visible to a caller. The caller always sees the original exception from the annotated method.

---

## Why `CountDownLatch` in concurrent tests

The concurrent tests must verify that many threads calling the same key simultaneously are coalesced into one execution. This requires the threads to actually call `execute()` concurrently — not one after another.

Simply starting threads in a loop does not guarantee true concurrency. Each thread starts and may run for a while before the next thread is even created. The first thread could finish before the last one starts, making every call a separate non-overlapping execution with no coalescing to measure.

**`CountDownLatch`** (`java.util.concurrent.CountDownLatch`) is a synchronization primitive. It is initialized with a count. Threads call `countDown()` to decrement the count. Threads call `await()` to block until the count reaches zero.

The tests use two latches:

```java
CountDownLatch ready = new CountDownLatch(N); // N = number of test threads
CountDownLatch start = new CountDownLatch(1);

// In each of the N threads:
ready.countDown(); // "I have started and am waiting at the gate"
start.await();     // block here; do not proceed until the main thread releases us
group.execute(...);// all N threads arrive here simultaneously after start.countDown()
```

The main test thread waits until all N threads have called `ready.countDown()`, confirming all are alive and waiting. Then it calls `start.countDown()`, setting the latch to zero. All N threads unblock simultaneously and call `execute()`.

The work passed to `execute()` sleeps for 80 milliseconds. This keeps the in-flight window open long enough for all N threads to arrive and observe the same in-flight future, even on a slow or busy machine.

The test assertions use `≤ 3` rather than `= 1` because thread scheduling is not perfectly deterministic. On a very slow machine or under heavy load, the leader may complete and have the key removed from the map before a few late-arriving threads call `execute()`. Those threads see an empty map and start a second execution (wave 2). The `≤ 3` bound tolerates up to two additional waves while still catching real bugs such as "every thread starts its own execution" — which would produce a count of N.
