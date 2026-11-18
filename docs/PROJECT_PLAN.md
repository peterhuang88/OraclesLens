# OraclesLens project plan

## Goal and delivery strategy

Build a local AI-native League of Legends analytics assistant that turns questions into reproducible, validated analyses. The interesting work is semantic retrieval, analytical correctness, iterative investigation, and measurable agent performance. Reach that work before building database infrastructure.

Start with SQLite, local CSV ingestion, a CLI, and one model provider. SQLite performs joins, aggregation, planning, and execution. Add PostgreSQL only for a demonstrated concurrency or hosting need. Custom storage, a custom SQL language/parser/optimizer, Spark, distributed processing, streaming, Kubernetes, and complicated deployment are outside the MVP.

Planning assumptions: one developer, roughly 4–6 focused weeks for the full MVP; the first useful end-to-end demonstration should happen during week one. Milestones are gates, not promises tied to calendar dates.

## Existing work and relationship to earlier plans

Inspected repository baseline:

- `README.md`: project title only.
- `engine/{EngineMain,Query,Table,Row}.java`: CSV loading, projection, predicate filtering, and result limiting. Preserve as an engine experiment; it is not a dependency of the AI MVP.
- `Data/MatchStatsTbl.csv`: player statistics, win indicator, lane, six item slots, enemy champion ID, and other statistics. Inspect actual types, keys, coverage, and quality before relying on them.
- `.cursor/plans/oracleslens_system_design_630a9a89.plan.md` and `.cursor/plans/scala_query_engine_design_fd29d0e4.plan.md`: earlier proposals for Scala, MySQL, a custom query pipeline, and Spark. Preserve these as historical designs. This document revises delivery order and backend choices for the AI-first MVP.

The earlier plans describe additional CSVs under `~/Data/LoLData`; their existence, row counts, relationships, and completeness have not been verified for this plan. The repository CSV alone does not establish patch, queue, rank, champion names, team composition, item purchase timing, or player-match uniqueness. Missing data must reduce supported questions rather than invite invented joins or assumptions.

Keep the prior Scala preference: use one small Scala CLI application, JDBC for SQLite, a maintained SQL AST parser, JSON/YAML catalog files, and a thin HTTP model adapter. Select library versions during implementation. Avoid a multi-module build, effect framework migration, and new SQL grammar as prerequisites. Organize by packages first; the existing Java experiment remains untouched.

## Product contract and demonstration

Target workflow:

1. User asks a question.
2. Assistant resolves entities, retrieves relevant metric definitions, schema, joins, examples, and caveats.
3. Assistant shows or records an analysis specification: metric, cohort, time coverage, grain, comparison, and assumptions. Ask a targeted clarification when ambiguity changes the answer.
4. Assistant proposes SQL and parameters; deterministic validation gates execution.
5. SQLite returns bounded results and diagnostics.
6. Assistant may investigate a small number of follow-up hypotheses through the same validated path.
7. Final answer includes evidence tables, SQL references, denominators, dataset coverage, limitations, and a reproducible run ID.

First demonstration using only verified fields: compare lane-level observed win rates and sample sizes; drill into a lane's item-holder cohorts, making clear that final inventory is not a purchase history.

Later demonstration after lookup and match data are verified: “How do LDR and Mortal Reminder holders compare against tank lane opponents on the latest patch in this dataset, and does the pattern persist by champion or rank?” Show retrieved definitions, validated queries, subgroup checks, and an evidence-grounded conclusion. Never claim an item causes wins from observational inventory data.

## Modular architecture

```text
Local CSVs -> ingest + quality checks -> SQLite tables + curated SQL views
                                            ^
Question -> semantic retrieval -> analysis specification -> SQL proposal
                     ^                           |
           versioned catalog              AST + semantic validation
                                                 |
                                       bounded read-only execution
                                                 |
                                 evidence + diagnostics -> agent follow-up
                                                 |
                                         grounded answer + trace

Evaluation runs exercise retrieval, validation, execution, and answer quality.
```

