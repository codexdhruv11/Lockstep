# lockstep

A load testing tool that answers one question: **when your app slows down, is the cause the app,
the database, or the cache?**

It drives HTTP, database and Redis load from one process on **one shared clock**, records every
layer's latencies into the same time buckets, and compares them bucket by bucket. Instead of one
latency curve that hides where the time went, you get three that line up — and a report that says
which bucket a storage layer spiked in, and whether the application felt it.

Java 21, backend and CLI only, no web UI. Plain Java — no frameworks, and no external
load-generator binary.

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

## Install

**Requires Java 21 or later.** Virtual threads are load-bearing, so 17 will not do.

### Download a release

Grab `lockstep-<version>.jar` from the releases page, plus the `lockstep` launcher if you want it
on your PATH:

```sh
java -jar lockstep-v0.4.0.jar --help

# or, with the launcher beside the jar:
./lockstep --help
```

One self-contained jar. Nothing is installed on the machine under test, there is no agent and no
daemon. Check the download against the published `SHA256SUMS`.

### Or build it

```sh
mvn clean package -DskipTests     # builds target/lockstep.jar
./bin/lockstep --help
```

Running the tests needs Docker, because the integration and oracle suites start real Postgres and
Redis containers. `mvn test -DexcludedGroups=container` skips those and takes about a minute.

## Try it in three commands

No database, no target, nothing to set up:

```sh
mvn clean package                                   # builds target/lockstep.jar
java -jar target/lockstep.jar demo-server &      # a small API on :8080
java -jar target/lockstep.jar run -c examples/scenario-login.yaml
```

That runs a two-step login journey against the demo server and writes `report.html`. Open it.

## The workflow on a real target

```sh
# 1. find the rate at which it stops keeping up, rather than guessing one
lockstep find-capacity -c config.yaml --step=15s

# 2. measure at a rate you chose, watching the target's own database
lockstep run -c config.yaml \
    --observe-db "postgres://user:pw@host:5432/appdb" \
    --warmup 10s --json run.json

# 3. gate CI on it: non-zero exit only on a change larger than the runs' own noise
lockstep compare baseline.json run.json --budget 300ms

# 4. ask when it will break. This one WRITES, so point it at a throwaway database
lockstep growth-curve -c config.yaml --table signals \
    --seed "INSERT INTO signals (payload) SELECT repeat('x',200) FROM generate_series(1,{{n}})" \
    --steps 25000,50000,100000,200000 --budget 500ms --rows-per-day 2000 --allow-writes
```

Three flags do most of the work of not fooling yourself:

- `--warmup 10s` — the JVM compiles itself during a run's opening seconds, and those buckets
  should not count as findings.
- `--observe-db` — reads the target's own statistics views, so the report can say how many
  statements it ran per request and what bounded it. This is what catches an endpoint that is
  fast for the wrong reason.
- `--arrivals poisson` — constant pacing understates queueing at the same mean rate, because real
  traffic arrives in clumps.

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

### Finding the capacity

`find-capacity` runs the workload at a staircase of rates and reports the one it stops keeping up
at, instead of you running the tool by hand at 20/s, then 250/s, then 120/s, then 60/s:

```
$ lockstep find-capacity -c dashboard.yaml --step 6s

capacity search
RATE  DELIVERED        P99     SLOWEST  VERDICT
30/s  30.0/s (100.0%)  83.4ms  db       held
60/s  60.0/s (100.0%)  2.4s    db       strained — p99 blew up
45/s  45.0/s (100.0%)  734ms   db       strained — p99 blew up
38/s  38.0/s (100.0%)  310ms   db       strained — p99 blew up
34/s  34.0/s (100.0%)  169ms   db       held
36/s  36.0/s (100.0%)  204ms   db       held
37/s  37.0/s (100.0%)  209ms   db       held

capacity: sustains 37/s, strains at 38/s
```

It starts at the config's own rate and doubles until something gives — or **halves** if the
configured rate is already too much, which is the state you are usually in when you reach for
this. Once the answer is bracketed it bisects, because "sustains 60/s, strains at 120/s" is not
an answer.

**Two independent ways to fail**, and it says which one fired: the generator could not hand over
the load (work was shed because every worker was busy), or the rate held while p99 blew past the
baseline. Checking only the first misses a system that keeps accepting requests and takes four
seconds over each; checking only the second misses one that keeps latency flat by refusing work.

