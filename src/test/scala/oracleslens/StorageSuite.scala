package oracleslens

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import munit.FunSuite
import oracleslens.cli.Main
import oracleslens.db.QueryExecutor
import oracleslens.ingest.CsvImporter
import scala.jdk.CollectionConverters._
import scala.util.Using

class StorageSuite extends FunSuite {
  private def workspace(test: (Path, Path) => Unit): Unit = {
    val root = Files.createTempDirectory("oracleslens-test-")
    val inputs = Files.createDirectory(root.resolve("csv"))
    try test(inputs, root.resolve("database with spaces.sqlite"))
    finally Using.resource(Files.walk(root)) { paths =>
      paths.iterator().asScala.toVector.reverse.foreach(Files.delete(_))
    }
  }

  private def write(directory: Path, name: String, content: String): Unit = {
    Files.writeString(directory.resolve(name + ".csv"), content, UTF_8)
    ()
  }

  private def rows(database: Path, sql: String): Vector[Vector[String]] =
    QueryExecutor.execute(database, sql).rows.map(_.map(_.getOrElse("NULL")))

  test("all seven CSV schemas ingest, with SQL joins and numerical aggregation") {
    workspace { (inputs, database) =>
      Using.resource(Files.list(Paths.get("src/test/resources/csv"))) { paths =>
        paths.iterator().asScala.foreach(p => Files.copy(p, inputs.resolve(p.getFileName)))
      }
      val imported = CsvImporter.ingest(inputs, database)
      assertEquals(imported.map(_.table).toSet.size, 7)
      assertEquals(rows(database, """SELECT c.ChampionName, m.Patch, r.RankName, SUM(s.Win), COUNT(*)
        FROM MatchStatsTbl s
        JOIN SummonerMatchTbl sm ON s.SummonerMatchFk = sm.SummonerMatchId
        JOIN ChampionTbl c ON sm.ChampionFk = c.ChampionId
        JOIN MatchTbl m ON sm.MatchFk = m.MatchId
        JOIN RankTbl r ON m.RankFk = r.RankId
        GROUP BY c.ChampionName, m.Patch, r.RankName ORDER BY c.ChampionName"""),
        Vector(Vector("Caitlyn", "14.24", "Unranked", "1", "1"), Vector("Milio", "14.23", "Unranked", "0", "1")))
      assertEquals(rows(database, "SELECT COUNT(*) FROM TeamMatchTbl"), Vector(Vector("1")))
      assertEquals(rows(database, "SELECT ItemName FROM ItemTbl"), Vector(Vector("World Atlas")))
    }
  }

  test("repository sample gives hand-checked aggregates and preserves repeated source references") {
    workspace { (_, database) =>
      CsvImporter.ingest(Paths.get("Data"), database)
      assertEquals(rows(database, "SELECT Lane, COUNT(*), SUM(Win) FROM MatchStatsTbl GROUP BY Lane ORDER BY Lane"),
        Vector(Vector("BOTTOM", "19", "7"), Vector("MIDDLE", "4", "2"), Vector("NONE", "5", "5"),
          Vector("SUPPORT", "10", "5"), Vector("TOP", "12", "12")))
      assertEquals(rows(database, "SELECT COUNT(*) FROM MatchStatsTbl WHERE SummonerMatchFk = 13"), Vector(Vector("2")))
    }
  }

  test("RFC CSV quoting, multiline fields, Unicode, BOM, trailing blanks and duplicate IDs survive") {
    workspace { (inputs, database) =>
      write(inputs, "ChampionTbl", "\ufeffChampionId,ChampionName\r\n1,\"Name, with \"\"quotes\"\"\"\r\n1,\"two\nlines é\"\r\n2,\r\n3,  spaced  \r\n")
      CsvImporter.ingest(inputs, database)
      assertEquals(rows(database, "SELECT ChampionId, ChampionName FROM ChampionTbl ORDER BY rowid"),
        Vector(Vector("1", "Name, with \"quotes\""), Vector("1", "two\nlines é"), Vector("2", ""), Vector("3", "  spaced  ")))
    }
  }

  test("numeric affinity supports math while blanks and invalid numbers remain uncleaned text") {
    workspace { (inputs, database) =>
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n10,A\n,B\ninvalid,C\n")
      CsvImporter.ingest(inputs, database)
      assertEquals(rows(database, "SELECT typeof(ChampionId), ChampionId FROM ChampionTbl ORDER BY rowid"),
        Vector(Vector("integer", "10"), Vector("text", ""), Vector("text", "invalid")))
    }
  }

