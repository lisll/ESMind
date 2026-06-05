package io.esmind.web;

import io.esmind.compiler.EsRestClient;
import io.esmind.compiler.SchemaRegistry;
import io.esmind.complex.ComplexQueryEngine;
import io.esmind.complex.ExtractDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 科研取数 API 控制器。
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private static final Logger log = LoggerFactory.getLogger(ResearchController.class);

    private final EsRestClient esRestClient;
    private final SchemaRegistry schemaRegistry;

    @Autowired
    public ResearchController(EsRestClient esRestClient, SchemaRegistry schemaRegistry) {
        this.esRestClient = esRestClient;
        this.schemaRegistry = schemaRegistry;
    }

    /**
     * 执行科研数据提取（使用预置模板）。
     */
    @PostMapping("/extract")
    public Map<String, Object> extract(@RequestBody ExtractRequest request) {
        log.info("ResearchController: extract request - patient_id={}, visit_id={}, template={}",
                request.getPatientId(), request.getVisitId(), request.getTemplate());

        try {
            // 1. 创建或获取提取定义
            ExtractDef def;
            if ("术后数据提取".equals(request.getTemplate()) || request.getTemplate() == null) {
                def = ExtractDef.createPostSurgeryExtractDef(request.getPatientId(), request.getVisitId());
            } else {
                def = new ExtractDef();
                def.setResearchName(request.getTemplate());
            }

            // 2. 执行提取
            String indexName = schemaRegistry.getIndexName();
            ComplexQueryEngine engine = new ComplexQueryEngine(esRestClient, indexName);
            ComplexQueryEngine.ExtractResult result = engine.execute(def);

            // 3. 返回结果
            return Map.of(
                    "success", result.isSuccess(),
                    "research_name", result.getResearchName(),
                    "version", result.getVersion(),
                    "steps", result.getStepResults(),
                    "errors", result.getErrors()
            );

        } catch (Exception e) {
            log.error("ResearchController: error during extraction", e);
            return Map.of(
                    "success", false,
                    "error", e.getMessage()
            );
        }
    }

    // --- 请求类 ---

    public static class ExtractRequest {
        private String patientId;
        private String visitId;
        private String template;
        private Object steps; // 内联步骤（可选）

        public String getPatientId() { return patientId; }
        public void setPatientId(String patientId) { this.patientId = patientId; }

        public String getVisitId() { return visitId; }
        public void setVisitId(String visitId) { this.visitId = visitId; }

        public String getTemplate() { return template; }
        public void setTemplate(String template) { this.template = template; }

        public Object getSteps() { return steps; }
        public void setSteps(Object steps) { this.steps = steps; }
    }
}