**Latency is judged against the first rate that held**, fixed for the whole search. A baseline
that followed the search accepts a further 3× at every step, so it converges on a rate whose
latency is many times the healthy one and calls it sustained — and makes the same rate get
different verdicts depending on when it was tried.

Each step holds one rate rather than ramping through it: a continuous ramp holds no rate long
enough to measure, so the level at which the curve bends is not the level that caused it. One
unmeasured step runs first so the JVM is compiled before anything is believed.

A scenario-only config is **refused**: a scenario's arrival rate comes from concurrency, so
scaling it would change the worker pool at the same time and the answer would not mean what it
says.

### Capacity finder

Maps each bucket to an estimated active-user count and reports the first **sustained** strain —
p99 above twice the healthy baseline for three consecutive buckets, so a GC pause is not mistaken
for a capacity limit. It prints the assumption alongside the number, every time.

### Many endpoints in one run

`targets:` drives a weighted list of endpoints, so a page that calls five things is one run
rather than five:

```yaml
http:
  rate: 40
  targets:
    - method: GET
      url: http://localhost:8080/api/products
      weight: 50
    - method: POST
      url: http://localhost:8080/api/orders
      body: '{"customer": 42}'
      weight: 30
    - method: GET
      url: http://localhost:8080/api/slow
      weight: 20
```

```
http targets
HTTP TARGETS          CALLS  SHARE  ERR  MEAN    P50    P95     P99
t3 GET /api/slow      62     73.2%  0    267ms   255ms  298ms   300ms
t1 GET /api/products  196    15.3%  0    17.7ms  7.7ms  52.2ms  57.7ms
t2 POST /api/orders   118    11.0%  0    21.1ms  8.8ms  53.7ms  56.6ms
```

`target:` (one endpoint) still works exactly as before, so an existing single-target config keeps
working unchanged.

### Per-query breakdown

A database or Redis mix reports one row per statement as well as one row for the runner, so "the
storage layer is slow" becomes "this one is 96% of it" in a single run:

```
db queries
QUERY                                                            CALLS  SHARE  ERR  MEAN   P50     P95     P99
q1 WITH stats AS ( SELECT COUNT(*) AS total, COUNT(*) FILTER …  350    96.2%  0    103ms  81.8ms  243ms   386ms
q2 SELECT o.id, o.customer, o.amount, o.status FROM orders o …  238    2.6%   0    4.1ms  1.3ms   13.9ms  55.1ms
q3 SELECT id, name, region FROM customers WHERE active = true…  132    1.2%   0    3.4ms  1.4ms   12.1ms  43.8ms
```

**SHARE** is the column to read first: share of total database time, calls × mean. A 2ms statement
run a thousand times a second owns more of the machine than a 200ms one run twice a minute, and no
latency column can tell you that.

The latency columns here are each query's **own cost**. Queue delay belongs to the run, not to a
query — under saturation every statement waits in the same line, so total latency comes out nearly
identical for all of them and says nothing about which one to fix. The runner's row above keeps
the full wait, queue delay included.

Redis command mixes get the same table. Labels are derived from the statement, not configured: the reference tool rejects unknown YAML keys, so
a `name:` field would stop the same config running under both. The leading `qN` is the config
position, so two entries with identical SQL stay two rows.

### The server's own evidence

When a statement's own p99 crosses its runner's threshold, the tool goes and asks the server what
it thinks happened.

#### Redis: the slowlog

```
redis commands
REDIS COMMANDS           CALLS  SHARE  ERR  MEAN    P50     P95    P99    MAX
q3 KEYS bulk:1*          59     46.7%  0    132ms   107ms   287ms  304ms  304ms
q1 GET bulk:42           137    27.2%  0    33.2ms  3.2ms   204ms  245ms  285ms
q2 SET sess:loadtest ok  104    26.2%  0    42.2ms  22.2ms  169ms  250ms  259ms

redis slowlog
SERVER_TIME  COMMAND       CLIENT
45.3ms       KEYS bulk:1*  172.17.0.1:50568
48.1ms       KEYS bulk:1*  172.17.0.1:50568
...
```

