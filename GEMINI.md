# GEMINI.md — Movie Picker (Antigravity & Gemini Workspace Instructions)

Guidance for Antigravity and Gemini agents working in this repository.
This instruction set links to and extends [CLAUDE.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/CLAUDE.md) as the shared project standard.

> **Agent Instructions Synchronization Rule:**
> [GEMINI.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/GEMINI.md) and [CLAUDE.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/CLAUDE.md) must always be kept in sync. Whenever changes are made to `CLAUDE.md` (e.g., adding gotchas, updating architecture, or introducing new phases as in [PR #1: Add AOP aspects](https://github.com/shubhamroy0/movie-picker-spring-batch/pull/1)), `GEMINI.md` **must also be updated** to ensure complete parity across both Claude Code and Antigravity/Gemini agent workflows.

<!-- Include shared project standards & Spring Batch 6 gotchas via Antigravity rule expansion -->
@[CLAUDE.md](CLAUDE.md)

---

## 1. Single Source of Truth & Alignment with CLAUDE.md

All domain, architecture, security, and coding standards defined in [CLAUDE.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/CLAUDE.md) apply fully to Antigravity:

* **Tech Stack**: Java 21 (`maven.compiler.release=21`), Spring Boot 4.1.2, Spring Framework 7.0.x, Spring Batch 6.0.6, Hibernate 7.x, Jackson 3, Jakarta EE, H2 file database (`jdbc:h2:file:./data/moviedb;AUTO_SERVER=TRUE`), Spring `RestClient`, `spring-boot-starter-aspectj` (Phase 10 AOP).
* **Spring Batch 6 & AOP Gotchas**:
  * Always use `spring-boot-starter-batch-jdbc`; launch with `JobOperator` and inspect via `JobRepository`.
  * Instantiate steps with `new StepBuilder(name, jobRepository)`; use `ChunkOrientedStepBuilder` and explicitly bind `.transactionManager(transactionManager)`.
  * **AOP (Phase 10)**: Spring AOP uses runtime CGLIB proxies. Advise only beans (`@Component`); self-invocation (`this.method()`) bypasses proxies. Explicit `@Order` on all aspects (10 timing, 20 request log, 30 audit, 40 metrics). `@Around` advice must rethrow exceptions unchanged. Keep aspects isolated to services, controllers, and TMDB client.
* **Code Conventions**:
  * Constructor injection only (no `@Autowired` fields).
  * Java Records for DTOs; entity objects must never leak past the service layer.
  * No Lombok; keep classes small and single-purpose.
  * Use `@ConfigurationProperties` records with `@Validated` instead of scattered `@Value`.
  * Proper RFC 9457 `ProblemDetail` error responses.
* **Security & Attribution**:
  * Never hard-code or log the TMDB token; load it via the `TMDB_API_TOKEN` environment variable.
  * Maintain the TMDB attribution clause (*"This product uses the TMDB API but is not endorsed or certified by TMDB."*) in `README.md` and `GET /api/about`.

---

## 2. Antigravity Tool & Execution Guidelines

### PowerShell & Windows 11 Execution
* **Maven Wrapper**: Always execute `.\mvnw.cmd` from the project root; never call bare `mvn` (Maven is not installed globally).
* **Quoting Arguments**: PowerShell strips unquoted `-D` flags. Always quote them:
  ```powershell
  .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock"
  .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=mock,demo"
  ```
* **Process Cleanup & File Locks**:
  H2 file DB in Windows locks `data/moviedb.mv.db`. If a previous Java run did not terminate cleanly or port 8080 is blocked:
  ```powershell
  Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
  netstat -ano | findstr :8080
  ```
* **Verification Gates**:
  Execute `.\mvnw.cmd clean verify` inside the sandbox to validate code changes before considering any implementation task complete.

---

## 3. Teaching Rules & Interactive Flow

As emphasized in [CLAUDE.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/CLAUDE.md) Section 7, **Movie Picker is a learning project**:
1. **Explain First**: Before writing or modifying code, explain the concepts and mechanics in plain English (e.g. chunk semantics, `ExecutionContext`, retry vs. skip, AOP proxying).
2. **Comment Code**: Comment non-obvious architecture decisions focusing on **WHY**, not what.
3. **Log Progress**: After any phase or major component update, append documentation to [LEARNING_LOG.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/docs/LEARNING_LOG.md) and update [INTERVIEW_PREP.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/docs/INTERVIEW_PREP.md).
4. **Interactive Checkpoints**:
   * After completing a phase, ask 2–3 check questions to test understanding.
   * Stop calling tools and yield the turn so the user can reflect and answer.
5. **Interview Deliverable**: Maintain interview scenarios and Q&A alignments to ensure every concept can be spoken to in a 30–60 second interview response format (Verdict → Mechanism → Project Evidence → Trade-off).

---

## 4. Key Project References

* Shared Guidelines: [CLAUDE.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/CLAUDE.md)
* Linked PR: [PR #1: Add AOP aspects (Phase 10)](https://github.com/shubhamroy0/movie-picker-spring-batch/pull/1) ([Diff b335630](https://github.com/shubhamroy0/movie-picker-spring-batch/pull/1/changes#diff-b335630551682c19a781afebcf4d07bf978fb1f8ac04c6bf87428ed5106870f5))
* Learning Log: [LEARNING_LOG.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/docs/LEARNING_LOG.md)
* Interview Preparation: [INTERVIEW_PREP.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/docs/INTERVIEW_PREP.md)
* Study Plan & Labs: [STUDY_PLAN.md](file:///e:/Projects/Movie%20API%20DB%20java%20practice/docs/STUDY_PLAN.md)
* Postman Collection: [MoviePicker.postman_collection.json](file:///e:/Projects/Movie%20API%20DB%20java%20practice/postman/MoviePicker.postman_collection.json)
