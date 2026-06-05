package io.esmind.complex;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 单个提取步骤定义。
 */
public class ExtractStep {

    private String id;
    private String table;
    private List<String> fields;
    private List<Filter> filters;
    private List<String> dependsOn;
    private Strategy strategy;
    private Output output;

    public ExtractStep() {}

    public ExtractStep(String id, String table) {
        this.id = id;
        this.table = table;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTable() { return table; }
    public void setTable(String table) { this.table = table; }

    public List<String> getFields() { return fields; }
    public void setFields(List<String> fields) { this.fields = fields; }

    public List<Filter> getFilters() { return filters; }
    public void setFilters(List<Filter> filters) { this.filters = filters; }

    public List<String> getDependsOn() { return dependsOn; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }

    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }

    public Output getOutput() { return output; }
    public void setOutput(Output output) { this.output = output; }

    // --- 内部类 ---

    public static class Filter {
        private String field;
        private String op;
        private Object value;

        public Filter() {}
        public Filter(String field, String op, Object value) {
            this.field = field;
            this.op = op;
            this.value = value;
        }

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }

        public String getOp() { return op; }
        public void setOp(String op) { this.op = op; }

        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
    }

    public static class Strategy {
        private String type; // "simple", "if_else", "time_range", "threshold"
        private Map<String, Object> params;

        public Strategy() {}
        public Strategy(String type) { this.type = type; }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Map<String, Object> getParams() { return params; }
        public void setParams(Map<String, Object> params) { this.params = params; }
    }

    public static class Output {
        private String format;
        private List<String> fields;

        public Output() {}

        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }

        public List<String> getFields() { return fields; }
        public void setFields(List<String> fields) { this.fields = fields; }
    }
}
