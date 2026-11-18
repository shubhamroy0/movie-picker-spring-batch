# CLAUDE.md — Movie Picker

Guidance for Claude Code (and humans) working in this repository. Read this first.
*(For Antigravity and Gemini agents, see linked instructions in [GEMINI.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/GEMINI.md).)*

## 1. Project purpose

**Movie Picker** is a *learning project* combining **Spring Batch** and **Spring Boot**, built to prepare for Java/Spring interviews.

- A **scheduled Spring Batch job** (`syncMoviesJob`) periodically pulls movies from the **TMDB public API** and **upserts** them into an **H2** database via **JPA/Hibernate**.
- A **Spring Boot REST API** serves that data (and controls the job) so it can be queried from **Postman**.
- Every concept used must be **taught as it is used** (see section 7). The code is a means; understanding is the goal.

Data attribution (required by TMDB terms): *"This product uses the TMDB API but is not endorsed or certified by TMDB."* It must appear in `README.md` and in the `GET /api/about` response.

## 2. Tech stack (verified 2026-11-17 against start.spring.io / Maven Central)

| Area | Choice |
|---|---|
| Language / build | Java **21** target (`maven.compiler.release=21`; the dev machine has JDK 25, which compiles fine with `--release 21`), Maven via **wrapper** (`mvnw.cmd`) |
| Framework | **Spring Boot 4.1.2** (latest stable) → Spring Framework **7.0.x**, **Spring Batch 6.0.6**, Hibernate **7.x**, Jackson **3**, Jakarta EE, JUnit 6 |
| Batch starter | `spring-boot-starter-batch-jdbc` (**not** plain `spring-boot-starter-batch`) |
| Other starters | `web`, `data-jpa`, `validation`, `actuator`, `aspectj` (AOP, Phase 10) |
| Database | H2 **file** DB: `jdbc:h2:file:./data/moviedb;AUTO_SERVER=TRUE`, H2 console enabled |
| HTTP client | Spring **`RestClient`** (optionally a declarative `@HttpExchange` interface on top) |
| Tests | `spring-boot-starter-test`, `spring-batch-test`, stub HTTP server (WireMock vs `MockRestServiceServer` — chosen and justified in Phase 1/9 after checking Boot 4 compatibility) |
| Optional | `springdoc-openapi` (3.x line targets Boot 4) — add only if it works on Boot 4.1.2 |

Always check versions on start.spring.io / Maven Central before changing them. Do not rely on Batch 4/5 tutorials.

## 3. Spring Batch 6 gotchas (respect these)

