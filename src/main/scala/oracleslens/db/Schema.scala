package oracleslens.db

final case class Column(name: String, affinity: String)
final case class TableSchema(name: String, columns: Vector[Column])

/** Source names are deliberate: SQL can use the names from the CSV headers directly.
  * No uniqueness, foreign-key, or value constraints until the data contract is established.
  */
object Schema {
  private def table(name: String, names: String, text: Set[String] = Set.empty): TableSchema =
    TableSchema(name, names.split(",").toVector.map { name =>
      Column(name, if (text(name)) "TEXT" else "INTEGER")
    })

  val tables: Vector[TableSchema] = Vector(
    table("ChampionTbl", "ChampionId,ChampionName", Set("ChampionName")),
    table("ItemTbl", "ItemID,ItemName", Set("ItemName")),
    table("RankTbl", "RankId,RankName", Set("RankName")),
    table("MatchTbl", "MatchId,Patch,QueueType,RankFk,GameDuration", Set("MatchId", "Patch", "QueueType")),
    table("SummonerMatchTbl", "SummonerMatchId,SummonerFk,MatchFk,ChampionFk", Set("MatchFk")),
    table("TeamMatchTbl", "TeamID,MatchFk,B1Champ,B2Champ,B3Champ,B4Champ,B5Champ,R1Champ,R2Champ,R3Champ,R4Champ,R5Champ,BlueBaronKills,BlueRiftHeraldKills,BlueDragonKills,BlueTowerKills,BlueKills,RedBaronKills,RedRiftHeraldKills,RedDragonKills,RedTowerKills,RedKills,RedWin,BlueWin", Set("MatchFk")),
    table("MatchStatsTbl", "MatchStatsId,SummonerMatchFk,MinionsKilled,DmgDealt,DmgTaken,TurretDmgDealt,TotalGold,Lane,Win,item1,item2,item3,item4,item5,item6,kills,deaths,assists,PrimaryKeyStone,PrimarySlot1,PrimarySlot2,PrimarySlot3,SecondarySlot1,SecondarySlot2,SummonerSpell1,SummonerSpell2,CurrentMasteryPoints,EnemyChampionFk,DragonKills,BaronKills,visionScore", Set("Lane"))
  )

  def quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
