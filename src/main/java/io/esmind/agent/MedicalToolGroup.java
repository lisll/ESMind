package io.esmind.agent;

import io.esmind.compiler.BusinessSemanticRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * MedicalToolGroup — 动态医疗数据 Tool Group 描述生成器。
 * <p>
 * 在启动时从 BusinessSemanticRegistry 读取所有核心业务表，
 * 生成 Tool Group 的描述文本（description）。
 * <p>
 * 新增业务表只改 table-semantic.yaml，描述自动更新。
 */
public class MedicalToolGroup {

    private static final Logger log = LoggerFactory.getLogger(MedicalToolGroup.class);

    private static final String GROUP_NAME = "medical_tables";
    private static final String GROUP_DESCRIPTION_TEMPLATE =
            "可查询的医疗数据业务表（共 %d 张）：\n%s\n\n"
            + "支持的查询类型：按诊断、检验、检查、处方、手术、病理等过滤，\n"
            + "支持时间范围（近N天/年/月）、聚合统计（按月分布/各科室统计）、\n"
            + "患者ID精确查询。不支持天气、股票等非医疗查询。";

    private final BusinessSemanticRegistry businessRegistry;
    private String description;

    public MedicalToolGroup(BusinessSemanticRegistry businessRegistry) {
        this.businessRegistry = businessRegistry;
    }

    /**
     * 生成 Tool Group 的描述文本。
     * 可多次调用，描述文本不变。
     */
    public String getDescription() {
        if (description == null) {
            description = buildDescription();
        }
        return description;
    }

    public String getGroupName() {
        return GROUP_NAME;
    }

    private String buildDescription() {
        if (businessRegistry == null || !businessRegistry.isLoaded()) {
            return "可查询的医疗数据业务表。当前无业务表配置。";
        }

        String tableList = businessRegistry.getTableConfigs().entrySet().stream()
                .map(this::formatTableEntry)
                .collect(Collectors.joining("\n"));

        log.info("MedicalToolGroup built: {} tables in group '{}'",
                businessRegistry.size(), GROUP_NAME);

        return String.format(GROUP_DESCRIPTION_TEMPLATE,
                businessRegistry.size(), tableList);
    }

    private String formatTableEntry(Map.Entry<String, BusinessSemanticRegistry.TableSemantic> entry) {
        String tableName = entry.getKey();
        BusinessSemanticRegistry.TableSemantic sem = entry.getValue();
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(tableName);
        if (sem.getBusinessName() != null) {
            sb.append(" (").append(sem.getBusinessName()).append(")");
        }
        if (sem.getAliases() != null && !sem.getAliases().isEmpty()) {
            String aliasStr = sem.getAliases().stream()
                    .filter(a -> !a.equals(tableName))
                    .limit(3)
                    .collect(Collectors.joining(", "));
            if (!aliasStr.isEmpty()) {
                sb.append(" [别名: ").append(aliasStr).append("]");
            }
        }
        if (sem.getCategories() != null && !sem.getCategories().isEmpty()) {
            sb.append(" 分类: ").append(String.join(", ", sem.getCategories()));
        }
        return sb.toString();
    }
}
