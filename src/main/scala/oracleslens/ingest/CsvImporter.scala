package oracleslens.ingest

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.{DigestInputStream, MessageDigest}
import java.time.Instant
import org.apache.commons.csv.{CSVFormat, CSVParser}
import oracleslens.db.{Database, Schema, TableSchema}
import scala.jdk.CollectionConverters._
import scala.util.Using

final case class ImportResult(table: String, rows: Long, sha256: String)

object CsvImporter {
  private val format = CSVFormat.RFC4180.builder()
    .setHeader().setSkipHeaderRecord(true).get()

  /** Every supplied CSV is one table snapshot. All replacements commit together. */
  def ingest(directory: Path, database: Path): Vector[ImportResult] = {
    require(Files.isDirectory(directory), s"Not a data directory: $directory")
    val files = Using.resource(Files.list(directory)) { paths =>
      paths.iterator().asScala.filter(p => Files.isRegularFile(p) && p.getFileName.toString.toLowerCase.endsWith(".csv"))
        .toVector.sortBy(_.getFileName.toString)
    }
    require(files.nonEmpty, s"No CSV files found in $directory")
    val sources = files.map { path =>
      val schema = Schema.tables.find(t => path.getFileName.toString == s"${t.name}.csv")
        .getOrElse(throw new IllegalArgumentException(s"Unsupported CSV: ${path.getFileName}. Supported: ${Schema.tables.map(_.name + ".csv").mkString(", ")}"))
      path -> schema
    }

    Using.resource(Database.open(database)) { connection =>
      connection.setAutoCommit(false)
      try {
        Using.resource(connection.createStatement()) { statement =>
          statement.executeUpdate("""CREATE TABLE IF NOT EXISTS _oracles_imports (
            table_name TEXT PRIMARY KEY, source_path TEXT NOT NULL, sha256 TEXT NOT NULL,
            row_count INTEGER NOT NULL, imported_at TEXT NOT NULL)""")
        }
        val results = sources.map { case (path, schema) =>
          Using.resource(connection.createStatement()) { statement =>
            val columns = schema.columns.map(c => s"${Schema.quote(c.name)} ${c.affinity}").mkString(", ")
            statement.executeUpdate(s"CREATE TABLE IF NOT EXISTS ${Schema.quote(schema.name)} ($columns)")
            statement.executeUpdate(s"DELETE FROM ${Schema.quote(schema.name)}")
          }
          val result = importFile(path, schema, connection)
          Using.resource(connection.prepareStatement("""INSERT INTO _oracles_imports
            (table_name, source_path, sha256, row_count, imported_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(table_name) DO UPDATE SET source_path=excluded.source_path,
            sha256=excluded.sha256, row_count=excluded.row_count, imported_at=excluded.imported_at""")) { statement =>
            statement.setString(1, schema.name)
            statement.setString(2, path.toAbsolutePath.normalize().toString)
            statement.setString(3, result.sha256)
            statement.setLong(4, result.rows)
            statement.setString(5, Instant.now().toString)
            statement.executeUpdate()
          }
          result
        }
        connection.commit()
        results
      } catch {
        case scala.util.control.NonFatal(error) => connection.rollback(); throw error
      }
    }
  }

  private def importFile(path: Path, schema: TableSchema, connection: java.sql.Connection): ImportResult = {
    // Hash the same byte stream we parse, rather than reading a possibly changed file twice.
    val digest = MessageDigest.getInstance("SHA-256")
    Using.Manager { use =>
      val input = use(new DigestInputStream(Files.newInputStream(path), digest))
      val reader = use(new java.io.BufferedReader(new java.io.InputStreamReader(input, UTF_8)))
      // Accept a UTF-8 BOM without treating it as part of the first column name.
      reader.mark(1)
      if (reader.read() != 0xfeff) reader.reset()
      val parser = use(CSVParser.parse(reader, format))
      val headers = parser.getHeaderNames.asScala.toVector
      require(headers == schema.columns.map(_.name),
        s"${path.getFileName}: expected header ${schema.columns.map(_.name).mkString(",")}; found ${headers.mkString(",")}")
      val columns = headers.map(Schema.quote).mkString(", ")
      val placeholders = headers.map(_ => "?").mkString(", ")
      val statement = use(connection.prepareStatement(s"INSERT INTO ${Schema.quote(schema.name)} ($columns) VALUES ($placeholders)"))
      var count = 0L
      parser.iterator().asScala.foreach { record =>
        require(record.size() == headers.size,
          s"${path.getFileName}: record ${record.getRecordNumber} has ${record.size()} fields; expected ${headers.size}")
        // Bind source strings verbatim. SQLite applies declared affinity to numeric values;
        // blanks and nonnumeric text remain text, without inventing a null policy.
        headers.indices.foreach(i => statement.setString(i + 1, record.get(i)))
        statement.addBatch()
        count += 1
        if (count % 1000 == 0) { statement.executeBatch(); statement.clearBatch() }
      }
      if (count % 1000 != 0) statement.executeBatch()
      ImportResult(schema.name, count, digest.digest().map(b => f"${b & 0xff}%02x").mkString)
    }.get
  }
}