| Package | Responsibility and contract |
| --- | --- |
| `ingest` | Parse real CSV syntax, map types/nulls, validate keys, load transactionally, record checksums and rejects. Produce a dataset snapshot ID and quality report. |
| `db` | Schema migrations, views, connections, and `QueryExecutor.execute(ValidatedQuery, Budget) -> QueryResult`. SQLite implementation first. |
| `catalog` | Versioned entities, aliases, metric formulas, grains, joins/cardinalities, dimensions, coverage, examples, and limitations. Validate catalog references against the database. |
| `retrieval` | Return ranked catalog entries with stable IDs and scores. Begin with lexical/entity lookup; add embeddings and hybrid ranking over catalog text during the retrieval milestone. |
| `model` | One provider adapter with structured response decoding, retries, token/cost accounting, and mock responses for deterministic tests. |
| `query` | Parse SQL using an existing parser, validate AST and analytical semantics, bind parameters, and issue a `ValidatedQuery`. No custom optimizer or execution engine. |
| `agent` | Own explicit state, tool calls, budgets, follow-up selection, stop conditions, and evidence references. |
| `eval` | Frozen fixtures, held-out questions, expected results/behaviors, retrieval labels, and comparison reports. |
| `observability` | Local structured events and artifacts connecting question, catalog retrieval, SQL, result, and answer. |
| `cli` | Thin entry points for ingest, direct SQL, ask, analyze, catalog inspection, and evaluation. |

Small shared contracts: `AnalysisSpec`, `CatalogEntry`, `DatasetSnapshot`, `ValidatedQuery(sql, parameters, catalogVersion)`, `QueryResult(columns, rows, truncated, elapsedMs)`, `Evidence`, and `RunTrace`. These preserve module boundaries without designing a universal relational IR.

### Proposed additions; leave current files in place

```text
build.sbt
project/
src/main/scala/oracleslens/{cli,ingest,db,catalog,retrieval,model,query,agent,eval,observability}/
src/test/scala/oracleslens/
catalog/                  # schema, metric definitions, aliases, examples
sql/migrations/           # SQLite DDL and curated views
fixtures/                 # small deterministic data, including adversarial cases
eval/                     # question suites, expected results, retrieval labels
docs/PROJECT_PLAN.md
Data/                     # existing inputs; preserve original bytes
.local/                   # generated database, indexes, traces; ignore in Git
```

These are proposed paths, not scaffolding created by this planning change. Keep secrets in environment variables. Add narrowly scoped ignore rules when generated files are introduced; do not replace the existing ignore file.

## Data and analytical semantics

### Ingestion and serving views

- Profile headers, row counts, nulls, numeric ranges, duplicate keys, and relationships. Use an explicit mapping file; never infer foreign keys solely from similar names.
- Begin with a typed `match_stats` table preserving source identifiers. Validate whether `MatchStatsId` is unique and whether `SummonerMatchFk` is one-to-one before defining player-match grain.
- Keep raw CSVs immutable. Load into staging tables and publish a new snapshot only after validation. A repeated import of the same files must not duplicate rows. Record source checksum, ingestion time, schema version, and rejected-row reasons.
- Add match, participant, champion, item, rank, and team tables only as available and verified. Enforce or audit referential integrity and record missing coverage.
- Expose curated views such as `player_match` and `participant_item`. Unpivot six item slots and remove empty IDs; deduplicate repeated item IDs per participant for item-holder metrics. Preserve slot-level data separately when needed.
- Prevent join fanout: count distinct verified participant keys for player-level metrics; use `EXISTS` for inventory/tag membership where appropriate. Specify whether both-item holders overlap or are excluded from a comparison.
- Add indexes based on measured representative queries. Full local scans are acceptable at this scale; no partitioning project.

### Catalog and answer rules

Every metric defines formula, unit, grain, eligible population, null policy, denominator, supported filters, and caveats. For win rate, validate `Win` encoding, exclude invalid/unknown outcomes, and report wins plus eligible participant count. Distinguish participant observations from distinct matches.

Resolve champion/item aliases through verified lookup data. Version curated tank tags and specify whether “against tanks” means lane opponent or any enemy-team member. Do not infer enemy-team membership from a lane-opponent field.

Parse patch components numerically. “Latest available patch” means newest covered patch in this snapshot; “previous patch” means its predecessor in verified coverage. Neither automatically means the current live patch. Display concrete coverage and request clarification for ambiguous time language.

“Better” requires an explicit metric or a disclosed default of observed win rate. Default minimum cohort size: 100 eligible observations; display smaller cohorts as insufficient evidence, not a ranked recommendation. Report uncertainty where appropriate and disclose that multiple participants can share a match; naive independent-observation intervals may understate uncertainty. Match-level resampling is a later improvement when match IDs are available.

Do not interpret final inventory as build order, completed-item timing, or causal item effectiveness. Champion, role, game duration, rank, and patch can confound results. Subgroup analysis describes robustness; it does not establish causality. Abstain when a requested variable is absent.

## Query validation and execution

