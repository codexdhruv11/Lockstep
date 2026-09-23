# lockstep

A load testing tool that answers one question: **when your app slows down, is the cause the app,
the database, or the cache?**

It drives HTTP, database and Redis load from one process on **one shared clock**, records every
layer's latencies into the same time buckets, and compares them bucket by bucket. Instead of one
latency curve that hides where the time went, you get three that line up — and a report that says
which bucket a storage layer spiked in, and whether the application felt it.

A Java 21 reimplementation of [reference]((the reference implementation)) (Go). Backend and
CLI only, no web UI. Plain Java — no frameworks, and no external load-generator binary.

```
$ lockstep run -c config.yaml

duration 16s · bucket 1s · concurrency 40 · ramp 8s

RUNNER  REQUESTS  SUCCESS  RATE    MEAN    P50     P95    P99    MAX    STATUS
http    479       100.0%   29.9/s  228ms   405ms   409ms  413ms  415ms  200×479
db      64        100.0%   4.0/s   210ms   208ms   251ms  310ms  312ms

capacity: strain starts around ~40 users (at 00:09, p99 crossed 72.7ms against a 22.7ms baseline)
  re-test at concurrency 60
  users are estimated from concurrency, not measured — one worker is not one user

correlated spikes
TIME   RUNNER  APP_P99  STORAGE_P99  NOTE
00:01  db      16.8ms   252ms        db-only (app not affected yet)
00:02  db      17.7ms   209ms        db-only (app not affected yet)

report written to report.html
percentiles accurate to ~1.0% (histogram precision)
```

## Try it in three commands

No database, no target, nothing to set up:

```sh
mvn clean package                                   # builds target/lockstep.jar
java -jar target/lockstep.jar demo-server &      # a small API on :8080
java -jar target/lockstep.jar run -c examples/scenario-login.yaml
```

That runs a two-step login journey against the demo server and writes `report.html`. Open it.

## What it does

### Three runners, one clock

| Runner | How |
|---|---|
| **HTTP** | `java.net.http.HttpClient`, paced in-process |
| **DB** | JDBC + HikariCP — Postgres, MySQL, SQLite |
| **Redis** | Lettuce, one multiplexed connection |

Every runner shares one start instant and one bucket width. That is the whole basis of the tool:
an HTTP request and a database query that happened in the same second land in the same bucket, so
their latencies can be compared rather than guessed at.

### Spike correlation

For each bucket where the database or Redis crossed its p99 threshold:

- **masked** — storage was slow, the app was not. The store is straining but users have not felt
  it yet. This is the most useful thing the tool prints.
- **correlated** — both were slow. A verdict names the slower side (`HTTP`, `DB`, `REDIS`, or
  `EVEN` when the gap is smaller than the measurement precision).

Buckets where **only** HTTP was slow are deliberately not flagged. A slow endpoint over idle data
stores is an application problem, and this tool's claim is about storage.

### Capacity finder

Maps each bucket to an estimated active-user count and reports the first **sustained** strain —
p99 above twice the healthy baseline for three consecutive buckets, so a GC pause is not mistaken
for a capacity limit. It prints the assumption alongside the number, every time.

### Scenarios

Multi-step user journeys with variable capture:

```yaml
scenario:
  - name: login-flow
    steps:
      - method: POST
        url: http://localhost:8080/api/login
        body: '{"user":"alice"}'
        extract:
          token: $.token
      - method: GET
        url: http://localhost:8080/api/me
        headers:
          Authorization: Bearer {{token}}
```

One journey is one recorded operation, so the latency is what a user waits through. A failed step
ends its journey — continuing would fire later requests with an uncaptured token and add load that
describes the tool rather than the target. Per-step figures show which request in the flow is slow.

### Reports and CI

```sh
lockstep run -c config.yaml --json results.json   # machine-readable, versioned schema
lockstep compare -b baseline.json -c current.json --fail-on 100ms
```

`compare` exits **0** when nothing regressed, **1** on a regression, **2** when the inputs could
not be used. A runner regresses when its p99 grows by more than the budget in absolute terms — a
budget is how much extra waiting a user tolerates, so 5ms→40ms passes and 900ms→1.2s does not.

## Commands

