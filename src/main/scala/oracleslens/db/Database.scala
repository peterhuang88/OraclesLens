package oracleslens.db

import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import org.sqlite.SQLiteConfig
import scala.util.Using

object Database {
  def open(path: Path, readOnly: Boolean = false): Connection = {
    val absolute = path.toAbsolutePath.normalize()
    if (readOnly) require(Files.isRegularFile(absolute), s"Database does not exist: $absolute. Run ingest first.")
    else Option(absolute.getParent).foreach(Files.createDirectories(_))

    val config = new SQLiteConfig()
    config.setBusyTimeout(5000)
    config.setReadOnly(readOnly)
    val connection = DriverManager.getConnection(s"jdbc:sqlite:${absolute.toUri}", config.toProperties)
    try {
      if (readOnly) Using.resource(connection.createStatement())(_.execute("PRAGMA query_only = ON"))
      connection
    } catch {
      case error: Throwable => connection.close(); throw error
    }
  }
}
