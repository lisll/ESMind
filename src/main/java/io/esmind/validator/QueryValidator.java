package io.esmind.validator;

import io.esmind.ast.QueryNode;
import io.esmind.compiler.SchemaField;
import io.esmind.compiler.SchemaRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * DSL 静态校验器。
 * 验证 AST 中的字段存在性、nested path 合法性、类型匹配。
 */
public class QueryValidator {

    private static final Logger log = LoggerFactory.getLogger(QueryValidator.class);

    private final SchemaRegistry schema;

    public QueryValidator(SchemaRegistry schema) {
        this.schema = schema;
    }

    /**
     * 校验一个 QueryNode 及其子节点。
     *
     * @return 校验结果，含所有错误
     */
    public ValidationResult validate(QueryNode node) {
        List<String> errors = new ArrayList<>();
        validateNode(node, errors);
        return new ValidationResult(errors);
    }

    private void validateNode(QueryNode node, List<String> errors) {
        if (node instanceof QueryNode.BoolNode) {
            validateBool((QueryNode.BoolNode) node, errors);
        } else if (node instanceof QueryNode.NestedNode) {
            validateNested((QueryNode.NestedNode) node, errors);
        } else if (node instanceof QueryNode.TermNode) {
            validateTerm((QueryNode.TermNode) node, errors);
        } else if (node instanceof QueryNode.MatchPhraseNode) {
            validateFieldExists(((QueryNode.MatchPhraseNode) node).getField(), errors);
        } else if (node instanceof QueryNode.MatchNode) {
            validateFieldExists(((QueryNode.MatchNode) node).getField(), errors);
        } else if (node instanceof QueryNode.RangeNode) {
            validateRange((QueryNode.RangeNode) node, errors);
        }
    }

    private void validateBool(QueryNode.BoolNode bool, List<String> errors) {
        for (QueryNode child : bool.getMust()) validateNode(child, errors);
        for (QueryNode child : bool.getShould()) validateNode(child, errors);
        for (QueryNode child : bool.getFilter()) validateNode(child, errors);
        for (QueryNode child : bool.getMustNot()) validateNode(child, errors);
    }

    private void validateNested(QueryNode.NestedNode nested, List<String> errors) {
        String path = nested.getPath();
        // 检查 nested path 是否存在
        SchemaField anyField = schema.getByFieldName(path);
        if (anyField == null) {
            // 可能 path 只在 nestedPaths 中，检查是否有字段以该 path 开头
            List<SchemaField> fieldsInPath = schema.getFieldsByNestedPath(path);
            if (fieldsInPath.isEmpty()) {
                errors.add("Nested path not found in schema: " + path);
            }
        }
        if (nested.getQuery() != null) {
            validateNode(nested.getQuery(), errors);
        }
    }

    private void validateTerm(QueryNode.TermNode term, List<String> errors) {
        String field = term.getField();
        SchemaField sf = schema.getByFieldName(field);
        if (sf == null) {
            errors.add("Field not found in schema: " + field);
        }
    }

    private void validateRange(QueryNode.RangeNode range, List<String> errors) {
        String field = range.getField();
        SchemaField sf = schema.getByFieldName(field);
        if (sf == null) {
            errors.add("Field not found for range: " + field);
        } else if (!sf.isDateField() && !sf.isNumeric()) {
            errors.add("Range query on non-date/numeric field: " + field + " (type=" + sf.getEsType() + ")");
        }
    }

    private void validateFieldExists(String field, List<String> errors) {
        SchemaField sf = schema.getByFieldName(field);
        if (sf == null) {
            // 尝试模糊查找
            if (schema.fuzzyLookupBizName(field) == null) {
                errors.add("Field not found in schema: " + field);
            }
        }
    }

    public static class ValidationResult {
        private final List<String> errors;

        ValidationResult(List<String> errors) {
            this.errors = errors;
        }

        public boolean hasErrors() { return !errors.isEmpty(); }
        public List<String> getErrors() { return errors; }
        public String summary() {
            if (errors.isEmpty()) return "Validation passed";
            return "Validation failed (" + errors.size() + " errors):\n  - "
                    + String.join("\n  - ", errors);
        }
    }
}
