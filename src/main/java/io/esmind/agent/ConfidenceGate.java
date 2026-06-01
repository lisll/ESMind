package io.esmind.agent;

import io.esmind.semantic.SemanticIR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ConfidenceGate — 查询置信度评估与域检测。
 * <p>
 * 职责：
 * <ol>
 *   <li>域检测：UNSUPPORTED（天气/股票/新闻等无关查询）→ 直接拒绝，不走 LLM</li>
 *   <li>置信度评估：基于用户输入和 LLM 解析结果计算置信度</li>
 *   <li>决策输出：EXECUTE / CONFIRM / CANDIDATES / REJECT</li>
 * </ol>
 * <p>
 * 设计原则：LLM 只输出最简 SematicIR，结构化判断由程序完成。
 */
public class ConfidenceGate {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceGate.class);

    /** 明确无关领域的关键词（命中任一即 REJECT） */
    private static final Set<String> UNSUPPORTED_KEYWORDS = new LinkedHashSet<>(Arrays.asList(
            "天气", "股票", "新闻", "财经", "体育", "娱乐", "音乐", "电影",
            "游戏", "动漫", "旅游", "美食", "购物", "科技", "汽车",
            "房价", "汇率", "彩票", "星座", "运势", "命理",
            "你是谁", "你能做什么",
            "help", "test", "测试"
    ));

    /** 问候语关键词 — 返回友好提示，不拒绝 */
    private static final Set<String> GREETING_KEYWORDS = new LinkedHashSet<>(Arrays.asList(
            "你好", "您好", "hello", "hi", "嗨", "早上好", "下午好", "晚上好"
    ));

    /** 医疗领域指示词 — 命中任一即排除 UNSUPPORTED */
    private static final Set<String> MEDICAL_INDICATORS = new LinkedHashSet<>(Arrays.asList(
            "患者", "医生", "医院", "病历", "门诊", "住院", "急诊",
            "诊断", "治疗", "手术", "用药", "处方", "药品", "化验",
            "检验", "检查", "报告", "病理", "出院", "入院", "体温",
            "血压", "心率", "血糖", "血脂", "白细胞", "红细胞",
            "血红蛋白", "转氨酶", "肌酐", "尿素", "肿瘤", "癌",
            "炎", "病", "痛", "烧", "咳", "痰",
            "医嘱", "费用", "护理", "超声", "麻醉"
    ));

    /** 已有 CATEGORY_WORDS 的查询类别（用于领域检测和置信度提升） */
    private static final Set<String> MEDICAL_CATEGORIES = new LinkedHashSet<>(Arrays.asList(
            "检验报告", "化验", "检验", "处方", "药品", "用药", "手术",
            "检查", "检查报告", "辅助检查", "辅助检查结果", "检验检查",
            "病理", "病理报告", "门诊就诊", "门诊就诊记录", "门诊记录",
            "出院小结", "入院记录", "体温", "体温单", "体温记录",
            "病案首页", "首页诊断"
    ));

    /** LLM report_type 值（用于 LLM 输出后的置信度验证） */
    private static final Set<String> KNOWN_REPORT_TYPES = new LinkedHashSet<>(Arrays.asList(
            "lab", "exam", "surgery", "pathology", "outpatient",
            "prescription", "discharge", "admission", "temperature",
            "frontpage", "frontpage_diag",
            // 25 张业务表名（动态加载的，直接做静态兜底）
            "binganshouye", "shouyezhenduan", "menzhenzhenduan",
            "menzhenjiuzhenjilu", "jianyanbaogaofu", "jianchabaogaofu",
            "shoushujilu", "menzhenxiyichufang", "binglizhenduan",
            "hulitizhengyangli", "chuyuanxiaojiexin", "ruyuanjilu",
            "menzhenshuju", "shouyeshoushu", "yizhu",
            "zhuyuanfeiyong", "hulipdayizhu", "hulichuruliangjilu",
            "menzhenfeiyongmingxi", "yangbenkufu",
            "shoumashuzhongyongyao", "shoumamazuijilu",
            "structureddatafu", "BLWS", "chaoshengexamfu"
    ));

    /** 已知医疗实体类型 */
    private static final Set<String> MEDICAL_ENTITY_TYPES = new LinkedHashSet<>(Arrays.asList(
            "disease", "symptom", "department", "lab_item",
            "patient_id", "medicine", "surgery", "exam_item",
            "report_type", "time"
    ));

    // ========================================================================
    // 决策结果
    // ========================================================================

    public enum Decision {
        REJECT,      // 无关查询，直接拒绝（PRE 检查）
        GREETING,    // 问候语，友好回应（PRE 检查）
        EXECUTE,     // LLM 解析成功，直接执行
        CANDIDATES   // LLM 未能解析，提示用户重新描述
    }

    public static class Result {
        private final Decision decision;
        private final double confidence;
        private final String message;
        private final List<String> candidates;
        private final SemanticIR parsedIr;

        public Result(Decision decision, double confidence, String message,
                      List<String> candidates, SemanticIR parsedIr) {
            this.decision = decision;
            this.confidence = confidence;
            this.message = message;
            this.candidates = candidates;
            this.parsedIr = parsedIr;
        }

        public Decision getDecision() { return decision; }
        public double getConfidence() { return confidence; }
        public String getMessage() { return message; }
        public List<String> getCandidates() { return candidates; }
        public SemanticIR getParsedIr() { return parsedIr; }

        public boolean isRejected() { return decision == Decision.REJECT; }
        public boolean isGreeting() { return decision == Decision.GREETING; }
        public boolean needsCandidates() { return decision == Decision.CANDIDATES; }
        // 只要不是 REJECT/GREETING/CANDIDATES，就是可执行的
        public boolean isExecutable() { return decision == Decision.EXECUTE; }
    }

    // ========================================================================
    // 评估方法
    // ========================================================================

    /**
     * 预评估 — 在 LLM 调用之前执行。
     * 只做：UNSUPPORTED → REJECT，问候语 → GREETING，其余 → EXECUTE。
     */
    public Result evaluatePre(String query) {
        if (query == null || query.isBlank()) {
            return new Result(Decision.CANDIDATES, 0.0, "请输入查询内容。", Collections.emptyList(), null);
        }

        String cleaned = query.trim().toLowerCase();

        // 0. 问候语检测 — 友好回应，不拒绝
        String cleanTrimmed = query.trim();
        for (String g : GREETING_KEYWORDS) {
            if (cleanTrimmed.equalsIgnoreCase(g) || cleanTrimmed.startsWith(g + "！") || cleanTrimmed.startsWith(g + "。")) {
                log.info("ConfidenceGate: GREETING detected '{}'", g);
                return new Result(Decision.GREETING, 1.0,
                        "您好！我是医疗数据查询助手。请描述您要查询的内容，例如：患者信息、诊断记录、检验报告、门诊就诊记录等。",
                        Collections.emptyList(), null);
            }
        }

        // 1. UNSUPPORTED 检测
        for (String kw : UNSUPPORTED_KEYWORDS) {
            if (cleaned.contains(kw)) {
                log.info("ConfidenceGate: REJECT (unsupported keyword '{}')", kw);
                return new Result(Decision.REJECT, 0.0,
                        "抱歉，我只能回答医疗数据相关的问题。请描述您要查询的病历数据内容。",
                        Collections.emptyList(), null);
            }
        }

        // 其余全部放行，由 LLM 解析后判断
        log.info("ConfidenceGate: pass PRE for LLM parsing");
        return new Result(Decision.EXECUTE, 0.5, null, Collections.emptyList(), null);
    }

    /**
     * 后评估 — LLM 解析完成后执行。
     * 只判断：能否解析出有用实体 → EXECUTE，否则 → CANDIDATES。
     * 不再基于置信度做中间态确认，LLM 解析成功就直接执行。
     */
    public Result evaluatePost(String query, SemanticIR ir) {
        if (ir == null) {
            return new Result(Decision.CANDIDATES, 0.0, "无法解析您的查询。",
                    Collections.emptyList(), null);
        }

        // 1. 检查是否为空 intent 或无实体
        if (ir.getIntent() == null || ir.getIntent().isBlank()) {
            return new Result(Decision.CANDIDATES, 0.2,
                    "我没能理解您的查询意图。请重新描述您要查询的病历数据。",
                    getDefaultCandidates(), ir);
        }

        List<SemanticIR.Entity> entities = ir.getEntities();
        if (entities == null || entities.isEmpty()) {
            return new Result(Decision.CANDIDATES, 0.3,
                    "请指定要查询的内容，例如：患者、诊断名称、检验项目等。",
                    getDefaultCandidates(), ir);
        }

        // 2. 检查 entity types 是否都在医疗领域内
        boolean hasMedicalEntity = false;
        for (SemanticIR.Entity e : entities) {
            if (MEDICAL_ENTITY_TYPES.contains(e.getType())) {
                hasMedicalEntity = true;
                break;
            }
        }

        if (!hasMedicalEntity) {
            return new Result(Decision.CANDIDATES, 0.3,
                    "您的查询似乎不涉及医疗数据。请描述病历数据相关的内容。",
                    getDefaultCandidates(), ir);
        }

        // 3. LLM 成功解析 → 直接执行
        log.info("ConfidenceGate: POST EXECUTE (intent={}, entities={})", ir.getIntent(), entities.size());
        return new Result(Decision.EXECUTE, 0.9, null, Collections.emptyList(), ir);
    }

    // ========================================================================
    // 置信度计算
    // ========================================================================

    /**
     * 粗略预计算 — 基于用户输入文本匹配。
     */
    private double calculateRoughConfidence(String cleaned) {
        double score = 0.0;

        // 医疗类别词命中
        for (String cat : MEDICAL_CATEGORIES) {
            if (cleaned.contains(cat)) {
                score += 0.5;
                break;
            }
        }

        // 医疗指示词命中
        for (String ind : MEDICAL_INDICATORS) {
            if (cleaned.contains(ind)) {
                score += 0.15;
            }
        }

        // 明确的病历查询句式
        if (cleaned.contains("查询") || cleaned.contains("统计") || cleaned.contains("查看")
                || cleaned.contains("找") || cleaned.contains("有多少") || cleaned.contains("分布")) {
            score += 0.1;
        }

        // 患者 ID 格式（数字串）
        if (cleaned.matches(".*\\d{6,}.*")) {
            score += 0.2;
        }

        return Math.min(1.0, score);
    }

    /**
     * 精确后计算 — 基于 LLM 输出的 SemanticIR + 原始 query 信号。
     */
    private double calculatePostConfidence(String query, SemanticIR ir) {
        List<SemanticIR.Entity> entities = ir.getEntities();
        double score = 0.0;
        int entityCount = entities != null ? entities.size() : 0;

        // 基础分：有实体就给分
        if (entityCount > 0) score += 0.3;
        if (entityCount >= 2) score += 0.15;

        // intent 清晰
        String intent = ir.getIntent();
        if ("patient_search".equals(intent) || "patient_count".equals(intent)) {
            score += 0.2;
        }

        // 有 report_type 且值有效
        boolean hasReportType = false;
        for (SemanticIR.Entity e : entities) {
            if ("report_type".equals(e.getType())) {
                hasReportType = true;
                String val = e.getValue();
                if (val != null && KNOWN_REPORT_TYPES.contains(val)) {
                    score += 0.3;
                } else if (val != null) {
                    score += 0.1;
                }
                break;
            }
        }

        // 有 disease/symptom/lab_item 等具体实体
        for (SemanticIR.Entity e : entities) {
            String type = e.getType();
            if ("disease".equals(type)) score += 0.3;
            else if ("lab_item".equals(type)) score += 0.25;
            else if ("medicine".equals(type)) score += 0.25;
            else if ("patient_id".equals(type)) score += 0.3;
            else if ("department".equals(type)) score += 0.2;
            else if ("surgery".equals(type)) score += 0.2;
        }

        // 聚合查询加分
        if (ir.getAggregation() != null) {
            score += 0.1;
        }

        // === 原始 query 信号提升 ===
        // 当 query 明确包含医疗类别词且 LLM 解析出相应 report_type 时，额外加分
        if (query != null) {
            String queryLower = query.trim().toLowerCase();

            // 1. 类别直接匹配：query 有 CATEGORY 词 + LLM 解析出一致 report_type
            boolean hasCategoryWord = false;
            String matchedCategory = null;
            for (String cat : MEDICAL_CATEGORIES) {
                if (queryLower.contains(cat)) {
                    hasCategoryWord = true;
                    matchedCategory = cat;
                    break;
                }
            }
            if (hasCategoryWord) {
                score += 0.2; // query 中明确提到了医疗类别
                // 如果 LLM 也解析出了 report_type，再加分
                if (hasReportType) {
                    score += 0.1;
                }
            }

            // 2. 计数类查询词（"多少条"、"数量"、"总数"等）
            if (queryLower.contains("多少条") || queryLower.contains("数量")
                    || queryLower.contains("总数") || queryLower.contains("一共")) {
                score += 0.1;
                if ("patient_count".equals(intent) || intent == null) {
                    score += 0.05; // 意图匹配
                }
            }

            // 3. 患者查询词
            if (queryLower.contains("患者") || queryLower.contains("病人")) {
                score += 0.1;
            }
        }

        // 减分：只有 time 实体没有其他实体
        if (entityCount == 1 && entities != null && "time".equals(entities.get(0).getType())) {
            score -= 0.2;
        }

        // 减分：只有 report_type 没有其他实体且 query 也没有强信号（可能是误匹配）
        if (entityCount == 1 && hasReportType) {
            score -= 0.1;
        }

        // 减分/封顶：原始 query 无医疗信号时，LLM 输出可能是幻觉
        if (query != null) {
            double querySignal = calculateRoughConfidence(query.trim().toLowerCase());
            if (querySignal < 0.2) {
                score = Math.min(score, 0.65); // 最多 CONFIRM，不能 EXECUTE
                // 短查询且无中文字符 → 极可能是幻觉
                if (query.trim().length() <= 3 && !containsChinese(query.trim())) {
                    score = Math.min(score, 0.4); // 降到 CANDIDATES
                }
            }
        }

        // 减分：单个文字作为 disease（通常是误匹配）
        if (entityCount == 1 && entities != null) {
            String v = entities.get(0).getValue();
            if (v != null && v.length() <= 1 && "disease".equals(entities.get(0).getType())) {
                score -= 0.3;
            }
        }

        return Math.max(0.0, Math.min(1.0, score));
    }

    // ========================================================================
    // 消息生成
    // ========================================================================

    private String buildConfirmMessage(SemanticIR ir) {
        StringBuilder sb = new StringBuilder("您说的是不是");

        List<String> parts = new ArrayList<>();
        for (SemanticIR.Entity e : ir.getEntities()) {
            String type = e.getType();
            String val = e.getValue();
            if ("report_type".equals(type)) {
                parts.add("查询" + val + "数据");
            } else if ("disease".equals(type)) {
                parts.add("“" + val + "”诊断");
            } else if ("lab_item".equals(type)) {
                parts.add("“" + val + "”检验");
            } else if ("patient_id".equals(type)) {
                parts.add("患者" + val);
            } else if ("department".equals(type)) {
                parts.add("“" + val + "”科室");
            } else if ("medicine".equals(type)) {
                parts.add("“" + val + "”用药");
            }
        }

        if (parts.isEmpty()) {
            sb.append("查询医疗数据");
        } else {
            sb.append(String.join("和", parts));
        }

        sb.append("？请回复“是”确认，或重新描述。");
        return sb.toString();
    }

    private String buildCandidateMessage(SemanticIR ir) {
        StringBuilder sb = new StringBuilder("您想查询什么？");
        List<String> parts = new ArrayList<>();
        for (SemanticIR.Entity e : ir.getEntities()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                parts.add(e.getValue());
            }
        }
        if (!parts.isEmpty()) {
            sb.append("（我理解到：").append(String.join(", ", parts)).append("）");
        }
        return sb.toString();
    }

    private List<String> getDefaultCandidates() {
        return Arrays.asList(
                "查询患者病历信息",
                "统计诊断分布",
                "查看检验报告",
                "查询门诊就诊记录"
        );
    }

    /** 检查字符串是否包含中文字符（CJK统一表意文字） */
    private static boolean containsChinese(String s) {
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (cp >= 0x4E00 && cp <= 0x9FFF) return true;
            i += Character.charCount(cp);
        }
        return false;
    }
}