```
lockstep run [-c config.yaml] [--duration 30s] [--ramp 10s] [--concurrency 50]
                [--http-threshold 150ms] [--db-threshold 250ms] [--redis-threshold 80ms]
                [--json results.json] [--report report.html] [--no-report] [--buckets]
                [--no-progress]
lockstep compare -b baseline.json -c current.json [--fail-on 100ms] [--report compare.html]
lockstep demo-server [--port 8080]
lockstep seed-db --conn postgres://user:pw@localhost:5432/db [--driver postgres] [-n 1000000]
lockstep version
```

Results go to **stdout**; progress and errors go to **stderr**, so `run > results.txt` captures
the tables without the chatter.

## Configuration

Config files are compatible with the reference tool's — the same YAML runs under both. See
`examples/`: `light.yaml`, `heavy.yaml`, `scenario-login.yaml`, `scenarios-weighted.yaml`.

```yaml
duration: 15s
bucket_width: 1s
ramp: 3s              # grow from 0 to full rate over this window
concurrency: 10       # workers, and the queue depth behind them

http:
  rate: 10            # arrivals per second, not a serial request stream
  target:
    method: POST
    url: http://localhost:8080/api/orders
    body: '{"customer": 42}'
    header:
      content-type: [application/json]

db:
  rate: 5
  target:
    driver: postgres
    conn: postgres://user:pw@localhost:5432/mydb?sslmode=disable
    queries:
      - query: SELECT count(*) FROM orders
        weight: 20
        type: read          # authoritative; untyped falls back to reading the SQL
      - query: INSERT INTO orders (customer, amount) VALUES ('load', 1)
        weight: 25
        type: write

redis:
  rate: 20
  target:
    addr: localhost:6379
    queries:
      - query: PING
        weight: 1
```

Unknown keys are **rejected** at load time, naming the offending field. A typo should fail before
the run, not silently change what is tested.

Connection strings may be Go-style (`postgres://user:pw@host/db`) or JDBC (`jdbc:postgresql://…`).

## What it measures, honestly

These are design decisions, not caveats buried in a footnote:

- **Latency is measured from the instant an operation was *scheduled*,** not from when a worker
  picked it up. Time spent queued is part of the number, because that queue is what grows when a
  system saturates. Service time is reported alongside it, so the gap — the queue delay — is
  visible. (The reference measures only service time, which looks *best* exactly when the target
  is worst.)
- **The arrival rate does not slow down when the target does.** Real users keep arriving. When
  every worker is busy the work is **shed and counted**, never silently dropped or queued forever.
- **Percentiles are accurate to ~1%**, printed with every run. That is a deliberate trade:
  3-digit histograms cost 320KB each against 46KB, which is 115MB versus 16MB for a 3-minute run.
- **Anything the run failed to deliver is printed.** Scheduled vs executed, shed, fired late. A
  table showing 78/s when 100/s was configured would otherwise invite you to believe the target
  handled 100.
- **Memory is bounded by bucket count, not request count.** Samples fold into histograms and are
  dropped; 10,000 and 10,000,000 observations cost the same.

## Known limitations

- **JVM warm-up distorts the first bucket** of short runs. The first operations pay for JIT
  compilation, which can show up as a spike that describes lockstep rather than your system.
  There is no `--warmup` flag yet.
- Load comes from **one process on one machine**. No distributed generation.
- No WebSocket or streaming load, no browser, no cookie jar for scenarios (token capture only).
- `compare` diffs p99 only, and does not compare per-step figures or throughput.
- Thresholds are absolute cut-offs; nothing adapts to what is normal for your target.

## Build

Requires **Java 21** (virtual threads) and Maven.

```sh
mvn clean package            # target/lockstep.jar
mvn clean verify             # 217 tests; Postgres and Redis tests need Docker
```

Tests that need Docker skip themselves by name when it is unavailable, rather than passing
silently.

## Project layout

```
core/       the shared clock, the pacer, the bounded work queue
stats/      per-bucket histograms, merged percentiles
runner/     http, db, redis
scenario/   multi-step journeys, capture, interpolation
analysis/   spike correlation, capacity finder
report/     CLI tables, JSON export, HTML report
compare/    run-to-run diff and the CI gate
demo/       demo server and database seeder
```

`docs/PITFALLS.md` explains the measurement traps this tool is built to avoid, including two
defects in the reference implementation that are deliberately not reproduced here.
