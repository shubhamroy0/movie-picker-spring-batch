# Study plan — answering the questions and owning the theory

Two goals: (1) be able to answer every check question in this project's learning log *without looking*, and (2) be able to
explain the theory behind each answer well enough to handle a follow-up. This file gives you a method, a schedule, the theory
map, hands-on labs, and an **answer key** (Section 4). Try each question yourself first; the key is for checking, not reading.

---

## 1. The method: how to answer any "explain / what happens if" question

Use four beats, in 30–60 seconds:

1. **Answer first** — one sentence with the verdict ("the whole 3rd chunk rolls back").
2. **Mechanism** — *why*, naming the component (transaction, `ItemStream.update`, `RetryPolicy`…).
3. **Evidence from your project** — "in my project, `MovieSyncJobRestartTest` shows 60 + 83 reads = 143".
4. **Trade-off / limit** — what it costs or where it stops working (single JVM, same-transaction retry…).

If you can't do beat 2, you memorised a fact, not the theory: go to the theory map (Section 3).

---

## 2. Schedule (about 5 sessions of 60–90 min)

| Session | Do | Done when |
|---|---|---|
| **1 — The chunk model** | Read LEARNING_LOG Phase 1–2. Run the app (`mock`), `POST /api/jobs/sync`, open H2 console and query `batch_step_execution`. Answer Phase 1–2 questions aloud. Do labs L1–L2. | You can draw read→process→write→commit and say what `update()` saves |
| **2 — Failure handling** | Phase 3–4. Run demos B and C from the README. Answer Phase 3–4 questions. Labs L3–L4. | You can explain retry vs skip vs filter without hesitation |
| **3 — State: scheduling and restart** | Phase 5–6. Do the failure/restart demo (README demo A). Answer Phase 5–6 questions. Lab L5. | You can narrate "failed at page 40 of 100" end to end |
| **4 — Web, JPA, scaling** | Phase 7–8. Click through the Postman collection; read `SerializedUpsertWriter`. Answer Phase 7–8 questions. Labs L6–L7. | You can explain the partition race and its fix |
| **5 — Interview simulation** | `docs/INTERVIEW_PREP.md`: pitch ×3, Q1–Q65 cover-and-recite, scenarios S1–S4 out loud, Phase 9 questions. | You answer 80 % of Q&A without peeking |

Spaced repetition: re-do the Q&A you missed after 1 day, 3 days, 1 week.

---

## 3. Theory map (concept → where it lives → how to practise it)

| Theory | Key points to be able to say | In this repo | Practise |
|---|---|---|---|
| **Chunk-oriented processing** | transaction per chunk; read/process per item, write per chunk; commit interval trade-offs; counters (`read = filtered + written`) | `MovieSyncJobConfig`, `docs/LEARNING_LOG.md` Ph.2 | L1 |
| **Job model** | Job / JobInstance / JobExecution / StepExecution; identifying parameters define instance | `SyncJobService` | L5 |
| **ExecutionContext & ItemStream** | `open/update/close`; saved atomically with the chunk; step vs job context; promotion | `TmdbMovieReader`, `MovieUpsertWriter` | L2 |
| **Fault tolerance** | filter vs skip vs retry vs fail; skip limit; where retry applies in Batch 6; Framework 7 `RetryPolicy` | `faultTolerantFetchStep`, `ResilientTmdbClient` | L3 |
| **Transactions in Batch** | JPA tx manager must be passed in Batch 6; retry re-runs the write in the same tx; `REQUIRES_NEW` for audit rows | `RejectedRecordService`, `SerializedUpsertWriter` | L3, L7 |
| **Flow control** | exit status transitions, decider, tasklet vs chunk | `SyncJobFlowConfig` | L4 |
| **Restart** | same instance, new execution, completed steps skipped, idempotent writer | `MovieSyncJobRestartTest` | L5 |
| **Scheduling** | fixedDelay/fixedRate/cron, overlap, zombie executions, multi-instance locks | `SyncScheduler`, `StaleExecutionRecovery` | L5 |
| **JPA** | upsert strategies, business vs surrogate key, SEQUENCE + batching, `@Version`, N+1 (`@EntityGraph`, `@BatchSize`), `open-in-view`, `readOnly` | `MovieUpsertWriter`, `Movie`, `MovieService` | L6 |
| **REST design** | DTOs, Specifications, paging caps, ProblemDetail, 202/409 semantics | `web/*`, `MovieSpecifications` | L6 |
| **Scaling** | threaded step vs partitioning vs remote chunking vs parallel steps; disjoint partition keys; rate limits cap parallelism | `PartitionedFetchConfig` | L7 |
| **Testing** | real DB + real socket stub; per-context in-memory DB; repeat concurrency tests | `src/test/**` | L8 |