Use a maintained SQLite-capable AST parser and verify dialect support with fixtures. If a required construct is unsupported, restrict the supported subset rather than writing a grammar.

- Accept exactly one read-only `SELECT`, including only validated nonrecursive CTEs. Inspect the entire AST, including nested queries, expressions, and functions.
- Allow only catalog-approved views, columns, joins, and pure functions. Reject DDL/DML, `ATTACH`, `PRAGMA`, extension loading, filesystem functions, arbitrary table-valued functions, and unknown identifiers. Resolve aliases and scope correctly.
- Check analytical grain, join cardinality, aggregate definitions, required cohort filters, and denominators. Reject or request correction when semantics cannot be established. SQL syntax validity alone is insufficient.
- Bind user-derived literal values. Open the query connection read-only with query-only enforcement, disable extensions, and use database authorization controls where supported. Ingestion uses a separate writable connection.
- Initial defaults: 5 seconds per query, 200 returned rows, 5 executed queries per analysis, 2 repair attempts, 60 seconds total analysis time, and a configurable token/cost budget. Validate driver cancellation behavior; use an isolated worker with a hard deadline if cancellation cannot be enforced.
- A result limit does not limit aggregation work: enforce wall-clock cancellation independently. Mark truncation explicitly and avoid summarizing a truncated table as the full population.
- Repairs receive structured validation errors; every repaired or follow-up query is validated again. Exhausted budgets produce a partial-evidence answer or abstention.

## Retrieval and iterative analysis

Index schema descriptions, metric definitions, aliases, valid joins, reviewed example queries, and caveats—not raw player rows. Use exact alias matching plus lexical retrieval as the baseline. Then embed the same versioned entries and combine lexical and vector ranks locally; a small in-process index is sufficient. Rebuild when catalog content or embedding configuration changes.

Measure whether retrieval brings the needed metric, relation, and caveat into the context. Compare lexical-only and hybrid retrieval on held-out questions before retaining complexity. Never execute SQL from a retrieved example without validation; dataset and catalog text are evidence, not agent instructions.

Implement the agent as a bounded state machine: clarify -> retrieve -> specify -> propose -> validate -> execute -> inspect -> follow up or stop -> answer. Useful follow-ups include checking cohort sizes, comparing subgroup results, and investigating unexpected nulls. Parallel multi-agent orchestration is deferred.

Each numerical claim must reference an executed result and metric definition. Store concise decisions and tool events, not private model reasoning. Stop when the question is answered, required data is missing, validation repeatedly fails, or a budget is exhausted. Distinguish an empty result from a failed query or missing data.

## MVP milestones and acceptance gates

| Milestone | Scope | Acceptance criteria |
| --- | --- | --- |
| M0: trusted local data, days 1–2 | Build/CLI, CSV profile, typed SQLite import, first views/catalog, direct SQL | Clean setup can ingest a fixture and existing CSV; repeated import preserves counts; invalid rows have explicit handling; three hand-checked aggregates match. |
| M1: first AI answer, days 3–5 | Lexical retrieval, structured analysis specification, model adapter, validated SQL, evidence answer | Five supported fixture questions return correct results and SQL/run IDs; missing patch/name data leads to a clear limitation; writes, unknown columns, fanout cases, and expensive queries are rejected or stopped. |
| M2: semantic retrieval, week 2 | Verified supplemental data where available, aliases/metrics, local embeddings, hybrid retrieval | At least 20 labeled retrieval questions; required catalog entries appear in top 5 for at least 90%; report lexical vs hybrid outcomes and misses; stale catalog/index versions are detected. |
| M3: iterative analyst, weeks 3–4 | Bounded follow-ups, repair, subgroup checks, evidence synthesis | At least five scripted investigations, including an ambiguous request, insufficient cohort, missing data, empty result, and misleading aggregate; every follow-up passes validation; traces show budgets and evidence links. |
| M4: evaluated showcase, weeks 5–6 | Held-out suite, regression checks, trace viewer or readable report, reproducible demo | At least 40 total evaluation cases with at least 15 held out from prompt tuning; achieve >=85% result correctness on supported held-out questions and >=90% correct clarification/abstention behavior; all adversarial write attempts blocked; publish measured latency/cost and known failures. |

If time is limited, stop after M1 with an honest demonstration, then add M2/M3. Do not replace missing data work with infrastructure work. Thresholds are proposed release gates, not measured current performance.

## First week task list

