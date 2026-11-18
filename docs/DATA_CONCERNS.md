# Data observations — November 17, 2026

These are observations, not cleaning rules. The importer preserves source rows;
no values were repaired, removed, or deduplicated.

## Local dataset

The seven files found in `~/Data/LoLData` contain:

| Table | Data rows |
| --- | ---: |
| ChampionTbl | 173 |
| ItemTbl | 635 |
| RankTbl | 11 |
| MatchTbl | 95,744 |
| SummonerMatchTbl | 193,758 |
| TeamMatchTbl | 84,155 |
| MatchStatsTbl | 193,758 |

Basic CSV inspection found consistent field counts, no empty cells, and no
duplicate first-column IDs in these files. This is not a complete quality audit.

Observed references from stats to participant records, participants to matches
and champions, team records to matches, matches to ranks, and enemy champion IDs
to champions all have corresponding lookup rows. These checks do not prove the
intended join cardinalities or complete team coverage.

Concerns to keep in mind:

- The full stats table has 193,758 rows but only 109,234 distinct
  `SummonerMatchFk` values. 84,500 reference values occur more than once, with
  up to three stats rows per reference. A unique stats ID does not establish
  one observation per participant. Joining to participant or champion data
  retains this repeated weighting; the correct analysis grain needs investigation.
- Two nonzero final-inventory slots contain item ID `7003`, which has no row in
  `ItemTbl`. An inner join to the item lookup would lose those observations.
- `MatchStatsTbl` has 27,313 rows with `Lane = NONE`. This is a supplied category,
  not an empty cell; deciding whether to include it depends on the analysis.
- Game modes are mixed: CLASSIC (71,726 matches), CHERRY (11,843), ARAM (10,357),
  SWIFTPLAY (1,804), and ULTBOOK (14). Pooled comparisons can mix different rules
  and item systems. No filtering is applied during import.
- All observed stats outcomes are 0/1 (95,116 losses; 98,642 wins). Other fields
  and future datasets are not constrained to valid values by the database.
- Zero appears as an item/champion/rank value. Its interpretation depends on
  the field; the importer preserves it rather than inventing a NULL policy.
- Patches are stored as text. A plain `ORDER BY Patch` is lexical and may not
  reflect numeric patch order.
- Final item slots describe inventory, not purchase sequence or item timing.
  Observed win rates do not establish causal item effectiveness.

## Repository sample

`Data/MatchStatsTbl.csv` contains 50 rows. Its `MatchStatsId` values are unique,
but `SummonerMatchFk` values 1–20 each occur twice. The sample must not be assumed
to have one row per participant reference. For example, the middle-lane query
returns repeated participant references. These rows remain in the database.

The sample has no supporting match or lookup CSVs. Import it into a separate
database (`--db .local/sample.sqlite`) rather than replacing the full dataset's
stats table and joining it to unrelated source snapshots.

## Future inputs

Empty numeric cells and nonnumeric numeric-field values remain SQLite text.
Aggregates can coerce such text, so future analytical validation should establish
types, null handling, keys, join cardinalities, coverage, and metric denominators
before relying on results. Bad CSV structure fails the whole import with an error
instead of silently skipping records.