Reading list (official docs, verify against **Batch 6**, not older tutorials): Spring Batch reference — *Domain Language*, *Configuring a Step*
(chunk-oriented, fault tolerance), *Scaling and Parallel Processing*, *Job restart*; Spring Boot reference — *Batch*, *Task Execution and Scheduling*, *Actuator health*;
Spring Framework — *Resilience (retry)*, *Method validation*, *ProblemDetail*; Hibernate — *Batching*, *Fetching*.

---

## 3b. Hands-on labs ("break it on purpose")

| # | Lab | What you should observe |
|---|---|---|
| L1 | Change `moviepicker.sync.chunk-size` to 5 and 50 (env `MOVIEPICKER_SYNC_CHUNKSIZE`); run a sync; query `commit_count` in `batch_step_execution` | `commit_count = ceil(read / chunk)`; log "Chunk written" lines change |
| L2 | In H2: `select short_context from batch_step_execution_context`; decode the Base64 (or read it via `JobRepository` in a test) | Reader position + `writer.*` counters |
| L3 | Start with `MOVIEPICKER_MOCK_CORRUPTRECORDS=true`; then in `application.yml` set `sync.skip-limit: 1` | First: 2 skips and 2 rows in `REJECTED_RECORD`; second: the step FAILS on the 2nd bad record |
| L4 | Comment out `.listener(promotion)` in `MovieSyncJobConfig` and rerun the flow tests | `MovieSyncJobFlowTest` fails: counters never reach the decider (the bug we hit) |
| L5 | Demo A from the README (`MOVIEPICKER_MOCK_FAILONCEAT=popular:2`), then restart, then try to restart again | FAILED → COMPLETED on the same instance → `409` |
| L6 | Turn on `spring.jpa.show-sql` / `logging.level.org.hibernate.SQL=DEBUG`; call `/api/movies?size=20`; then remove `@BatchSize` from `Movie` | ~4 statements vs ~41 (N+1) |
| L7 | In `PartitionedFetchConfig`, pass the raw `writer` instead of `new SerializedUpsertWriter(...)`; run `MovieSyncJobPartitionedTest` | The job FAILS with unique-key / lock-timeout errors (the Phase 8 race) |
| L8 | Break one assertion on purpose in `RestApiTest` (e.g. expect 200 for an unknown movie) | Read the MockMvc failure output: it prints the actual ProblemDetail |

---

## 4. Answer key

> Try first. Where a Batch internal is not exercised by our code, the answer says so — in an interview, saying "I'd confirm
> that in the reference" beats bluffing.

### The one you asked about: "chunk size"

A **chunk** is a *batch of items processed inside one database transaction*. Chunk size = how many items per transaction.
Think of a cashier who stamps 20 forms, then files them together; if form 14 is smudged, the 20 are put back and redone — not just that one.

