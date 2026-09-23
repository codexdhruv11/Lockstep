# lockstep

A Java 21 reimplementation of [reference]((the reference implementation)) — a load
testing tool that drives HTTP, database and Redis load on one clock, buckets every layer's
latencies onto the same timeline, and reports **which layer** caused a slowdown.

Backend + CLI only. No web UI.

## Who does what

| Role | Who |
|---|---|
| Writes the code | the author (or whichever coding CLI is driving) |
| Reviews the code | the author (this repo's reviewer) |
| Owns the decisions | Dhruv |

the author: read `AGENT_INSTRUCTIONS.md` first. It is the contract for how work is delivered.

## Layout

```
docs/SPEC.md            what the tool must do (feature parity list)
docs/ARCHITECTURE.md    package layout + what each class owns
docs/BUILD_ORDER.md     16 phases, each with acceptance criteria
docs/PITFALLS.md        traps in the Go original — do NOT copy them
docs/REVIEW_CHECKLIST.md what the reviewer checks before a phase passes
PROGRESS.md             phase-by-phase status tracker (updated after each review)
reviews/                one file per completed review
examples/               example configs (mirrors ../reference/examples)
```

## Reference implementation

The Go original is cloned at `../reference`. It is a **reference, not a spec to transcribe**.
Read it for structure and edge cases; do not port it line by line. See `docs/PITFALLS.md`
for the parts that are wrong and must be done differently.

## Prerequisites

- Java 21 (installed: 21.0.10)
- Maven 3.6+ (installed: 3.6.3)
- `vegeta` binary on PATH — needed from Phase 6 onward
- Docker — needed for Testcontainers tests (Postgres, Redis)

## Build

```sh
mvn clean package
java -jar target/lockstep.jar run -c config.yaml
```
