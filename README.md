# Movie Picker

A learning project that combines **Spring Batch** and **Spring Boot**.
A scheduled Spring Batch job (`syncMoviesJob`) pulls movies from the **TMDB API** and upserts them into an **H2** database
through JPA/Hibernate; a Spring Boot REST API serves the data and controls the job, so you can drive everything from Postman.

> **Status:** all 9 phases implemented. Study material: [docs/LEARNING_LOG.md](docs/LEARNING_LOG.md) (what each phase built and
> why), [docs/INTERVIEW_PREP.md](docs/INTERVIEW_PREP.md) (architecture, Q&A, scenarios, 2-minute pitch) and
> [docs/STUDY_PLAN.md](docs/STUDY_PLAN.md) (how to study it, plus the answers to every check question).

**Stack:** Java 21 · Spring Boot 4.1.2 (Spring Framework 7, Spring Batch 6.0.6, Hibernate 7, Jackson 3) · Maven wrapper · H2 file DB.
All commands below are **Windows PowerShell**.

## What it does

```
 scheduler (every 5 min) / POST /api/jobs/sync
        |
        v
 syncMoviesJob ──► fetchAndUpsertMovies ──► [decider] ──► enrichDetails ──► snapshotTrending ──► summary
                   (chunk: read TMDB pages,   new movies      (chunk: GET        (chunk: today's        (tasklet:
                    filter junk, skip bad     or pending?     /movie/{id})       trending ranks)        one-line log +
                    records, upsert)                                                                    SyncRun row)
        |
        v
   H2: MOVIE, GENRE, TRENDING_SNAPSHOT, SYNC_RUN, REJECTED_RECORD  +  BATCH_* metadata
        |
        v
 REST API: /api/movies, /trending, /picks, /genres, /api/jobs/*, /api/scheduler/*, /api/about
```

## Prerequisites

```powershell
java -version                 # must be 21 or newer (this project targets 21; JDK 25 works too)
$env:JAVA_HOME                # must point at your JDK folder
```

If `JAVA_HOME` is empty: `setx JAVA_HOME "C:\Path\To\jdk"` (then open a new terminal).
Maven is **not** required — the wrapper `mvnw.cmd` downloads it on first use.

## Getting a TMDB token

1. Create a free account at <https://www.themoviedb.org> and open <https://www.themoviedb.org/settings/api>.
2. Request an API key and copy the **API Read Access Token** (a long `eyJ...` string, *not* the short "API Key").
3. Provide it through the **`TMDB_API_TOKEN`** environment variable — never put it in a file that is committed:

```powershell
$env:TMDB_API_TOKEN = "eyJ..."        # current terminal only
setx TMDB_API_TOKEN "eyJ..."          # persistent; only NEW terminals / app restarts see it
```

The token is read only from that variable, never logged (the app logs only whether it is present).
**Not needed** for the `mock` profile.

## Running

```powershell
.\mvnw.cmd clean verify                                              # build + all tests

# No network, no token needed (fixtures in src/main/resources/fixtures):
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock"

# Same, but sync every 30 s instead of every 5 min (best for watching the scheduler):
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock,demo"

# Real TMDB (default profile "tmdb"; needs TMDB_API_TOKEN):
.\mvnw.cmd spring-boot:run
```

> PowerShell quirk: always put `-D...` arguments in **quotes**, otherwise PowerShell splits them at the comma/dot.

| Profile | Meaning |
|---|---|
| `tmdb` | Real API. Default when no profile is active. Warns clearly if the token is missing. |
| `mock` | No network; JSON fixtures; works without a token. Each new run sees "later" data (inserts **and** updates). |
| `demo` | Scheduler interval 30 s instead of 5 min. Combine with the others: `mock,demo`. |

### Switches (environment variables — set them in the terminal that starts the app)

Spring maps `MOVIEPICKER_SYNC_RUNONSTARTUP` → `moviepicker.sync.run-on-startup` ("relaxed binding").