1. **Day 1:** verify available CSVs and old-plan data paths; profile the repository CSV; record supported questions and data gaps; initialize the minimal Scala CLI/build and a tiny deterministic fixture.
2. **Day 2:** implement typed transactional SQLite ingestion, snapshot metadata, duplicate/null checks, curated views, and three reference aggregates. Define win rate and item-holder semantics in the catalog.
3. **Day 3:** implement lexical catalog retrieval, the provider adapter, structured `AnalysisSpec` output, and SQL proposals. Get the first fixture question through the complete path immediately.
4. **Day 4:** add AST/semantic validation, read-only execution, real deadline enforcement, result caps, one repair path, and local run traces. Exercise unsafe SQL and join-fanout fixtures.
5. **Day 5:** finish five representative AI questions and missing-data responses; create an initial 15-case regression suite; record a demo showing input, catalog evidence, SQL, results, and limitations. Select the next verified dataset needed for item/patch analysis.

Proposed CLI contract: `oracles ingest --data-dir ...`, `oracles sql --file ...`, `oracles ask "..."`, `oracles analyze "..."`, `oracles catalog`, and `oracles eval`. These commands are targets, not currently implemented functionality. `ask` performs one analytical query with bounded repair; `analyze` permits bounded follow-ups.

## Evaluation and observability

Use fixed synthetic fixtures for exact answers, plus a versioned real-data snapshot for representative workloads. Include duplicated item slots, both-item holders, repeated players within a match, null outcomes, malformed rows, patch ordering, sparse cohorts, ambiguous aliases, unsupported questions, and adversarial SQL/catalog content.

Evaluate layers separately: retrieval recall@5; SQL execution success; result equivalence to reviewed reference queries; cohort/denominator correctness; clarification and abstention; evidence-grounded numerical claims; and policy rejection. Compare result sets with defined ordering and numeric tolerances, not SQL string equality. Keep held-out questions separate from retrieved examples.

For every run, capture snapshot/catalog/index versions, model and prompt version, retrieved IDs/scores, analysis specification, candidate SQL/parameters, validation failures, executed SQL, result checksum or bounded result artifact, timing, token/cost usage, stop reason, and final evidence references. Log errors distinctly from empty results. Redact credentials and avoid logging unnecessary player identifiers. Keep artifacts local and ignored by Git; share only sanitized demonstration fixtures/traces.

Use deterministic replay with saved model responses for regression checks, plus a smaller live-model evaluation to measure actual behavior. Report stage latency and overall p50/p95 only with the run count, dataset size, model, and machine context. A practical initial latency target is under 30 seconds for a single-question answer on the chosen local dataset; measure before optimizing.

## Optional PostgreSQL transition

Move only when multiple concurrent writers/users or a hosted workflow justify it. Preserve catalog identities, analysis contracts, and evaluation questions. Add a PostgreSQL executor, migrations, dialect-specific SQL compilation/validation, and import path. Verify results against SQLite fixtures, including booleans, nulls, division, dates, and patch ordering. Treat migration as real compatibility work; a thin adapter does not make SQL dialects identical.

## Future database engine phase

Start after the AI evaluation gates pass and traces identify a useful workload. Keep the AI product runnable on SQLite throughout.

1. Freeze a representative workload from observed analyses: filters, projection, grouped aggregation, item membership, and joins. Measure SQLite baselines for latency, memory, and ingest cost.
2. Revisit `engine/` as a separate experiment. First add correctness fixtures and explicit row/type/null contracts; then implement scan/filter/project/aggregate for a documented subset. Use SQLite as the correctness oracle.
3. Pick one learning/research axis: columnar batches and vectorized execution, selective indexes/zone maps, join execution, or semantic retrieval-aware planning. Avoid simultaneously attempting storage, optimizer, transactions, and distributed execution.
4. Introduce a small logical plan only when two actual executors need it. Unsupported operations route explicitly to SQLite or fail clearly; never silently change semantics.
5. Benchmark identical data and queries, document limitations, and connect the experimental executor behind `QueryExecutor` only after result-equivalence checks pass.

Custom persistence, recovery, transactions, and optimizer work can follow as separate milestones. Spark and distributed infrastructure remain deferred unless measured data scale exceeds a single machine and simpler options have been evaluated.

## Definition of MVP complete

A fresh local checkout can load a documented dataset, answer supported LoL questions through catalog retrieval and validated SQL, conduct bounded follow-up analysis, explain numerical claims with reproducible evidence, abstain on unsupported questions, and run an evaluation suite with visible failures, latency, and cost. No custom engine or hosted service is required to satisfy this definition.