```
chunk size = 20, 143 items to read
chunk 1: read 20 → process 20 → write 20 → COMMIT (position saved)
chunk 2: …                                  COMMIT
…
chunk 8: read 3 (the rest) → write → COMMIT          => 8 commits  (ceil(143/20))
```
* **Bigger chunk** → fewer commits (faster), more memory, more work lost/redone on failure, longer locks.
* **Smaller chunk** → more commits (slower), finer restart granularity, less memory.
* It is also the **checkpoint**: the reader/writer position is saved at each commit, so a restart resumes at the last *completed chunk*.
* Not the same as the page size of the API (also 20 here, by coincidence), and not the same as `jdbc.batch_size` (how many SQL statements Hibernate sends per JDBC batch — we set both to 20 so one chunk = one batch).

### Phase 1

1. **`@Profile("!mock")` vs `@Profile("tmdb")`.** `spring.profiles.default: tmdb` applies *only when no profile is active*. With `-Dspring-boot.run.profiles=demo` a profile *is* active, so `tmdb` is **not** → `@Profile("tmdb")` beans vanish → no `TmdbClient` bean → startup failure (`NoSuchBeanDefinitionException`/unsatisfied dependency). `!mock` is true whenever `mock` isn't active, regardless of other profiles.
2. **Two things in `Movie`.** The **unique constraint on `tmdb_id`** (database-level; stops a duplicate *INSERT* — the loser gets a constraint violation) and **`@Version`** (optimistic locking; stops a *lost UPDATE* — `UPDATE … WHERE id=? AND version=?` affects 0 rows → `OptimisticLockingFailureException`). Insert protection vs update protection.
3. **Lazy genres, no transaction, `open-in-view: false`.** `LazyInitializationException` (no open session to load the collection). *With* a transaction it works but triggers **N+1**: 1 query for the movies + 1 per movie for genres (21). Fixes: `@EntityGraph`/`join fetch`, `@BatchSize`, or a DTO projection query.

### Phase 2

1. **Writer throws on item 14 of the 3rd chunk (size 20).** Chunk 3 = items 41–60, one transaction. The exception **rolls back the whole chunk 3**: DB holds only chunks 1–2 (minus filtered items) — *not* items 41–53, even though they were "written" before the exception, because they were uncommitted. The saved `ExecutionContext` is from the **last successful commit (end of chunk 2)**: reader position after item 40, writer counters for chunks 1–2 (`update()` is only called for a successful chunk). Status `FAILED`. **Restart:** `open()` restores that position → re-reads items 41+ → chunk 3 is attempted again; nothing lost, nothing duplicated (and the upsert would be idempotent anyway). Nuance: with JDBC batching the exception may surface at *flush/commit time*, not literally "at item 14" — the outcome is the same.
2. **Processor returns `null` for the adult movie.** `FILTER_COUNT` increments (`READ_COUNT` too; `WRITE_COUNT` does not). The item **never reaches the writer**. No rollback, no listener, no skip. Throwing instead: a non-skippable exception fails the step and rolls the chunk back; a skippable one bumps `SKIP_COUNT`, calls the `SkipListener` (→ `REJECTED_RECORD`), counts against `skipLimit`, and the chunk is retried without that item. Null = "not an error"; exception = "something is wrong".
3. **`runAt` identifying, `trigger` not.** `JobInstance` = job name + *identifying* parameters. `runAt` (a new timestamp every launch) makes every launch a **new instance**; `trigger` is information only and must not change identity (a manual and a scheduled run with the same `runAt` would be the same logical run). If *both* were non-identifying, the identifying set would be empty → every launch is the **same JobInstance** → after the first one COMPLETES, the second scheduled run is refused (`JobInstanceAlreadyCompleteException`).

### Phase 3

