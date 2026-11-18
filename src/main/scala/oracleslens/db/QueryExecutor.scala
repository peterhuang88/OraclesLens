package oracleslens.db

import java.nio.file.Path
import scala.util.Using

final case class QueryResult(columns: Vector[String], rows: Vector[Vector[Option[String]]], truncated: Boolean)

object QueryExecutor {
  def execute(database: Path, sql: String, maxRows: Int = 200): QueryResult = {
    require(sql.trim.nonEmpty, "SQL must not be empty")
    require(maxRows > 0, "max-rows must be positive")
    Using.resource(Database.open(database, readOnly = true)) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery(sql)) { result =>
          val columns = (1 to result.getMetaData.getColumnCount).map(result.getMetaData.getColumnLabel).toVector
          val rows = Vector.newBuilder[Vector[Option[String]]]
          var count = 0
          while (count < maxRows && result.next()) {
            rows += columns.indices.map(i => Option(result.getString(i + 1))).toVector
            count += 1
          }
          QueryResult(columns, rows.result(), truncated = result.next())
        }
      }
    }
  }
}
