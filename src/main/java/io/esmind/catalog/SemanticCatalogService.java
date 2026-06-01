package io.esmind.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.esmind.compiler.BusinessSemanticRegistry;
import io.esmind.compiler.SchemaExplorer;
import io.esmind.compiler.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * SemanticCatalogService — 语义目录服务。
 * <p>
 * 提供 SchemaExplorer 自动推断结果 + YAML 人工确认的桥梁。
 * 职责：
 * <ol>
 *   <li>列出全部 top-level 表及自动推断的分类、置信度</li>
 *   <li>标记哪些表已在 YAML 中、哪些待确认</li>
 *   <li>接受人工确认/纠正，写入 table-semantic.yaml</li>
 * </ol>
 */
@Service
public class SemanticCatalogService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCatalogService.class);

    private final SchemaExplorer schemaExplorer;
    private final BusinessSemanticRegistry businessRegistry;

    /** YAML 源文件路径（classpath 源，开发时可写） */
    private final String yamlSourcePath;

    /** 已知分类的中文显示名 */
    private static final Map<String, String> CATEGORY_CN = new LinkedHashMap<>();
    static {
        CATEGORY_CN.put("diagnosis", "诊断");
        CATEGORY_CN.put("order", "医嘱");
        CATEGORY_CN.put("lab", "检验");
        CATEGORY_CN.put("exam", "检查");
        CATEGORY_CN.put("surgery", "手术");
        CATEGORY_CN.put("prescription", "处方/药品");
        CATEGORY_CN.put("discharge", "出院");
        CATEGORY_CN.put("admission", "入院");
        CATEGORY_CN.put("temperature", "体温");
        CATEGORY_CN.put("fee", "费用");
        CATEGORY_CN.put("anesthesia", "麻醉");
        CATEGORY_CN.put("pathology", "病理");
        CATEGORY_CN.put("ultrasound", "超声");
        CATEGORY_CN.put("death", "死亡");
        CATEGORY_CN.put("blood", "输血");
        CATEGORY_CN.put("consent", "知情同意书");
        CATEGORY_CN.put("nursing", "护理");
        CATEGORY_CN.put("unknown", "未分类");
    }

    /** 表名 → 建议的中文业务名（去除拼音后缀） */
    private static final Map<String, String> TABLE_BIZ_NAME_HINT = new LinkedHashMap<>();
    static {
        TABLE_BIZ_NAME_HINT.put("binganshouye", "病案首页");
        TABLE_BIZ_NAME_HINT.put("binglizhenduan", "病理诊断");
        TABLE_BIZ_NAME_HINT.put("binglizhenduanshu", "病理诊断书");
        TABLE_BIZ_NAME_HINT.put("bingrensiwang", "病人死亡");
        TABLE_BIZ_NAME_HINT.put("bingweitongzhishu", "病危通知书");
        TABLE_BIZ_NAME_HINT.put("chaoshengcaogaobaogao", "超声草稿报告");
        TABLE_BIZ_NAME_HINT.put("chaoshengexam", "超声检查");
        TABLE_BIZ_NAME_HINT.put("chaoshengexamfu", "超声检查附");
        TABLE_BIZ_NAME_HINT.put("chuangweifenpei", "床位分配");
        TABLE_BIZ_NAME_HINT.put("chuliangedc", "处理EDC");
        TABLE_BIZ_NAME_HINT.put("chuyuanjilu", "出院记录");
        TABLE_BIZ_NAME_HINT.put("chuyuanxiaojie", "出院小结");
        TABLE_BIZ_NAME_HINT.put("chuyuanxiaojiexin", "出院小结新");
        TABLE_BIZ_NAME_HINT.put("ershisixiaoshineiruchuyuanjilu", "24小时内出入院记录");
        TABLE_BIZ_NAME_HINT.put("ershisixiaoshineiruyuansiwangjilu", "24小时内入院死亡记录");
        TABLE_BIZ_NAME_HINT.put("fenmianjilu", "分娩记录");
        TABLE_BIZ_NAME_HINT.put("ganranyujing", "感染预警");
        TABLE_BIZ_NAME_HINT.put("gaozhihaocai", "告知耗材");
        TABLE_BIZ_NAME_HINT.put("gudingzichan", "固定资产");
        TABLE_BIZ_NAME_HINT.put("guominyuan", "过敏原");
        TABLE_BIZ_NAME_HINT.put("gusuixibao", "骨髓细胞");
        TABLE_BIZ_NAME_HINT.put("gusuixibaofu", "骨髓细胞附");
        TABLE_BIZ_NAME_HINT.put("huanzhejibenxinxi", "患者基本信息");
        TABLE_BIZ_NAME_HINT.put("huanzheshuxuebuliangfanying", "患者输血不良反应");
        TABLE_BIZ_NAME_HINT.put("huizhenjilu", "会诊记录");
        TABLE_BIZ_NAME_HINT.put("huizhenjilustruct", "会诊记录结构化");
        TABLE_BIZ_NAME_HINT.put("huizhenshenqing", "会诊申请");
        TABLE_BIZ_NAME_HINT.put("huizhenshenqingmingxi", "会诊申请明细");
        TABLE_BIZ_NAME_HINT.put("hulibiaobenxinxi", "护理标本信息");
        TABLE_BIZ_NAME_HINT.put("hulichuruliangjilu", "护理出入量记录");
        TABLE_BIZ_NAME_HINT.put("huliedc", "护理EDC");
        TABLE_BIZ_NAME_HINT.put("hulijianhushuju", "护理监护数据");
        TABLE_BIZ_NAME_HINT.put("hulipdayizhu", "护理PD医嘱");
        TABLE_BIZ_NAME_HINT.put("hulipinggubiaomx", "护理评估表明细");
        TABLE_BIZ_NAME_HINT.put("hulipingguxinxibiao", "护理评估信息表");
        TABLE_BIZ_NAME_HINT.put("hulishoushuliu", "护理手术流");
        TABLE_BIZ_NAME_HINT.put("hulishuxuexinxi", "护理输血信息");
        TABLE_BIZ_NAME_HINT.put("hulitizhengyangli", "护理体温测量");
        TABLE_BIZ_NAME_HINT.put("huliwenshumingxi", "护理文书明细");
        TABLE_BIZ_NAME_HINT.put("hulixunshixinxi", "护理巡视信息");
        TABLE_BIZ_NAME_HINT.put("hulizhixing", "护理执行");
        TABLE_BIZ_NAME_HINT.put("hulizhuanjiaojiexinxi", "护理交接信息");
        TABLE_BIZ_NAME_HINT.put("huxijiedc", "呼吸机EDC");
        TABLE_BIZ_NAME_HINT.put("jiagongshujumoxing", "加工数据模型");
        TABLE_BIZ_NAME_HINT.put("jianchabaogao_src", "检查报告源");
        TABLE_BIZ_NAME_HINT.put("jianchabaogaofu", "检查报告附");
        TABLE_BIZ_NAME_HINT.put("jianchashenqingfu", "检查申请附");
        TABLE_BIZ_NAME_HINT.put("jianyanbaogao", "检验报告");
        TABLE_BIZ_NAME_HINT.put("jianyanbaogaofu", "检验报告附");
        TABLE_BIZ_NAME_HINT.put("jianyanbaogaomingxifu", "检验报告明细附");
        TABLE_BIZ_NAME_HINT.put("jianyanbaogaozhubiaofu", "检验报告主表附");
        TABLE_BIZ_NAME_HINT.put("jianyanshenqingfu", "检验申请附");
        TABLE_BIZ_NAME_HINT.put("jiaojiebanjilu", "交接班记录");
        TABLE_BIZ_NAME_HINT.put("jieduanxiaojie", "阶段小结");
        TABLE_BIZ_NAME_HINT.put("jizhenbingchengjilu", "急诊病程记录");
        TABLE_BIZ_NAME_HINT.put("jizhenliuguan", "急诊留观");
        TABLE_BIZ_NAME_HINT.put("jizhenqiangjiujilu", "急诊抢救记录");
        TABLE_BIZ_NAME_HINT.put("linchuanglujing", "临床路径");
        TABLE_BIZ_NAME_HINT.put("linchuangshuxueshenqing", "临床输血申请");
        TABLE_BIZ_NAME_HINT.put("mazuishuqianfangshijilu", "麻醉术前访视记录");
        TABLE_BIZ_NAME_HINT.put("mazuishuqianshuhoufangshijilu", "麻醉术前术后访视");
        TABLE_BIZ_NAME_HINT.put("mazuitongyishu", "麻醉同意书");
        TABLE_BIZ_NAME_HINT.put("menzhenchaoshengexam", "门诊超声检查");
        TABLE_BIZ_NAME_HINT.put("menzhenfeiyongmingxi", "门诊费用明细");
        TABLE_BIZ_NAME_HINT.put("menzhenguahaodengji", "门诊挂号登记");
        TABLE_BIZ_NAME_HINT.put("menzhenguahaoyuyue", "门诊挂号预约");
        TABLE_BIZ_NAME_HINT.put("menzhengusuixibao", "门诊骨髓细胞");
        TABLE_BIZ_NAME_HINT.put("menzhenhulibingqujilubiao", "门诊护理病区记录表");
        TABLE_BIZ_NAME_HINT.put("menzhenjianchabaogao_src", "门诊检查报告源");
        TABLE_BIZ_NAME_HINT.put("menzhenjianchashenqing", "门诊检查申请");
        TABLE_BIZ_NAME_HINT.put("menzhenjianyanbaogao", "门诊检验报告");
        TABLE_BIZ_NAME_HINT.put("menzhenjianyanshenqing", "门诊检验申请");
        TABLE_BIZ_NAME_HINT.put("menzhenjiegouhuashuju", "门诊结构化数据");
        TABLE_BIZ_NAME_HINT.put("menzhenjiuzhenjilu", "门诊就诊记录");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhendiseaserecord", "门诊急诊疾病记录");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenfenzhen", "门诊急诊分诊");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenhuanzhezhenduan", "门诊急诊患者诊断");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenhuli", "门诊急诊护理");
        TABLE_BIZ_NAME_HINT.put("menzhenjizheninoutrecord", "门诊急诊出入记录");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenlvsetongdaonew", "门诊急诊绿色通道");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenorder", "门诊急诊医嘱");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenorderexecrecord", "门诊急诊医嘱执行");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenpatientinfo", "门诊急诊患者信息");
        TABLE_BIZ_NAME_HINT.put("menzhenjizhenpatientvitalsign", "门诊急诊生命体征");
        TABLE_BIZ_NAME_HINT.put("menzhenshoumajilu", "门诊数码记录");
        TABLE_BIZ_NAME_HINT.put("menzhenshoushujilu", "门诊手术记录");
        TABLE_BIZ_NAME_HINT.put("menzhenshouyeshoushu", "门诊首页手术");
        TABLE_BIZ_NAME_HINT.put("menzhenshouyezhenduan", "门诊首页诊断");
        TABLE_BIZ_NAME_HINT.put("menzhenshuju", "门诊数据");
        TABLE_BIZ_NAME_HINT.put("menzhenshujukouqiang", "门诊数据口腔");
        TABLE_BIZ_NAME_HINT.put("menzhenshujulianghua", "门诊数据量化");
        TABLE_BIZ_NAME_HINT.put("menzhenweishengwujianyan", "门诊微生物检验");
        TABLE_BIZ_NAME_HINT.put("menzhenweishengwujianyanbaogaomingxi", "门诊微生物检验报告明细");
        TABLE_BIZ_NAME_HINT.put("menzhenweishengwujianyanbaogaozhubiao", "门诊微生物检验报告主表");
        TABLE_BIZ_NAME_HINT.put("menzhenweizhiwenshu", "门诊未知文书");
        TABLE_BIZ_NAME_HINT.put("menzhenxindianjianhudan", "门诊心电监护单");
        TABLE_BIZ_NAME_HINT.put("menzhenxiyichufang", "门诊西药处方");
        TABLE_BIZ_NAME_HINT.put("menzhenyangbenku", "门诊样本库");
        TABLE_BIZ_NAME_HINT.put("menzhenzhenduan", "门诊诊断");
        TABLE_BIZ_NAME_HINT.put("peinfo", "体检信息");
        TABLE_BIZ_NAME_HINT.put("peixuejilu", "配血记录");
        TABLE_BIZ_NAME_HINT.put("pemasterrecoder", "体检主记录");
        TABLE_BIZ_NAME_HINT.put("peresult", "体检结果");
        TABLE_BIZ_NAME_HINT.put("pingfenbiao", "评分表");
        TABLE_BIZ_NAME_HINT.put("qiangjiujilu", "抢救记录");
        TABLE_BIZ_NAME_HINT.put("quanyuanhuizhenjilu", "全院会诊记录");
        TABLE_BIZ_NAME_HINT.put("rehabilitationinforecord", "康复信息记录");
        TABLE_BIZ_NAME_HINT.put("richangbingchengjilu", "日常病程记录");
        TABLE_BIZ_NAME_HINT.put("rijianshoushuruchuyuanjilu", "日间手术出入院记录");
        TABLE_BIZ_NAME_HINT.put("ruliangedc", "入量EDC");
        TABLE_BIZ_NAME_HINT.put("ruyuanjilu", "入院记录");
        TABLE_BIZ_NAME_HINT.put("scoreedc", "评分EDC");
        TABLE_BIZ_NAME_HINT.put("shangjiyishichafanglu", "上级医师查房录");
        TABLE_BIZ_NAME_HINT.put("shangjiyishishoucibingcheng", "上级医师首次病程");
        TABLE_BIZ_NAME_HINT.put("shoucibingchengjilu", "首次病程记录");
        TABLE_BIZ_NAME_HINT.put("shoumamazuijilu", "术前麻醉记录");
        TABLE_BIZ_NAME_HINT.put("shoumashoushushenqing", "术前手术申请");
        TABLE_BIZ_NAME_HINT.put("shoumashoushuzhentong", "术前手术镇痛");
        TABLE_BIZ_NAME_HINT.put("shoumashuhoufangshi", "术后访视");
        TABLE_BIZ_NAME_HINT.put("shoumashuhouhuifu", "术后恢复");
        TABLE_BIZ_NAME_HINT.put("shoumashuzhongyongyao", "术中用药");
        TABLE_BIZ_NAME_HINT.put("shouquanweituoshu", "授权委托书");
        TABLE_BIZ_NAME_HINT.put("shoushuanquanhechabiao", "手术安全检查表");
        TABLE_BIZ_NAME_HINT.put("shoushuanquanhechajilu", "手术安全检查记录");
        TABLE_BIZ_NAME_HINT.put("shoushuguocheng", "手术过程");
        TABLE_BIZ_NAME_HINT.put("shoushuhulijilu", "手术护理记录");
        TABLE_BIZ_NAME_HINT.put("shoushujilu", "手术记录");
        TABLE_BIZ_NAME_HINT.put("shoushuqingdianjilu", "手术清点记录");
        TABLE_BIZ_NAME_HINT.put("shoushutongyishu", "手术同意书");
        TABLE_BIZ_NAME_HINT.put("shouyeshoushu", "首页手术");
        TABLE_BIZ_NAME_HINT.put("shouyeshushi", "首页事宜");
        TABLE_BIZ_NAME_HINT.put("shouyezhenduan", "首页诊断");
        TABLE_BIZ_NAME_HINT.put("shuhoubingchengjilu", "术后病程记录");
        TABLE_BIZ_NAME_HINT.put("shuhoushoucibingchengjilu", "术后首次病程记录");
        TABLE_BIZ_NAME_HINT.put("shuhoushoucishangjichafang", "术后首次上级查房");
        TABLE_BIZ_NAME_HINT.put("shuqianfangshi", "术前访视");
        TABLE_BIZ_NAME_HINT.put("shuqiantaolunjilu", "术前讨论记录");
        TABLE_BIZ_NAME_HINT.put("shuqianxiaojie", "术前小结");
        TABLE_BIZ_NAME_HINT.put("shuxuejilu", "输血记录");
        TABLE_BIZ_NAME_HINT.put("shuxueshenqing", "输血申请");
        TABLE_BIZ_NAME_HINT.put("shuxuezhiliaozhiqingtongyishu", "输血治疗知情同意书");
        TABLE_BIZ_NAME_HINT.put("siwangbinglitaolunjielun", "死亡病例讨论结论");
        TABLE_BIZ_NAME_HINT.put("siwangbinglitaolunjilu", "死亡病例讨论记录");
        TABLE_BIZ_NAME_HINT.put("siwangjilu", "死亡记录");
        TABLE_BIZ_NAME_HINT.put("structureddatafu", "结构化数据附");
        TABLE_BIZ_NAME_HINT.put("suifangjilu", "随访记录");
        TABLE_BIZ_NAME_HINT.put("teshujianchatongyishu", "特殊检查同意书");
        TABLE_BIZ_NAME_HINT.put("vitalSignsedc", "生命体征EDC");
        TABLE_BIZ_NAME_HINT.put("weijizhi", "危急值");
        TABLE_BIZ_NAME_HINT.put("weishengwujianyanbaogao", "微生物检验报告");
        TABLE_BIZ_NAME_HINT.put("weizhiwenshu", "未知文书");
        TABLE_BIZ_NAME_HINT.put("xuekujilu", "血库记录");
        TABLE_BIZ_NAME_HINT.put("yangbenku", "样本库");
        TABLE_BIZ_NAME_HINT.put("yangbenkufu", "样本库附");
        TABLE_BIZ_NAME_HINT.put("yaofangfayao", "药房发药");
        TABLE_BIZ_NAME_HINT.put("yinanbinglitaolunjilu", "疑难病例讨论记录");
        TABLE_BIZ_NAME_HINT.put("yizhu", "医嘱");
        TABLE_BIZ_NAME_HINT.put("youchuangzhenliaocaozuojilu", "有创诊疗操作记录");
        TABLE_BIZ_NAME_HINT.put("yujiaojin", "预交金");
        TABLE_BIZ_NAME_HINT.put("zaicishoushubaogaobiao", "再次手术报告表");
        TABLE_BIZ_NAME_HINT.put("zairuyuan", "再入院");
        TABLE_BIZ_NAME_HINT.put("zaiyuanhuanzheliebiao", "在院患者列表");
        TABLE_BIZ_NAME_HINT.put("zhiliaojilu", "治疗记录");
        TABLE_BIZ_NAME_HINT.put("zhongyidianxingbingli", "中医典型病历");
        TABLE_BIZ_NAME_HINT.put("zhuankejilu", "专科记录");
        TABLE_BIZ_NAME_HINT.put("zhuankexinxi", "专科信息");
        TABLE_BIZ_NAME_HINT.put("zhuyuanfeiyong", "住院费用");
        TABLE_BIZ_NAME_HINT.put("zhuyuanfeiyongmingxi", "住院费用明细");
        TABLE_BIZ_NAME_HINT.put("zhuyuanhulijilu", "住院护理记录");
        TABLE_BIZ_NAME_HINT.put("zhuyuanjiegouhuashuju", "住院结构化数据");
        TABLE_BIZ_NAME_HINT.put("zhuyuanjiesuan", "住院结算");
        TABLE_BIZ_NAME_HINT.put("zhuyuanjifei", "住院计费");
        TABLE_BIZ_NAME_HINT.put("zhuyuanshangbaoka", "住院上报卡");
        TABLE_BIZ_NAME_HINT.put("zhuyuanweizhiwenshu", "住院未知文书");
        TABLE_BIZ_NAME_HINT.put("zhuyuanzhenduanzhengmingshu", "住院诊断证明书");
    }

    public SemanticCatalogService(SchemaExplorer schemaExplorer,
                                  BusinessSemanticRegistry businessRegistry) {
        this.schemaExplorer = schemaExplorer;
        this.businessRegistry = businessRegistry;

        // 定位 YAML 源文件路径
        String srcPath = findYamlSourcePath();
        this.yamlSourcePath = srcPath;
        log.info("SemanticCatalogService: yamlSourcePath={}", srcPath);
    }

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * 获取全部表的 SemanticCatalogItem（自动推断 + YAML 状态）
     */
    public List<CatalogItem> getAllTables() {
        List<TableMetadata> allMeta = schemaExplorer.getAllMetadata();
        Set<String> yamlTables = new HashSet<>(businessRegistry.getTableNames());

        List<CatalogItem> items = new ArrayList<>();
        for (TableMetadata meta : allMeta) {
            CatalogItem item = toCatalogItem(meta, yamlTables);
            items.add(item);
        }
        return items;
    }

    /**
     * 获取未在 YAML 中注册的表。
     */
    public List<CatalogItem> getUnconfirmedTables() {
        List<CatalogItem> all = getAllTables();
        List<CatalogItem> unconfirmed = new ArrayList<>();
        for (CatalogItem item : all) {
            if (!item.isInYaml()) {
                unconfirmed.add(item);
            }
        }
        return unconfirmed;
    }

    /**
     * 确认/更新一张表的语义配置，写入 YAML。
     */
    public ConfirmResult confirmTable(String tableName, String businessName,
                                      List<String> aliases, List<String> categories,
                                      List<String> dateFields) {
        // 1. 验证表名存在
        TableMetadata meta = schemaExplorer.getByTableName(tableName);
        if (meta == null) {
            return new ConfirmResult(false, "表 '" + tableName + "' 不存在于 Schema 中");
        }

        // 2. 填充默认值（用户没提供时用自动推断的）
        if (businessName == null || businessName.isEmpty()) {
            businessName = generateBusinessName(tableName, meta);
        }
        if (aliases == null || aliases.isEmpty()) {
            aliases = generateAliases(tableName, businessName, meta);
        }
        if (categories == null || categories.isEmpty()) {
            String suggested = meta.getSuggestedCategory();
            String cn = CATEGORY_CN.getOrDefault(suggested, "未知");
            categories = List.of(cn);
        }
        if (dateFields == null || dateFields.isEmpty()) {
            dateFields = meta.getDateFields();
            if (dateFields == null) dateFields = Collections.emptyList();
        }

        // 3. 追加到 YAML
        try {
            appendToYaml(tableName, businessName, aliases, categories, dateFields);
        } catch (Exception e) {
            log.error("Failed to write YAML for table '{}'", tableName, e);
            return new ConfirmResult(false, "写入 YAML 失败: " + e.getMessage());
        }

        // 4. 通知 BusinessSemanticRegistry 重新加载（可选）
        log.info("SemanticCatalog: confirmed table '{}' as '{}' (categories={})",
                tableName, businessName, categories);
        return new ConfirmResult(true, "表 '" + tableName + "' 已确认并写入 YAML");
    }

    /**
     * 批量确认多张表。
     */
    public List<ConfirmResult> batchConfirm(List<ConfirmRequest> requests) {
        List<ConfirmResult> results = new ArrayList<>();
        for (ConfirmRequest req : requests) {
            results.add(confirmTable(
                    req.tableName, req.businessName,
                    req.aliases, req.categories, req.dateFields));
        }
        return results;
    }

    /**
     * 获取统计摘要。
     */
    public Map<String, Object> getStats() {
        List<CatalogItem> all = getAllTables();
        int total = all.size();
        int inYaml = 0;
        int highConf = 0;
        int mediumConf = 0;
        int lowConf = 0;
        Map<String, Integer> categoryCount = new LinkedHashMap<>();

        for (CatalogItem item : all) {
            if (item.isInYaml()) inYaml++;
            String conf = item.getConfidence();
            if ("HIGH".equals(conf)) highConf++;
            else if ("MEDIUM".equals(conf)) mediumConf++;
            else lowConf++;

            String cat = item.getSuggestedCategory();
            cat = cat != null ? cat : "unknown";
            categoryCount.merge(cat, 1, Integer::sum);
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTables", total);
        stats.put("inYaml", inYaml);
        stats.put("unconfirmed", total - inYaml);
        stats.put("highConfidence", highConf);
        stats.put("mediumConfidence", mediumConf);
        stats.put("lowConfidence", lowConf);
        stats.put("categories", categoryCount);
        return stats;
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    private CatalogItem toCatalogItem(TableMetadata meta, Set<String> yamlTables) {
        CatalogItem item = new CatalogItem();
        item.setTableName(meta.getTableName());
        item.setType(meta.getType());
        item.setFieldCount(meta.getFieldCount());
        item.setDateFields(meta.getDateFields());
        item.setBusinessIndicatorFields(meta.getBusinessIndicatorFields());
        item.setSuggestedCategory(meta.getSuggestedCategory());
        item.setSuggestedCategoryCn(CATEGORY_CN.getOrDefault(meta.getSuggestedCategory(), "未分类"));
        item.setConfidence(meta.getSuggestionConfidence());
        item.setReason(meta.getSuggestionReason());
        item.setInYaml(yamlTables.contains(meta.getTableName()));
        item.setSuggestedBusinessName(generateBusinessName(meta.getTableName(), meta));
        return item;
    }

    /**
     * 根据表名和自动推断的分类生成建议的业务中文名。
     */
    private String generateBusinessName(String tableName, TableMetadata meta) {
        // 先查 hint 表
        String hint = TABLE_BIZ_NAME_HINT.get(tableName);
        if (hint != null) return hint;

        // 根据分类生成
        String cat = meta.getSuggestedCategory();
        if ("diagnosis".equals(cat)) return tableName.contains("menzhen") ? "门诊诊断" : "诊断";
        if ("order".equals(cat)) return tableName.contains("menzhen") ? "门诊医嘱" : "医嘱";
        if ("lab".equals(cat)) return "检验报告";
        if ("exam".equals(cat)) return "检查报告";
        if ("surgery".equals(cat)) return "手术记录";
        if ("fee".equals(cat)) return "费用";
        if ("nursing".equals(cat)) return "护理记录";
        if ("blood".equals(cat)) return "输血记录";

        // 兜底：拼音分段尝试
        return tableName;
    }

    /**
     * 生成建议的别名列表。
     */
    private List<String> generateAliases(String tableName, String businessName, TableMetadata meta) {
        List<String> aliases = new ArrayList<>();
        if (businessName != null && !businessName.isEmpty() && !businessName.equals(tableName)) {
            aliases.add(businessName);
        }
        // 从分类补充别名
        String cat = meta.getSuggestedCategory();
        String cn = CATEGORY_CN.get(cat);
        if (cn != null && !cn.equals(businessName)) {
            aliases.add(cn);
        }
        return aliases;
    }

    /**
     * 追加新表配置到 table-semantic.yaml。
     */
    private void appendToYaml(String tableName, String businessName,
                              List<String> aliases, List<String> categories,
                              List<String> dateFields) throws Exception {
        File yamlFile = new File(yamlSourcePath);
        if (!yamlFile.exists()) {
            throw new IllegalStateException("YAML file not found: " + yamlSourcePath);
        }

        // 读取现有内容
        String existingContent;
        try (InputStream is = new FileInputStream(yamlFile)) {
            existingContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Append new entry BEFORE the footer comment
        String footer = "# 找不到的表";
        String appendEntry = "\n  " + tableName + ":\n"
                + "    businessName: " + businessName + "\n"
                + "    aliases: [" + String.join(", ", aliases) + "]\n"
                + "    categories: [" + String.join(", ", categories) + "]\n";
        if (dateFields != null && !dateFields.isEmpty()) {
            appendEntry += "    dateFields: [" + String.join(", ", dateFields) + "]\n";
        }

        if (existingContent.contains(footer)) {
            existingContent = existingContent.replace(footer, appendEntry + "\n" + footer);
        } else {
            existingContent = existingContent.trim() + "\n" + appendEntry;
        }

        // 写回
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(yamlFile), StandardCharsets.UTF_8)) {
            writer.write(existingContent);
            writer.flush();
        }

        log.info("YAML updated: added table '{}' as '{}'", tableName, businessName);
    }

    /**
     * 定位 YAML 源文件路径（优先 src/main/resources，其次 classpath）。
     */
    private String findYamlSourcePath() {
        // 开发环境：src/main/resources/table-semantic.yaml
        String devPath = "src/main/resources/table-semantic.yaml";
        File devFile = new File(devPath);
        if (devFile.exists()) {
            return devFile.getAbsolutePath();
        }

        // 生产环境：尝试 classpath 定位
        // 直接使用当前工作目录
        String cwd = System.getProperty("user.dir");
        devFile = new File(cwd, devPath);
        if (devFile.exists()) {
            return devFile.getAbsolutePath();
        }

        // 最后尝试：target/classes/
        File targetFile = new File("target/classes/table-semantic.yaml");
        if (targetFile.exists()) {
            return targetFile.getAbsolutePath();
        }

        // 兜底
        return devPath;
    }

    // ========================================================================
    // POJO
    // ========================================================================

    /**
     * 目录条目 — 给前端展示用。
     */
    public static class CatalogItem {
        private String tableName;
        private String type;
        private int fieldCount;
        private List<String> dateFields;
        private List<String> businessIndicatorFields;
        private String suggestedCategory;
        private String suggestedCategoryCn;
        private String confidence;
        private String reason;
        private boolean inYaml;
        private String suggestedBusinessName;

        public String getTableName() { return tableName; }
        public void setTableName(String v) { this.tableName = v; }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public int getFieldCount() { return fieldCount; }
        public void setFieldCount(int v) { this.fieldCount = v; }
        public List<String> getDateFields() { return dateFields; }
        public void setDateFields(List<String> v) { this.dateFields = v; }
        public List<String> getBusinessIndicatorFields() { return businessIndicatorFields; }
        public void setBusinessIndicatorFields(List<String> v) { this.businessIndicatorFields = v; }
        public String getSuggestedCategory() { return suggestedCategory; }
        public void setSuggestedCategory(String v) { this.suggestedCategory = v; }
        public String getSuggestedCategoryCn() { return suggestedCategoryCn; }
        public void setSuggestedCategoryCn(String v) { this.suggestedCategoryCn = v; }
        public String getConfidence() { return confidence; }
        public void setConfidence(String v) { this.confidence = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { this.reason = v; }
        public boolean isInYaml() { return inYaml; }
        public void setInYaml(boolean v) { this.inYaml = v; }
        public String getSuggestedBusinessName() { return suggestedBusinessName; }
        public void setSuggestedBusinessName(String v) { this.suggestedBusinessName = v; }
    }

    /**
     * 确认请求（从 API 接收）。
     */
    public static class ConfirmRequest {
        public String tableName;
        public String businessName;
        public List<String> aliases;
        public List<String> categories;
        public List<String> dateFields;
    }

    /**
     * 确认结果。
     */
    public static class ConfirmResult {
        public final boolean success;
        public final String message;

        public ConfirmResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
