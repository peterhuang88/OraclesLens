import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

public class Table {
    private String path;
    public HashMap<String, Integer> columns; // name --> index
    public ArrayList<Row> rows;
    public int rowCount;

    /**
     * Constructor for Table class
     * @param path - path to the file containing the table (MUST BE CSV)
     * @throws IOException if the file cannot be read
     */
    public Table(String path) throws IOException {
        this.path = path;
        this.columns = new HashMap<>(); // Initialize columns HashMap
        this.rows = new ArrayList<>(); // Initialize rows ArrayList
        this.rowCount = 0; // Initialize rowCount

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;

            // Read header line and populate columns HashMap
            if ((line = reader.readLine()) != null) {
                String[] columnNames = line.split(",");
                for (int i = 0; i < columnNames.length; i++) {
                    columns.put(columnNames[i].trim(), i); // Store column name and its index, trimming whitespace
                }
            } else {
                throw new IOException("CSV file is empty or has no header.");
            }

            // Read data rows and populate rows ArrayList
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                Row newRow = new Row(values);
                newRow.index = this.rowCount++; // Set the row index
                rows.add(newRow);
            }
        }
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Table(");
        sb.append("columns: ").append(columns.keySet()).append(", ");
        sb.append("rowCount: ").append(rowCount).append(")");
        return sb.toString();
    }

    public Row getRow(int index) {
        return rows.get(index);
    }
}