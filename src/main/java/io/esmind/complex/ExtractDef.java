package io.esmind.complex;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 完整的提取任务定义。
 */
public class ExtractDef {

    private String researchName;
    private String version;
    private Map<String, Object> sources;
    private List<ExtractStep> steps;

    public ExtractDef() {
        this.steps = new ArrayList<>();
        this.sources = new HashMap<>();
    }

    public String getResearchName() { return researchName; }
    public void setResearchName(String researchName) { this.researchName = researchName; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public Map<String, Object> getSources() { return sources; }
    public void setSources(Map<String, Object> sources) { this.sources = sources; }

    public List<ExtractStep> getSteps() { return steps; }
    public void setSteps(List<ExtractStep> steps) { this.steps = steps; }

    /**
     * 从参数创建默认的科研取数定义（术后数据提取）。
     */
    public static ExtractDef createPostSurgeryExtractDef(String patientId, String visitId) {
        ExtractDef def = new ExtractDef();
        def.setResearchName("术后数据提取");
        def.setVersion("1.0");

        Map<String, Object> sources = new HashMap<>();
        sources.put("patient_id", patientId);
        sources.put("visit_id", visitId);
        def.setSources(sources);

        // Step 1: 手术时间
        ExtractStep step1 = new ExtractStep("surgery_time", "shoushujilu");
        step1.setFields(List.of("operation_name", "operation_time", "type_of_anesthesia", "postoperative_diagnosis"));
        step1.setStrategy(new ExtractStep.Strategy("simple"));
        def.getSteps().add(step1);

        // Step 2: 异常体温
        ExtractStep step2 = new ExtractStep("temperature", "hulitizhengyangli");
        step2.setFields(List.of("record_time", "vital_sign_value", "vital_sign_unit"));
        step2.setFilters(List.of(new ExtractStep.Filter("hulitizhengyangli.vital_type_name", "=", "体温")));
        step2.setDependsOn(List.of("surgery_time"));
        step2.setStrategy(new ExtractStep.Strategy("if_else"));
        def.getSteps().add(step2);

        // Step 3: 入院记录
        ExtractStep step3 = new ExtractStep("admission_record", "ruyuanjilu");
        step3.setFields(List.of(
                "chief_complaint.src",
                "history_of_present_illness.src",
                "history_of_past_illness.src",
                "social_history.src",
                "menses_childbirth.src",
                "family_member_diseases_history.src",
                "physical_examination.src",
                "special_examination.src",
                "diagnosis_name_src"
        ));
        step3.setStrategy(new ExtractStep.Strategy("simple"));
        def.getSteps().add(step3);

        return def;
    }
}
