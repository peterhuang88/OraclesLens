-- Requires MatchStatsTbl, SummonerMatchTbl, and ChampionTbl from the same dataset.
-- This includes all game modes and patches in the imported data.
SELECT c.ChampionName,
       COUNT(*) AS observations,
       SUM(s.Win) AS wins,
       ROUND(100.0 * SUM(s.Win) / COUNT(*), 2) AS win_rate_percent
FROM MatchStatsTbl s
JOIN SummonerMatchTbl sm ON s.SummonerMatchFk = sm.SummonerMatchId
JOIN ChampionTbl c ON sm.ChampionFk = c.ChampionId
GROUP BY c.ChampionId, c.ChampionName
ORDER BY observations DESC
LIMIT 20;