1. **Plain `spring-boot-starter-batch` ≠ metadata tables.** In Boot 4 it uses a `ResourcelessJobRepository` (nothing persisted). We use **`spring-boot-starter-batch-jdbc`** so the `BATCH_*` tables exist in H2 and can be inspected, and so restart works.
2. **`JobLauncher` / `JobExplorer` are deprecated.** Launch, restart and stop with **`JobOperator`**; query history through **`JobRepository`**.
3. **`JobBuilderFactory` / `StepBuilderFactory` are gone.** Use `new JobBuilder(name, jobRepository)` and `new StepBuilder(name, jobRepository)`.
4. **Test utilities moved/changed** — check the Spring Batch 6 reference docs (`JobOperatorTestUtils`, `JobRepositoryTestUtils`, `@SpringBatchTest`) before writing tests. If unsure about any API, **look it up; do not guess.**
5. `spring.batch.job.enabled=false` — otherwise Boot runs every `Job` bean at startup; we launch only from the scheduler/REST API.
6. **Packages moved in 6.0:** items/`ExecutionContext` are in `org.springframework.batch.infrastructure.item.*`; `Job`/`JobExecution`/`JobBuilder` in `core.job.*`; `Step`/`StepExecution`/`StepBuilder` in `core.step.*`; `JobOperator` in `core.launch`; `JobParameters` in `core.job.parameters`; `StepScope` in `core.configuration.annotation`.
7. **`StepBuilder.chunk(int)` returns the NEW `ChunkOrientedStepBuilder`** (fault tolerance is built in: `faultTolerant()`, `retry(..)`, `retryLimit`, `skip(..)`, `skipLimit`, `skipListener`, `taskExecutor`; retry uses Spring Framework 7's `org.springframework.core.retry.RetryPolicy`). The old `chunk(int, txManager)` returns the legacy `SimpleStepBuilder`.
8. **`ChunkOrientedStepBuilder` defaults to a `ResourcelessTransactionManager`** (no real transaction!). ALWAYS call `.transactionManager(transactionManager)` with the JPA one, or JPA writes are not tied to the chunk.
9. `ChunkOrientedStep` auto-registers reader/processor/writer as `ItemStream`s if they implement it. `JobRepository` now has the query methods `JobExplorer` used to have (`getJobInstanceCount`, `getLastStepExecution`, ...). `JobExecution` times are `LocalDateTime`.
10. `BATCH_STEP_EXECUTION_CONTEXT.SHORT_CONTEXT` is Base64 of a Java-serialised HashMap (not readable text) — read it through `JobRepository`, or decode it. H2 sequences cache 32 values, so IDs jump (1 → 33) after a forced kill: never treat batch IDs as counters.
11. Look APIs up with `javap` on `~/.m2/.../spring-batch-core-6.0.6.jar` (and `-infrastructure`) rather than guessing.
12. **A listener/aggregator you build is inert until registered** (`.listener(promotion)`). `ExecutionContextPromotionListener` copies keys step → job context only when the step COMPLETES; for FAILED runs read the step's own context (see `SyncRunService.stats`).
13. **`JobRepository.getJobExecution(unknownId)` throws `EmptyResultDataAccessException`** (JDBC repo), it does not return null. Map it to your own not-found exception (`SyncJobService.find`).
14. **Retry re-invokes the write in the SAME transaction** (`rollbackCount` stays 0). It cannot recover from a poisoned Hibernate session (failed flush / unique-key clash). For concurrent writers serialise the write in a short `REQUIRES_NEW` transaction (`SerializedUpsertWriter`) or partition by disjoint keys.
15. **Partitioning:** worker steps are named `<workerStep>:<partitionKey>`; the built-in `DefaultStepExecutionAggregator` sums framework counters but NOT custom `ExecutionContext` values (`CounterAggregator`); each worker needs its own `@StepScope` reader/writer (`#{stepExecutionContext[...]}`); exclude `:` step names when summarising to avoid double counting.
16. **Failed-step `exitDescription`** starts with a framework wrapper (`Unable to process chunk`); the root cause is the last `Caused by:` line.
17. **Spring MVC:** do not declare `@ExceptionHandler` methods for exceptions `ResponseEntityExceptionHandler` already handles (e.g. `HttpMessageNotReadableException`, type mismatches) — startup fails with "Ambiguous @ExceptionHandler method". Constraints on controller parameters raise `HandlerMethodValidationException` (Spring 6.1+/7), not `ConstraintViolationException`.
18. **AOP (Boot 4):** the starter is `spring-boot-starter-aspectj` (was `starter-aop`). Spring AOP = runtime CGLIB proxies (Boot default `proxy-target-class=true`), NOT bytecode weaving. An `@Aspect` class must also be a bean (`@Component`) or it does nothing.
19. **Self-invocation is not advised:** `this.method()` bypasses the proxy (same reason `@Transactional`/`@Async` fail on internal calls). `SyncJobService.startSync → startSyncAsync` is deliberately audited once. Do not "fix" it by self-injection; it is the lesson (`AspectsIntegrationTest`).
20. **Always give aspects an explicit `@Order`** (lower = outer; 10 timing, 20 request log, 30 audit, 40 metrics). `@Transactional`'s advisor has the lowest precedence, so ours wrap outside the transaction.
21. **An `@Around` advice must call `proceed()` and rethrow the original exception unchanged**, or it changes the contract of the method (REST status codes depend on the exception types). Prefer the weakest advice type that works.
22. **Aspects cover services, controllers and the TMDB client only.** Do not put pointcuts on Spring Batch internals or `@StepScope` beans. Keep metric tags low-cardinality, and never log arguments in an aspect that could cover secrets (the token never flows through any advised method).
23. **New aspect checklist:** package `com.moviepicker.aop`; a named pointcut in `Pointcuts` if reused; a no-Spring `AspectJProxyFactory` test plus an assertion of the effect in `AspectsIntegrationTest` (including the negative case).

## 4. Environment — Windows 11 + PowerShell

- All commands in docs are **PowerShell**, not bash. Use the wrapper: `.\mvnw.cmd`, **never** bare `mvn` (Maven is not installed).
- Quote `-D` args: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock,demo"`
- Use **`curl.exe`** (plain `curl` is an alias for `Invoke-WebRequest` in Windows PowerShell 5.1) or `Invoke-RestMethod`.
- Env vars: `$env:TMDB_API_TOKEN = "..."` (this session only); `setx TMDB_API_TOKEN "..."` (persistent; takes effect only in **new** terminals/app restarts).
- Code/config use forward slashes or `Path` APIs — never hard-coded backslash paths. H2 URL stays relative.
- Port 8080 busy? `netstat -ano | findstr :8080`, then `Get-Process -Id <PID>`. Port is configurable via `SERVER_PORT`.
- **Stopping the app:** make sure the Java process is really gone (`Get-Process java`), otherwise Windows keeps the H2 file locked. `AUTO_SERVER=TRUE` lets other tools (IntelliJ/DBeaver) open the DB while the app runs.
- `.gitattributes`: `* text=auto`, `*.cmd eol=crlf`, `*.sh eol=lf`.

## 5. Build / run / test commands (PowerShell)

```powershell
.\mvnw.cmd clean verify                                              # build + all tests (must pass after every phase)
.\mvnw.cmd test                                                      # tests only
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock"         # no network, no token needed
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock,demo"    # + sync every 30 s
$env:TMDB_API_TOKEN = "<token>"; .\mvnw.cmd spring-boot:run          # real TMDB ("tmdb" profile)
```

H2 console: `http://localhost:8080/h2-console` — JDBC URL `jdbc:h2:file:./data/moviedb;AUTO_SERVER=TRUE`, user `sa`, empty password.

### Profiles
| Profile | Meaning |
|---|---|
| `tmdb` | Real API. Default when `TMDB_API_TOKEN` is set. |
| `mock` | No network. Serves JSON fixtures from `src/main/resources/fixtures/`, rotated between runs so inserts *and* updates are visible. Must work fully without a token. Contains failure-simulation switches (429/5xx/bad record/fail on page N) for demos. |
| `demo` | Scheduler interval 30 s instead of 5 min (`PT30S`). Combine: `mock,demo`. |

## 6. Project layout & conventions

Base package: `com.moviepicker` (adjust if Initializr dictates otherwise).

```
src/main/java/com/moviepicker/
  config/        @ConfigurationProperties records, RestClient + Batch + Scheduling config
  domain/        JPA entities (Movie, Genre, TrendingSnapshot, SyncRun, RejectedRecord)
  repository/    Spring Data repositories
  tmdb/          TMDB client (real + mock), DTO records
  batch/         job config, readers, processors, writers, listeners, deciders, tasklets
  scheduler/     @Scheduled trigger, pause/resume state, overlap guard
  service/       business logic used by controllers
  web/           controllers, response DTOs, ProblemDetail exception handler
  aop/           aspects (@Aspect): timing, request log, job audit, TMDB metrics + shared Pointcuts
src/main/resources/  application.yml (+ application-{tmdb,mock,demo}.yml), fixtures/
src/test/java/...    mirrors main
docs/            LEARNING_LOG.md, INTERVIEW_PREP.md
postman/         MoviePicker.postman_collection.json, local.postman_environment.json
data/            H2 files (git-ignored)
```

**Conventions**
- **Constructor injection only** (no `@Autowired` fields).
- **Records for DTOs** (TMDB DTOs and API response DTOs). **Entities never leave the service layer.**
- **No Lombok.**
- **Small, focused classes** — one responsibility each.
- **`@ConfigurationProperties` (records, `@Validated`)** instead of scattered `@Value`.
- Layering: controller → service → repository. Controllers are thin.
- Errors: `ProblemDetail` (RFC 9457); validate inputs with Jakarta Validation; proper status codes.
- Comments explain the **WHY** of non-obvious concepts, not the WHAT.
- Schema: `ddl-auto=update` for dev only (explained as a trade-off); note what you would use in prod (Flyway/Liquibase + `validate`).

## 7. Teaching rules (the most important section)

1. **Before each phase:** explain in plain English what we are building and which Spring Batch / Spring Boot concepts it introduces.
2. **After each phase:** append to `docs/LEARNING_LOG.md` — what was built; each file and why it exists; key annotations/classes and what they do internally; Mermaid diagram where useful; what appears in logs and `BATCH_*` tables; common mistakes.
3. **Comment code** where the concept is non-obvious.
4. **After each phase:** ask 2–3 quick check questions and **wait** for the user's reply. If the user says "keep going", continue without pausing.
5. **Gate every phase:** `.\mvnw.cmd clean verify` passes **and** the app is run to prove it works. Fix errors before moving on.
6. **End deliverable:** `docs/INTERVIEW_PREP.md` (architecture + diagram, 50+ Q&A each pointing to a file here, 2-minute pitch script, scenario questions).

## 8. Security rules

- **Never hard-code or commit the TMDB token.** Read it only from the `TMDB_API_TOKEN` environment variable (bound through `@ConfigurationProperties`).
- Never log the token (log only whether it is present).
- `.gitignore` must include: `data/`, `*.mv.db`, `*.trace.db`, `.env`, `.env.*`, `*.local.*`, `target/`, IDE folders.
- Postman environment files must contain no real token (use a placeholder/`secret` variable).

## 9. TMDB rules of engagement

- Auth: `Authorization: Bearer <TMDB_API_TOKEN>` (the v4 "API Read Access Token").
- Endpoints: `/3/genre/movie/list` (cached), `/3/trending/movie/day`, `/3/movie/now_playing`, `/3/movie/popular`, `/3/movie/top_rated` (paginated), `/3/movie/{id}` (enrichment). Verify shapes at https://developer.themoviedb.org.
- Configurable: lists to sync, pages per list (default 3), region/language.
- Be polite: throttle, retry 429/5xx with exponential backoff, honor `Retry-After`, set timeouts.