| Variable | Effect |
|---|---|
| `MOVIEPICKER_SYNC_RUNONSTARTUP=true` | Run one sync right after startup |
| `MOVIEPICKER_SYNC_ENABLED=false` | No scheduler at all (trigger only via the API) |
| `MOVIEPICKER_SYNC_PARTITIONED=true` | Phase 8: fetch step split into 4 parallel worker steps (one per TMDB list) |
| `MOVIEPICKER_MOCK_FAILONCEAT=popular:2` | Mock: fail ONCE when fetching page 2 of `popular` (restart demo) |
| `MOVIEPICKER_MOCK_RATELIMITCALLS=2` | Mock: the first 2 calls answer 429 (retry/backoff demo) |
| `MOVIEPICKER_MOCK_SERVERERRORCALLS=2` | Mock: the first 2 calls answer 503 |
| `MOVIEPICKER_MOCK_CORRUPTRECORDS=true` | Mock: two broken records (skip + `REJECTED_RECORD` demo) |
| `MOVIEPICKER_MOCK_LATENCYMILLIS=200` | Mock: slow every call (so you can stop a running job) |
| `MOVIEPICKER_MOCK_ROTATEDATA=false` | Mock: every run sees identical data (idempotency demo) |

Remove one afterwards so it does not stick to the terminal: `Remove-Item Env:\MOVIEPICKER_MOCK_FAILONCEAT`.

### Port 8080 already in use?

```powershell
netstat -ano | findstr :8080          # last column = PID
Get-Process -Id <PID>                 # what is it?
$env:SERVER_PORT = 9090               # or run this app on another port
```

### Stopping the app (important on Windows)

`Ctrl+C` in the terminal that runs it. Then verify no Java process is left — a leftover process keeps the H2 file locked:

```powershell
Get-Process java                      # should be empty (or not your app)
Stop-Process -Id <PID> -Force         # if the app is still there
```

## Try the API (PowerShell)

Use **`curl.exe`** (plain `curl` is an alias for `Invoke-WebRequest` in Windows PowerShell 5.1) or `Invoke-RestMethod`.
Start the app in the `mock` profile, then:

```powershell
# 1) start a sync (202 Accepted + Location header; it runs in the background)
curl.exe -s -X POST http://localhost:8080/api/jobs/sync
# 2) watch it (replace 1 with the executionId from step 1)
curl.exe -s http://localhost:8080/api/jobs/executions/1
curl.exe -s "http://localhost:8080/api/jobs/executions?limit=5"

# 3) query the data
curl.exe -s "http://localhost:8080/api/movies?genre=Science%20Fiction&minRating=7&sort=voteAverage,desc&size=5"
curl.exe -s http://localhost:8080/api/movies/278
curl.exe -s "http://localhost:8080/api/movies/trending?list=trending_day"
curl.exe -s "http://localhost:8080/api/movies/picks?limit=5"
curl.exe -s http://localhost:8080/api/genres

# 4) control
curl.exe -s -X POST http://localhost:8080/api/scheduler/pause
curl.exe -s http://localhost:8080/api/scheduler
curl.exe -s -X POST http://localhost:8080/api/jobs/executions/1/restart     # only FAILED / STOPPED
curl.exe -s -X POST http://localhost:8080/api/jobs/executions/1/stop        # only a running one

# same thing with PowerShell objects:
$r = Invoke-RestMethod -Method Post http://localhost:8080/api/jobs/sync
Invoke-RestMethod "http://localhost:8080/api/jobs/executions/$($r.executionId)" | ConvertTo-Json -Depth 5
```

| Method & path | What |
|---|---|
| `GET /api/movies` | Paged search. `genre` (name or id), `minRating`, `year`, `language`, `q` (title), `page`, `size` (max 50), `sort=field,dir` (`popularity`, `voteAverage`, `voteCount`, `releaseDate`, `title`, `lastSyncedAt`) |
| `GET /api/movies/{tmdbId}` | One movie (404 ProblemDetail if unknown) |
| `GET /api/movies/trending?list=` | Latest snapshot in rank order with `previousRank` / `movement` |
| `GET /api/movies/picks?genre=&limit=` | Best picks by a weighted-rating score (formula in `PickScorer`) |
| `GET /api/genres` | Genre catalogue |
| `POST /api/jobs/sync` | Start a sync → **202** + `Location`; **409** if one is running |
| `GET /api/jobs/executions[/{id}]` | History / one execution with its steps and counters |
| `POST /api/jobs/executions/{id}/restart` · `/stop` | Restart a FAILED/STOPPED execution · stop a running one gracefully |
| `GET /api/scheduler` · `POST .../pause` · `.../resume` | Scheduler status and pause switch |
| `GET /api/about` | Attribution |
| `GET /actuator/health` | Health incl. the custom last-sync indicator |
| `/swagger-ui/index.html` | OpenAPI UI |

Errors are RFC 9457 `application/problem+json`: 400 bad input, 404 not found, 409 wrong state, 500 unexpected.

