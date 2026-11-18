package oracleslens.cli

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import org.apache.commons.csv.{CSVFormat, CSVPrinter}
import oracleslens.db.QueryExecutor
import oracleslens.ingest.CsvImporter
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

object Main {
  private val usage = """OraclesLens — SQLite storage and SQL
    |  ingest --data-dir DIRECTORY [--db PATH]
    |  sql (--query "SELECT ..." | --file QUERY.sql) [--db PATH]
    |      [--max-rows 200]
    |
    |Default database: .local/oracleslens.sqlite
    |Ingest replaces only the tables supplied in DIRECTORY, atomically.
    |SQL uses a read-only connection and prints TSV; SQL NULL prints as \N.
    |Use one SQL statement per invocation. Run with --help for this message.
    |""".stripMargin

  def main(args: Array[String]): Unit = {
    try run(args.toVector)
    catch { case NonFatal(error) => System.err.println(s"Error: ${error.getMessage}"); sys.exit(1) }
  }

  def run(args: Vector[String]): Unit = {
    if (args.isEmpty || args == Vector("--help") || args == Vector("help")) { println(usage); return }
    val command = args.head
    val allowed = command match {
      case "ingest" => Set("--data-dir", "--db")
      case "sql" => Set("--query", "--file", "--db", "--max-rows")
      case other => throw new IllegalArgumentException(s"Unknown command: $other\n$usage")
    }
    val options = args.tail.grouped(2).foldLeft(Map.empty[String, String]) { (values, pair) =>
      require(pair.size == 2 && allowed(pair.head), s"Unknown option or missing value: ${pair.mkString(" ")}\n$usage")
      require(!values.contains(pair.head), s"Repeated option: ${pair.head}")
      values + (pair.head -> pair(1))
    }
    val database = Paths.get(options.getOrElse("--db", ".local/oracleslens.sqlite"))
    command match {
      case "ingest" =>
        val directory = Paths.get(options.getOrElse("--data-dir", throw new IllegalArgumentException("Missing --data-dir")))
        CsvImporter.ingest(directory, database).foreach { result =>
          println(s"${result.table}: ${result.rows} rows")
        }
        println(s"Database: ${database.toAbsolutePath.normalize()}")
      case "sql" =>
        require(options.contains("--query") != options.contains("--file"), "Supply exactly one of --query or --file")
        val sql = options.getOrElse("--query", Files.readString(Paths.get(options("--file")), UTF_8))
        val result = QueryExecutor.execute(database, sql, options.getOrElse("--max-rows", "200").toInt)
        val printer = new CSVPrinter(new java.io.OutputStreamWriter(System.out, UTF_8), CSVFormat.TDF)
        printer.printRecord(result.columns.asJava)
        result.rows.foreach(row => printer.printRecord(row.map(_.getOrElse("\\N")).asJava))
        printer.flush()
        if (result.truncated) System.err.println(s"Results truncated to ${result.rows.size} rows; increase --max-rows to see more.")
      case _ => ()
    }
  }
}
