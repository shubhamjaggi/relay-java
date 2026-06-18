# Contributing to relay-java

Thanks for taking the time to contribute!

## Prerequisites

- Java 21+
- No Maven install required — the project ships with a Maven wrapper

## Build and test locally

```bash
# Linux / macOS
./mvnw verify

# Windows
.\mvnw.cmd verify
```

The first run downloads Maven 3.9.6 into `~/.m2/wrapper/` automatically. `verify` runs
tests and generates a coverage report at `target/site/jacoco/index.html`.

To build without running tests:
```bash
./mvnw package -DskipTests
```

## How it's tested

### CI workflow

Every push and pull request to `main` runs `.github/workflows/ci.yml`, which:

1. Checks out the code and sets up Java 21
2. Runs `./mvnw verify` — compiles, runs all tests, and generates a Jacoco coverage report
3. Uploads test results as a workflow artifact
4. Uploads coverage to Codecov

The build fails if any test fails. There is no deployment step; this is a library.

### Test classes and what they prove

**`RelayGroupTest`** — the core `RelayGroup` contract

| Test | Claim verified |
|---|---|
| `execute_singleCaller_returnsExpectedValue` | A single caller receives the correct return value |
| `execute_sequentialCalls_runEachTime` | Sequential calls (no overlap) each run the work independently |
| `execute_differentKeys_runIndependently` | Different keys never share an execution |
| `execute_concurrentCallers_sameKeySuppressesDuplicates` | 50 concurrent callers for the same key produce ≤3 actual executions |
| `execute_leaderFails_followersReceiveSameException` | When the leader throws, every follower receives the same exception |
| `execute_afterFailure_nextCallStartsFresh` | After a failure, the next call starts a fresh execution — no poisoned state |
| `inFlightCount_isZeroWhenIdle` | `inFlightCount()` returns 0 when nothing is running |
| `inFlightCount_tracksActiveWork` | `inFlightCount()` reflects the number of keys currently in progress |
| `executeAsync_concurrentCallers_sameKeySuppressesDuplicates` | Same coalescing guarantee for the non-blocking async path |
| `executeAsync_sequentialCalls_runEachTime` | Sequential async calls each run independently |
| `executeAsync_differentKeys_runIndependently` | Different keys run independently on the async path |
| `executeAsync_leaderFails_followersReceiveException` | Async failure propagates to all followers' futures |
| `executeAsync_supplierThrowsSynchronously_futureCompletesExceptionally` | A supplier that throws before returning a future causes the future to complete exceptionally |
| `execute_nullReturnValue_isAllowed` | `null` is a valid return value; the group does not treat it as a missing entry |

**`CoalesceAspectTest`** — Spring AOP wiring for `@Coalesce`

| Test | Claim verified |
|---|---|
| Concurrent callers coalesced | 30 threads on the same `@Coalesce` method produce ≤3 actual executions |
| SpEL key resolution | `key = "#id"` evaluates to the argument value and is used as the deduplication key |
| Per-method namespace | Two different methods annotated with the same SpEL expression do not share a key |
| `selfCall_bypasses_proxy_noCoalescing` | A call via `this.method()` bypasses the proxy; `@Coalesce` has no effect — 30 callers → 30 executions |
| `privateMethod_coalesceAnnotation_isIgnored` | `@Coalesce` on a `private` method has no effect — 30 callers → 30 executions |

**`CacheableCoalesceIntegrationTest`** — `@Cacheable` and `@Coalesce` together

| Test | Claim verified |
|---|---|
| `coldCacheMiss_concurrentCallers_coalesceToFewExecutions` | On a cold cache miss, 30 concurrent callers are coalesced; all receive the correct result; the result is stored in the cache |
| `warmCacheHit_subsequentCall_doesNotExecuteMethodBody` | After the first call populates the cache, a second call is served from the cache without executing the method body |
| `warmCacheHit_differentKey_stillExecutesMethodBody` | A cache hit for one key does not affect a different key — the different key still runs the method body |

## Submitting a bug report

Use the [Bug Report](.github/ISSUE_TEMPLATE/bug_report.yml) template. Please include:

- Java version (`java -version`)
- Spring Boot version (if applicable)
- A minimal reproducer — a test method is ideal

## Submitting a feature request

Open a [Feature Request](.github/ISSUE_TEMPLATE/feature_request.yml) before writing code, so we can align on the design first.

## Pull requests

1. Fork the repository
2. Create a branch: `git checkout -b feature/your-feature`
3. Make your changes with tests
4. Ensure all tests pass: `./mvnw verify`
5. Push and open a PR against `main`

### What makes a good PR

- **Tests first.** If you're fixing a bug, add a test that fails before your fix and passes after.
- **One concern per PR.** Separate refactors from features; separate features from bug fixes.
- **Keep the public API small.** Prefer adding internal helpers over expanding the public surface.

### Commit messages

Use the imperative mood and keep the subject line under 72 characters:

```
Add @CoalesceAsync for CompletableFuture-returning methods
Fix: follower thread not interrupted when leader is cancelled
```

## Code style

- Standard Java conventions — no custom formatter required
- Javadoc on all public types and methods
- No comments explaining *what* the code does; only comments explaining *why* when it's non-obvious

## Roadmap

Items under consideration for future releases:

- `@CoalesceAsync` — native support for methods returning `CompletableFuture`
- Micrometer metrics — counters for total calls, coalesced calls, suppression ratio
- Virtual thread compatibility (Project Loom)
- Kotlin coroutine support

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
