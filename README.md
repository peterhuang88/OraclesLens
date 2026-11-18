# OraclesLens

Local League of Legends data storage and SQL queries, using Scala, JDBC, and SQLite.
The existing `engine/*.java` code is a separate experiment; the CLI uses SQLite for
storage and query execution. The broader AI workflow is described in
[the project plan](docs/PROJECT_PLAN.md).

## Setup

Install JDK 11 or newer and sbt. Run commands from this repository's root.
The first run downloads the pinned dependencies in `build.sbt`.

```sh
sbt test
```

## Import CSV files

For the sample included in this repository:

```sh
sbt 'run ingest --data-dir Data --db .local/sample.sqlite'
```

For your full local dataset (the shell expands `$HOME` in this example):

```sh
sbt "run ingest --data-dir $HOME/Data/LoLData"
```

The default database is `.local/oracleslens.sqlite`. Use `--db PATH` to choose
another file. For paths containing spaces, quote the path **inside** the sbt command:

```sh
sbt 'run ingest --data-dir "/path/with spaces/data" --db ".local/my database.sqlite"'
```

Supported files map directly to tables with the same names and headers:

| CSV / table | Contents |
| --- | --- |
| `ChampionTbl` | Champion IDs and names |
| `ItemTbl` | Item IDs and names |
| `RankTbl` | Rank IDs and names |
| `MatchTbl` | Match IDs, patches, game modes, rank references, durations |
| `SummonerMatchTbl` | Player references, match references, champion references |
| `TeamMatchTbl` | Team compositions and team statistics |
| `MatchStatsTbl` | Participant statistics, lane, outcome, and final inventory |

Each file must be named exactly `<table>.csv`. A directory may contain any subset
of these files. Unknown CSV filenames, unexpected headers, invalid CSV syntax,
and inconsistent field counts cause an error; no part of that import commits.
UTF-8 CSVs support quoted commas, escaped quotes, multiline values, CRLF, trailing
empty fields, and an optional UTF-8 BOM.

Data can be downloaded from: 
https://www.kaggle.com/datasets/nathansmallcalder/lol-match-history-and-summoner-data-80k-matches/data?select=MatchStatsTbl.csv

**Imports replace all rows in each supplied table in one transaction.** Tables not
supplied are left alone. Rerunning an import does not append another copy, but
duplicates already present in the CSV remain. A header-only file empties its
table. Import related tables from a consistent source directory; mixing snapshots
is allowed but can break joins. Source files are never modified.

`_oracles_imports` records the source path, SHA-256, row count, and import time for
each table. Only supplied tables and their metadata are created. The database and
build outputs are ignored by Git.

## Run SQL

Supply one SQLite statement, either inline or in a UTF-8 file:

```sh
sbt 'run sql --query "SELECT COUNT(*) AS observations FROM MatchStatsTbl"'
sbt 'run sql --file sql/examples/lane_win_rates.sql'
sbt 'run sql --file sql/examples/champion_win_rates.sql'
sbt "run sql --db .local/sample.sqlite --query \"SELECT SummonerMatchFk, Win FROM MatchStatsTbl WHERE Lane = 'MIDDLE'\""
```

The champion example requires the full dataset's lookup tables. The lane example
works on the sample alone. These queries include every supplied game mode and
patch and assume `Win` is encoded as 0/1; they count stats rows, not distinct matches.

Results print as TSV with a header; fields containing tabs or newlines are quoted.
SQL NULL prints as `\N` (an empty source field remains an empty string). The default
output cap is 200 rows; use `--max-rows 1000` to change it. Truncation is reported
on stderr. The cap bounds returned data, not SQL execution time.

The SQL command opens an existing database read-only and enables SQLite's
`query_only` setting. It supports direct, trusted SQL, including joins, aggregation,
CTEs, and schema inspection. It does not yet implement the plan's AI SQL validation
or hard query deadlines. Errors produce a nonzero exit status.

The database is also a normal SQLite file, so the SQLite shell can query it without
running Scala:

```sh
sqlite3 -readonly -header -column .local/oracleslens.sqlite \
  'SELECT Lane, COUNT(*) AS observations, SUM(Win) AS wins FROM MatchStatsTbl GROUP BY Lane;'
sqlite3 -readonly .local/oracleslens.sqlite '.schema MatchStatsTbl'
```

## Data handling

There is no cleaning, deduplication, foreign-key enforcement, or primary-key
enforcement. Numeric fields have SQLite `INTEGER` affinity; names, match IDs,
patches, and lane/game-mode fields have `TEXT` affinity. SQLite converts numeric
strings on insert (so original numeric formatting such as leading zeros is not
preserved). Empty strings and nonnumeric values in numeric columns remain text;
they are not converted into NULL or rejected. Source files remain the authority
for original bytes. No indexes or curated analytical views are added yet.

See [data concerns](docs/DATA_CONCERNS.md) for observations from the current files.
