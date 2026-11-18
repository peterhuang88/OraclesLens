import java.util.StringJoiner;

public class Row {
    public int index;
    public Object[] values;

    public Row(Object[] values) {
        this.values = values;
    }

    public Object getValue(int index) {
        return values[index];
    }

    public String toString() {
        StringJoiner joiner = new StringJoiner(", ", "Row(", ")");
        for (Object value : values) {
            joiner.add(String.valueOf(value));
        }
        return joiner.toString();
    }
}