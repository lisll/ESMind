package io.esmind.web;

import io.esmind.catalog.SemanticCatalogService;
import io.esmind.catalog.SemanticCatalogService.CatalogItem;
import io.esmind.catalog.SemanticCatalogService.ConfirmRequest;
import io.esmind.catalog.SemanticCatalogService.ConfirmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SemanticCatalogController — 语义目录人工确认 API。
 * <p>
 * 提供 SchemaExplorer 自动推断结果的可视化 + 人工确认入口。
 * 全部返回 JSON，Feishu 友好。
 */
@Controller
public class SemanticCatalogController {

    private static final Logger log = LoggerFactory.getLogger(SemanticCatalogController.class);

    private final SemanticCatalogService catalogService;

    public SemanticCatalogController(SemanticCatalogService catalogService) {
        this.catalogService = catalogService;
        log.info("SemanticCatalogController initialized");
    }

    /**
     * 统计摘要：总数 / 已确认 / 待确认 / 各分类分布 / 置信度分布。
     */
    @GetMapping(value = "/api/semantic-catalog/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> stats() {
        try {
            Map<String, Object> stats = catalogService.getStats();
            stats.put("success", true);
            stats.put("esCountLoaded", catalogService.isEsCountLoaded());
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error getting catalog stats", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 批量加载 ES 文档数（耗时约 5-10 秒）。
     * 加载后所有表格查询将包含文档数。
     */
    @PostMapping(value = "/api/semantic-catalog/load-counts", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> loadCounts() {
        log.info("SemanticCatalog: loading ES counts...");
        try {
            catalogService.loadEsCounts();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("loaded", catalogService.isEsCountLoaded());
            result.put("count", catalogService.getEsCountCache() != null ? catalogService.getEsCountCache().size() : 0);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error loading ES counts", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 全部表目录（224 张 top-level 表）：
     * 按置信度排序（LOW → MEDIUM → HIGH），未确认的排在前面。
     */
    @GetMapping(value = "/api/semantic-catalog/tables", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> allTables() {
        try {
            List<CatalogItem> tables = catalogService.getAllTables();
            // 排序：未确认在前 → LOW → MEDIUM → HIGH
            tables.sort((a, b) -> {
                int ya = a.isInYaml() ? 1 : 0;
                int yb = b.isInYaml() ? 1 : 0;
                if (ya != yb) return ya - yb;
                int ca = confidenceOrder(a.getConfidence());
                int cb = confidenceOrder(b.getConfidence());
                return ca - cb;
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("total", tables.size());
            result.put("tables", tables);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error listing catalog tables", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 只列出未确认的表（不在 YAML 中的）。
     */
    @GetMapping(value = "/api/semantic-catalog/unconfirmed", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> unconfirmedTables() {
        try {
            List<CatalogItem> tables = catalogService.getUnconfirmedTables();
            // 按置信度排序：LOW → MEDIUM → HIGH
            tables.sort((a, b) -> {
                int ca = confidenceOrder(a.getConfidence());
                int cb = confidenceOrder(b.getConfidence());
                return ca - cb;
            });

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("total", tables.size());
            result.put("tables", tables);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error listing unconfirmed tables", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    /**
     * 确认一张表的语义配置，写入 table-semantic.yaml。
     * <p>
     * 请求体示例：
     * <pre>
     * {
     *   "tableName": "binglizhenduan",
     *   "businessName": "病理诊断",
     *   "aliases": ["病理", "病理诊断", "病理结果"],
     *   "categories": ["病理"],
     *   "dateFields": ["diagnosis_time"]
     * }
     * </pre>
     * 所有字段均可选 — 用户不传则使用 SchemaExplorer 自动推断的值。
     */
    @PostMapping(value = "/api/semantic-catalog/confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirmTable(@RequestBody ConfirmRequest request) {
        log.info("SemanticCatalog confirm: tableName={}, businessName={}",
                request.tableName, request.businessName);

        if (request.tableName == null || request.tableName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "tableName is required"));
        }

        try {
            ConfirmResult result = catalogService.confirmTable(
                    request.tableName, request.businessName,
                    request.aliases, request.categories, request.dateFields);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.success);
            response.put("message", result.message);
            response.put("tableName", request.tableName);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error confirming table '{}'", request.tableName, e);
            return ResponseEntity.ok(Map.of(
                    "success", false, "error", e.getMessage()));
        }
    }

    /**
     * 批量确认多张表。
     * <p>
     * 请求体示例：
     * <pre>
     * {
     *   "tables": [
     *     {"tableName": "binglizhenduan", "businessName": "病理诊断"},
     *     {"tableName": "jianyanbaogao", ...}
     *   ]
     * }
     * </pre>
     */
    @PostMapping(value = "/api/semantic-catalog/batch-confirm", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> batchConfirm(@RequestBody Map<String, List<ConfirmRequest>> request) {
        List<ConfirmRequest> tables = request.get("tables");
        if (tables == null || tables.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "error", "tables array is required"));
        }

        log.info("SemanticCatalog batch confirm: {} tables", tables.size());
        try {
            List<ConfirmResult> results = catalogService.batchConfirm(tables);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", results.stream().allMatch(r -> r.success));
            response.put("total", results.size());
            response.put("successCount", results.stream().filter(r -> r.success).count());
            response.put("results", results);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in batch confirm", e);
            return ResponseEntity.ok(Map.of("success", false, "error", e.getMessage()));
        }
    }

    private int confidenceOrder(String conf) {
        if (conf == null) return 0;
        switch (conf) {
            case "LOW": return 0;
            case "MEDIUM": return 1;
            case "HIGH": return 2;
            default: return 0;
        }
    }
}