  test("reimport replaces rather than appends and updates metadata with the source hash") {
    workspace { (inputs, database) =>
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n1,A\n1,B\n")
      CsvImporter.ingest(inputs, database)
      CsvImporter.ingest(inputs, database)
      assertEquals(rows(database, "SELECT COUNT(*) FROM ChampionTbl"), Vector(Vector("2")))
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n2,C\n")
      val imported = CsvImporter.ingest(inputs, database).head
      val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(inputs.resolve("ChampionTbl.csv")))
        .map(b => f"${b & 0xff}%02x").mkString
      assertEquals(imported.sha256, hash)
      assertEquals(rows(database, "SELECT ChampionId, ChampionName FROM ChampionTbl"), Vector(Vector("2", "C")))
      assertEquals(rows(database, "SELECT row_count, sha256 FROM _oracles_imports"), Vector(Vector("1", hash)))
    }
  }

  test("a later malformed CSV rolls back all table replacements and metadata") {
    workspace { (inputs, database) =>
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n1,Original\n")
      write(inputs, "ItemTbl", "ItemID,ItemName\n2,Original item\n")
      CsvImporter.ingest(inputs, database)
      val before = rows(database, "SELECT * FROM _oracles_imports ORDER BY table_name")
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n9,Replacement\n")
      write(inputs, "ItemTbl", "ItemID,ItemName\n2,too,many\n")
      intercept[IllegalArgumentException](CsvImporter.ingest(inputs, database))
      assertEquals(rows(database, "SELECT ChampionName FROM ChampionTbl"), Vector(Vector("Original")))
      assertEquals(rows(database, "SELECT ItemName FROM ItemTbl"), Vector(Vector("Original item")))
      assertEquals(rows(database, "SELECT * FROM _oracles_imports ORDER BY table_name"), before)
    }
  }

  test("partial imports leave tables not supplied intact; header-only CSV clears its table") {
    workspace { (inputs, database) =>
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n1,A\n")
      write(inputs, "ItemTbl", "ItemID,ItemName\n1,B\n")
      CsvImporter.ingest(inputs, database)
      Files.delete(inputs.resolve("ItemTbl.csv"))
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n")
      CsvImporter.ingest(inputs, database)
      assertEquals(rows(database, "SELECT COUNT(*) FROM ChampionTbl"), Vector(Vector("0")))
      assertEquals(rows(database, "SELECT ItemName FROM ItemTbl"), Vector(Vector("B")))
    }
  }

  test("unknown files, wrong headers, empty input and malformed quoted CSV fail explicitly") {
    workspace { (inputs, database) =>
      intercept[IllegalArgumentException](CsvImporter.ingest(inputs, database))
      write(inputs, "Unknown", "x\n1\n")
      intercept[IllegalArgumentException](CsvImporter.ingest(inputs, database))
      Files.delete(inputs.resolve("Unknown.csv"))
      write(inputs, "ChampionTbl", "Wrong,Header\n1,A\n")
      intercept[IllegalArgumentException](CsvImporter.ingest(inputs, database))
      write(inputs, "ChampionTbl", "")
      intercept[IllegalArgumentException](CsvImporter.ingest(inputs, database))
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n1,\"unterminated")
      intercept[Exception](CsvImporter.ingest(inputs, database))
    }
  }

  test("read-only SQL rejects writes; missing databases are not created") {
    workspace { (inputs, database) =>
      intercept[IllegalArgumentException](QueryExecutor.execute(database, "SELECT 1"))
      assert(!Files.exists(database))
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n1,A\n")
      CsvImporter.ingest(inputs, database)
      intercept[java.sql.SQLException](QueryExecutor.execute(database, "DELETE FROM ChampionTbl RETURNING *"))
      intercept[java.sql.SQLException](QueryExecutor.execute(database, "SELECT * FROM Unknown"))
      assertEquals(rows(database, "SELECT COUNT(*) FROM ChampionTbl"), Vector(Vector("1")))
    }
  }

  test("query results retain aliases and nulls and report truncation precisely") {
    workspace { (inputs, database) =>
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n1,A\n2,B\n")
      CsvImporter.ingest(inputs, database)
      val limited = QueryExecutor.execute(database, "SELECT ChampionId AS id, NULL AS absent FROM ChampionTbl ORDER BY id", 1)
      assertEquals(limited.columns, Vector("id", "absent"))
      assertEquals(limited.rows, Vector(Vector(Some("1"), None)))
      assert(limited.truncated)
      assert(!QueryExecutor.execute(database, "SELECT * FROM ChampionTbl", 2).truncated)
      assertEquals(rows(database, "SELECT * FROM ChampionTbl WHERE 0"), Vector.empty)
    }
  }

  test("CLI executes inline and file SQL and rejects conflicting options") {
    workspace { (inputs, database) =>
      write(inputs, "ChampionTbl", "ChampionId,ChampionName\n1,A\n")
      Main.run(Vector("ingest", "--data-dir", inputs.toString, "--db", database.toString))
      val query = inputs.resolve("query.sql")
      Files.writeString(query, "SELECT COUNT(*) AS total FROM ChampionTbl", UTF_8)
      Main.run(Vector("sql", "--file", query.toString, "--db", database.toString))
      Main.run(Vector("sql", "--query", "SELECT 1", "--db", database.toString))
      intercept[IllegalArgumentException](Main.run(Vector("sql", "--query", "SELECT 1", "--file", query.toString)))
      intercept[IllegalArgumentException](Main.run(Vector("ingest", "--unexpected", "x")))
    }
  }
}
