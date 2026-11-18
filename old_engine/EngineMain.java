import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EngineMain {
    public static void main(String[] args) {
        try {
            Table table = new Table("/Users/pyotr/code/OraclesLens/Data/MatchStatsTbl.csv");
            System.out.println(table);

            Query query = new Query(table, -1, new String[] {"SummonerMatchFk","Win" });
            query.where(row -> {
                int colIndex = table.columns.get("Lane");
                return row.getValue(colIndex).toString().equals("MIDDLE");
            });
            List<Row> result = query.execute();
            for (Row row : result) {
                System.out.println(row);
            }
            // System.out.println(table);
            // System.out.println(table.rows.get(0));
        } catch (IOException e) {
            System.err.println("Error reading table: " + e.getMessage());
            e.printStackTrace();
        }
    }
}