import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class Query {
    public Table table;
    public int limit;
    public String[] select;
    public Predicate<Row> whereCondition = row -> true;

    /**
     * Constructor for Query class
     * @param table - table to query
     * @param limit - limit on the number of rows to return, -1 for no limit
     * @param select - columns to select, null for all columns
     */
    public Query(Table table, int limit, String[] select) {
        this.table = table;
        this.limit = limit;
        this.select = select == null ? table.columns.keySet().toArray(new String[0]) : select;
    }

    public void select(String[] select) {
        this.select = select;
    }

    public void where(Predicate<Row> whereCondition) {
        if (whereCondition == null) {
            this.whereCondition = row -> true;
            return;
        }
        this.whereCondition = whereCondition;
    }

    public List<Row> execute() {
        List<Row> result = new ArrayList<>();
        for (int i = 0; i < table.rowCount; i++) {
            Row row = table.getRow(i);

            if (!whereCondition.test(row)) {
                // System.out.println("Skipping row: " + row.toString());
                continue;
            }

            Object[] projectedValues = new Object[this.select.length];
            
            for (int j = 0; j < this.select.length; j++) {
                String column = this.select[j];
                Integer columnIndex = table.columns.get(column);
                if (columnIndex == null) {
                    throw new IllegalArgumentException("Unknown column in select: " + column);
                }
                projectedValues[j] = row.getValue(columnIndex);
            }

            result.add(new Row(projectedValues));
        }
        
        if (limit >= 0 && result.size() >= limit) {
            return result.subList(0, limit);
        }
        return result;
    }
}