-- Observations are stats rows; no assumption of one row per match or player.
SELECT Lane,
       COUNT(*) AS observations,
       SUM(Win) AS wins,
       ROUND(100.0 * SUM(Win) / COUNT(*), 2) AS win_rate_percent
FROM MatchStatsTbl
GROUP BY Lane
ORDER BY observations DESC;