1. **Why the 429 retry lives in `ResilientTmdbClient`.** In Batch 6 the step's retry applies to *processing and writing* a chunk, not to `reader.read()`, and a 429 happens inside `read()` (the HTTP call). Retrying at the layer that owns the failing call also lets us honour `Retry-After`, use exponential backoff, and distinguish transient from permanent errors.
2. **`null` vs `InvalidMovieException`.** Null: `FILTER_COUNT++`, no listener, no rollback, nothing stored. Exception (skippable): chunk rollback and re-run without the item, `SKIP_COUNT++`, `SkipListener` writes a `REJECTED_RECORD` row (in `REQUIRES_NEW`), counts toward `skipLimit` (exceeding → step FAILED).
3. **Writer fails on one item of 20 (skippable).** The writer received the whole chunk, so the framework doesn't know which item is bad. In the classic fault-tolerant step it rolls the chunk back and re-processes/re-writes the items one at a time ("scan") to isolate the culprit. Cost: up to 20 extra tiny transactions and a lot of redundant work. Hence: detect bad items in the **processor** (cheap, precise), keep writer failures rare. *(Our code only skips processor errors; confirm the exact Batch 6 scan behaviour in the reference if asked.)*

### Phase 4

1. **Page 0 forever.** `enrichDetails` selects "movies without details" and the writer fills them in, so the result set shrinks as we go. A standard paging reader moves to page 1 after page 0, but by then the former page 1 rows have slid into page 0 → about half of the rows are never visited. Always reading page 0 lets the shrinking set do the paging (`RollingPageJpaReader`).
2. **Decider gets "inserted".** Writer counters are in the *step* context → `ExecutionContextPromotionListener` copies the keys to the *job* context when the step COMPLETES → the decider reads the job context (and also asks the repository for pending details). The listener must be registered with `.listener(...)` (it wasn't, once).
3. **`snapshotTrending` FAILED.** `.on("*").fail()` ends the job FAILED; `summary` doesn't run; enrichment results already committed stay; a restart skips completed steps and re-runs only `snapshotTrending`. Without that line you'd depend on the framework's default handling of an unmatched exit status (either an implicit FAILED or a flow exception depending on the transitions defined) — so we make it explicit. Say which parts are explicit vs implicit.

### Phase 5

1. **Tick while a sync is running.** A tick from the single scheduler thread can't overlap itself (`fixedDelay` waits for it to finish), but a *manual* run can be active. Then `SyncJobService.assertNothingRunning` throws `SyncAlreadyRunningException`; `SyncScheduler.tick()` catches it, logs it, and records "already running" in `SchedulerControl` (visible in `GET /api/scheduler`). A REST `POST` in the same situation gets **409**.
2. **`StaleExecutionRecovery`.** A killed JVM leaves `STARTED` in the DB; Batch treats it as running and refuses to restart it. Recovery marks it `FAILED` at startup. With 3 pods, a `STARTED` execution may be **another pod's healthy job** — failing it breaks that job and enables double runs. You'd need an owner/heartbeat.
3. **Nightly at 02:00.** `cron` (e.g. `0 0 2 * * *`, with an explicit zone). `fixedDelay`/`fixedRate` are relative to the previous run/start and drift after restarts; they don't align to wall-clock.

### Phase 6

1. **Dies in chunk 4 after the writer ran, before commit.** The transaction is rolled back (or never committed): the DB has chunks 1–3; the saved context says "after chunk 3". Restart re-reads and re-writes chunk 4. This works because the position and the data commit *together*.
2. **Same identifying parameters.** They define the JobInstance; Batch looks up that instance's previous executions to learn which steps completed and to load the failed step's saved context. Different identifying params = a *new* instance that starts from scratch. (`JobOperator.restart(execution)` reuses the old parameters for you.)
3. **FAILED vs STOPPED.** FAILED: an exception/crash (stack trace in the exit description, last chunk rolled back). STOPPED: a graceful stop request — the current chunk committed, nothing broken. Both are restartable (same mechanism); only the cause and cleanliness differ.

### Phase 7

1. **DTOs, not entities.** (a) lazy collections → `LazyInitializationException` (with `open-in-view=false`) or hidden N+1; (b) the JSON contract becomes coupled to the table (rename a column → break clients); (c) internal fields (`id`, `version`) leak; (d) bidirectional relations → JSON cycles.
2. **`202`.** Client reads the `Location` header and polls `GET /api/jobs/executions/{id}` until COMPLETED/FAILED (then restart/stop if needed). Waiting and returning `200` would hold a server thread for minutes, hit client/proxy timeouts, offer no progress, and a retrying client could try to launch a second run (blocked by the 409 guard, but still bad design).
3. **`?sort=voteAverage,desc&size=100000&genre=Drama`.** `size` is capped to **50**; `voteAverage` passes the sort whitelist (`overview` would be 400); the `Specification` adds `adult = false` and a join on genres with `lower(name) = 'drama'`, `distinct`; the page query has `ORDER BY vote_average DESC LIMIT 50` plus a `COUNT` query with the same predicates; then `@BatchSize` loads genres and source lists for the 50 movies in 1 query each → about 4 statements total.

### Phase 8

1. **The race.** Both workers `SELECT` "tmdbId X not found", both `INSERT`; the second hits the unique key at flush (`DataIntegrityViolationException`; also row-lock timeouts). Batch 6 retries by re-invoking the write **in the same transaction**, where the Hibernate session is poisoned by the failed flush → every retry fails identically → the step and job FAIL (5/5 runs). Fix: serialise the DB write in a short `REQUIRES_NEW` transaction under a lock, keep HTTP reads parallel.
2. **Restart of a partitioned step.** The manager step is restarted; each worker has its own StepExecution and context, so only partitions that did **not** complete re-run (resuming from their own saved position); completed partitions are skipped. *(Per the reference, the splitter checks each partition's previous execution; we did not write a test for a partial-partition restart — a good exercise.)*
3. **Remote chunking vs partitioning.** Remote chunking when reading is cheap and single-source but *processing* each item is the heavy part (CPU or remote calls): one manager reads, workers process/write. Partitioning when the input splits into independent slices that workers can read themselves — fewer moving parts (no per-item messaging), restartable per slice.

### Phase 9

1. **Real socket for the HTTP client.** Timeouts, connection handling, header parsing (`Retry-After`), status-to-exception translation and `RestClient` configuration only exist on a real connection; a mock verifies our *assumptions* about the library, not the library.
2. **"Fails when 11 records are bad" cheaply.** Lower the limit in the test (`skip-limit=1`) and use the two existing corrupt fixture records (`corrupt-records=true`) → assert the step FAILED; or inject a stub reader. (`MovieSyncJobSkipLimitTest` does the first.)
3. **Multi-pod test.** Two app contexts (or processes) against one real shared database (e.g. Testcontainers Postgres), launched concurrently; assert exactly one runs and the other is refused. The current suite is one JVM and in-memory H2, so it cannot prove cross-JVM locking.

### The scenario questions (S1–S4 in `INTERVIEW_PREP.md`)

Memorise the *shape* of each answer:
* **429 halfway:** transient → client retries with backoff/`Retry-After` → if exhausted the step fails with progress saved → restart; mitigate with throttling.
* **Ran twice at once:** different `runAt` = different instances so Batch won't stop it → our lock + running-check → 409; multi-pod needs a shared lock; the upsert makes a double run harmless.
* **Failed at page 40 of 100:** inspect → fix cause → **restart the same execution** (not a new run) → resumes after the last committed chunk, pages 1–39 not re-read.
* **3 pods:** one scheduler (ShedLock/DB lock/CronJob), remove zombie recovery or add ownership, real shared DB, Flyway, remote partitioning with disjoint keys if you need throughput.

---

## 5. Self-test checklist (tick when you can do it aloud)

- [ ] Draw the chunk loop and say exactly when `update()` runs.
- [ ] Explain read = filtered + written with numbers from a real run (143 = 5 + 138).
- [ ] State the three failure tools and give one example of each from this project.
- [ ] Say why the restart is safe (atomic position + idempotent upsert).
- [ ] Explain JobInstance identity with `runAt` and `trigger`.
- [ ] Explain the rolling-page trap and the partition race, each in 45 seconds.
- [ ] List four ways to scale a step and when to use each.
- [ ] Give the 2-minute pitch without notes.
- [ ] Name three limits of the project honestly (real TMDB untested, JVM-local locks, `ddl-auto=update`).