Read those two together. `GET` has a p50 of 3.2ms and a p99 of 245ms, and **no `GET` appears in
the slowlog at all** — the server never spent long on one. Redis executes commands on a single
thread, so the `GET`s were queued behind the `KEYS`, which the server itself timed at ~45ms each.
The client-side number says "GET is sometimes slow"; the server's says "GET is never slow, it is
waiting", and only the second one tells you what to change.

- **Nothing is reset.** `SLOWLOG RESET` would give a clean log and destroy whatever the operator
  of a shared server was keeping there. The newest entry id is noted before the run and only
  entries above it are reported — ids are monotonic, so this needs no cooperation and leaves the
  server as it was found.
- **The log is server-wide**, and the report says so: another client's slow commands during the
  same window appear too.
- **An empty log says why**, including the server's own `slowlog-log-slower-than`. "Nothing was
  logged" and "the log could not be read" are different answers, and neither is "Redis was fine".

#### Postgres, MySQL, SQLite: the execution plan

When a query's own p99 crosses `--db-threshold`, the tool takes its execution plan and puts it in
the report:

```
query plans (p99 above 100ms; taken after the run, with the load off)

q1 WITH stats AS ( SELECT COUNT(*) AS total, COUNT(*) FILTER …
  EXPLAIN (ANALYZE, BUFFERS)
  Aggregate  (actual time=101.884..101.885 rows=1 loops=1)
    ->  Seq Scan on orders  (actual rows=69720 loops=1)
          Buffers: shared hit=2143 read=1287
```

`Sort Method: external sort  Disk: 3016kB` is an answer; "the database was slow" is not.

- **Taken after the run**, with the load off. Explaining mid-run would add work the report then
  describes as the target's behaviour, and would need a free connection at exactly the moment the
  pool is saturated. The cost is stated in the output: the plan reproduces the *shape* — scans,
  row counts, a sort spilling to disk — but not lock waits or a cache that was cold only because
  the run was saturating it.
- **A write is never re-executed.** `EXPLAIN ANALYZE` runs the statement, so writes get the
  planner's estimate only, labelled as an estimate. A load-test tool may not quietly write to the
  target after the run is over.
- **Only the queries that crossed the threshold**, compared against each query's own cost rather
  than its total latency — under saturation every statement waits in the same line, so ranking on
  that would explain the whole config every time the target got busy.
- Postgres gets `EXPLAIN (ANALYZE, BUFFERS)`, MySQL `EXPLAIN ANALYZE` (8.0.18+), SQLite
  `EXPLAIN QUERY PLAN`. `--no-explain` turns it off.

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

### The generator audits itself

Every load tester blames the target. This one records its own JVM pauses during the run and checks
whether any of them line up with the spikes it just reported:

```
generator self-audit
TIME   PAUSED  EVENT          NOTE
00:07  340ms   GCPhasePause   overlaps a reported spike
00:11  95ms    GCPhasePause
  this process was paused for 435ms in total, across 2 GC pauses
  1 of these falls in a bucket reported as a storage spike above - that latency was at
  least partly this process, not the target
```

A 340ms stop-the-world pause in the generator is indistinguishable, from the outside, from 340ms
of slowness in the target: the request was scheduled, the clock ran, the response came back late.
The difference is that one of them is the measuring instrument. Flight Recorder is running
anyway — reading it costs nothing and turns an invisible error into a labelled one.

On a clean run it says so explicitly rather than printing nothing:

```
generator self-audit
  no JVM pause above 10ms in this run; the latencies above are the target's, not this process's
```

That distinction matters: silence would be indistinguishable from the recording having failed, so
an unreadable recording reports *unavailable* with the reason instead of looking clean.

`--no-self-audit` turns it off.

### Arrivals: constant or Poisson

```sh
lockstep run -c config.yaml --arrivals poisson --arrival-seed 42
```

A constant-rate pacer fires at exactly `1/rate` intervals. Real traffic does not: arrivals are a
Poisson process, with exponentially distributed gaps. That difference is not cosmetic — evenly
spaced arrivals into a queue produce materially less queueing delay than bursty ones at the same
mean rate, so **a constant-rate load test systematically under-reports the tail.**

Measured against the demo server's 250ms endpoint, 40/s mean, 12 workers (about 83% utilisation),
20s, warm-up excluded:

| arrivals | delivered | p50 | p99 |
|---|---:|---:|---:|
| constant | 800 | 296ms | **302ms** |
| poisson, seed 1234 | 760 | 333ms | 642ms |
| poisson, seed 7 | 824 | 549ms | 793ms |
| poisson, seed 99 | 835 | 822ms | 1.2s |
| poisson, seed 42 | 879 | 1.2s | **2.0s** |

Constant arrivals produce a p50 and p99 six milliseconds apart — the distribution is nearly flat,
because every request meets the same queue. Every Poisson seed is worse, by between two and six
times at the tail.

It is not simply extra load: seed 1234 delivered **fewer** requests than the constant run (760 vs
800) and still doubled the p99. What the tail responds to is burstiness, and a constant pacer has
none by construction.

`--arrival-seed` makes a Poisson schedule reproducible, so a regression can be re-measured against
the same arrival pattern rather than a new random one.

### The distribution, not just the percentiles

```sh
lockstep run -c config.yaml --distribution
```

```
LATENCY   COUNT  SHARE
<= 5ms    20     5.0%   ████
<= 10ms   215    53.8%  ████████████████████████████████████████
<= 25ms   24     6.0%   ████
<= 50ms   30     7.5%   ██████
<= 100ms  53     13.3%  ██████████
<= 500ms  62     15.5%  ████████████
```

That run's p50 is 9.1ms and its p90 is 254ms. Read as a curve, that looks like a long tail. It
isn't — it is **two populations**, and the histogram is the only view that shows it. Buckets are
fixed rather than derived from the data, so two runs can be compared.

### What failed, not just how many

```
failures
RUNNER  COUNT  FAILURE
http    24     HTTP 404
db      3      SQLException: canceling statement due to statement timeout
```

The distinct-message list is capped and the rest pooled, so a target emitting a unique message per
failure cannot turn the error table into a memory leak.

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
lockstep find-capacity [-c config.yaml] [--step 10s] [--max-steps 9] [--concurrency 50]
lockstep run [-c config.yaml] [--duration 30s] [--ramp 10s] [--concurrency 50]
                [--warmup 5s] [--no-explain] [--arrivals constant|poisson] [--arrival-seed N]
                [--no-self-audit]
                [--http-threshold 150ms] [--db-threshold 250ms] [--redis-threshold 80ms]
                [--json results.json] [--report report.html] [--no-report] [--buckets]
                [--no-progress]
lockstep compare -b baseline.json -c current.json [--fail-on 100ms] [--report compare.html]
                    [--no-fail-on-findings]
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
    pool_size: 10       # optional; defaults to the run's concurrency
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

### Warm-up

A run's opening moments measure the JVM compiling itself, not your system — latencies there can
be an order of magnitude above the steady state. `--warmup 5s` excludes that window from the
findings:

```sh
lockstep run -c config.yaml --warmup 5s
```

Those buckets are still measured and still printed. They are labelled and left out of the spike
list and the capacity baseline, because deleting data to make a report look tidy is the opposite
of what this tool is for.

## Known limitations

- Load comes from **one process on one machine**. No distributed generation.
- No WebSocket or streaming load, no browser, no cookie jar for scenarios (token capture only).
- `compare` diffs p50 and p99 and flags diverging throughput, but does not compare per-step or
  per-query figures.
- Thresholds are absolute cut-offs; nothing adapts to what is normal for your target.
- `find-capacity` scales http, db and redis rates; a scenario-only config is refused rather than
  answered wrongly.
- No bytes-in/bytes-out accounting. Counting response payloads needs a custom body subscriber on
  the measurement hot path, and that is a change worth measuring rather than guessing at.
- `CapacityFinder` cannot name a strain *point* on a run that was saturated from the first
  bucket — there is no healthy stretch to measure one against. It reports that case as "already
  over capacity" rather than as "no strain", which is what it used to do.
- Without `--warmup`, a short run's first bucket can still produce a finding about the JVM.

## Build

Requires **Java 21** (virtual threads) and Maven.

```sh
mvn clean package            # target/lockstep.jar
mvn clean verify             # 332 tests; Postgres and Redis tests need Docker
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

`docs/PITFALLS.md` explains the measurement traps this tool is built to avoid.