## Postman

1. Postman → **Import** → select both files from the [`postman/`](postman) folder:
   `MoviePicker.postman_collection.json` and `local.postman_environment.json`.
2. Top-right environment picker → **Movie Picker - local** (sets `baseUrl = http://localhost:8080`).
3. Run **Jobs → Start sync**: its test script stores `executionId` in the environment, so **Get execution**, **Restart** and
   **Stop** work without copy-pasting. The environment file contains no secrets (the API token only lives in the app's
   environment, never in Postman).

## Demos worth doing

```powershell
# A) Failure and restart (Phase 6): fail once at page 2 of "popular", then restart the SAME job instance
$env:MOVIEPICKER_MOCK_FAILONCEAT = "popular:2"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock"
#   POST /api/jobs/sync  -> poll the execution -> FAILED (movies of the first lists are already committed)
#   POST /api/jobs/executions/{id}/restart -> COMPLETED, same jobInstanceId, no duplicates

# B) Rate limiting (Phase 3): the HTTP client retries with exponential backoff and honours Retry-After
$env:MOVIEPICKER_MOCK_RATELIMITCALLS = "2"

# C) Bad records (Phase 3): skipped, counted, stored in REJECTED_RECORD
$env:MOVIEPICKER_MOCK_CORRUPTRECORDS = "true"

# D) Partitioning (Phase 8): look at the fetchWorker:partition-* rows in BATCH_STEP_EXECUTION
$env:MOVIEPICKER_SYNC_PARTITIONED = "true"
```

Each run logs ONE summary line, e.g.
`SYNC RUN 3 COMPLETED in 212 ms: inserted=45 updated=0 unchanged=0 read=143 filtered=5 skipped=0 enriched=45 snapshots=30`.

## Database (H2)

| | |
|---|---|
| Console | <http://localhost:8080/h2-console> |
| JDBC URL | `jdbc:h2:file:./data/moviedb;AUTO_SERVER=TRUE` |
| User / password | `sa` / *(empty)* |

The URL is relative to the folder you start the app from (the project root), so the files are `data/moviedb.mv.db`.
`AUTO_SERVER=TRUE` lets a second tool (IntelliJ, DBeaver) open the same DB while the app runs; without it H2 locks the file
for a single process. In IntelliJ/DBeaver use the same URL **with an absolute path**, e.g.
`jdbc:h2:file:E:/Projects/Movie API DB java practice/data/moviedb;AUTO_SERVER=TRUE`.

Useful queries:

```sql
select job_execution_id, status, exit_code from batch_job_execution order by job_execution_id desc;
select step_name, status, read_count, filter_count, write_count, skip_count, commit_count, rollback_count
  from batch_step_execution order by step_execution_id desc;
select * from sync_run order by id desc;
select * from rejected_record;
```

Batch IDs jump (1 → 33) after a forced kill: H2 sequences cache 32 values. Never treat them as counters.

## Tests

`.\mvnw.cmd clean verify` runs everything: unit tests (client resilience, scorer, reader, processor), `@DataJpaTest`,
and end-to-end `@SpringBootTest` runs of the real job against the mock client and an in-memory H2 (idempotency, rotation,
skip, retry, flow/decider, restart, stop, scheduler, partitioning, REST API through MockMvc). The HTTP client is tested
against a real in-process stub server (`com.sun.net.httpserver`) so timeouts and 429/5xx are real, not mocked.

## Honest limitations

* **The real TMDB API has not been exercised by the automated tests** (they need a token and network); everything is
  verified against the `mock` profile. Try the real thing once with `$env:TMDB_API_TOKEN` set and look at the first run's log.
* The scheduler's overlap guard and the partition write lock protect **one JVM**. Multiple instances need ShedLock / a DB lock.
* `ddl-auto=update` is dev-only; production would use Flyway/Liquibase + `validate`.

## Line endings

`.gitattributes` sets `* text=auto` (git normalises text to LF in the repo, CRLF in a Windows checkout) and forces
`*.cmd` to CRLF and `*.sh` / `mvnw` to LF — a shell script with CRLF fails on Linux with `bad interpreter: /bin/sh^M`.

## Attribution

This product uses the TMDB API but is not endorsed or certified by TMDB.

(TMDB also asks for their logo to be shown; download it from <https://www.themoviedb.org/about/logos-attribution>.)

The mock fixtures are illustrative sample data (real-looking titles, approximate numbers, our own one-line summaries),
not an authoritative copy of TMDB data.
