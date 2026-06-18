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
