#!/usr/bin/env python3
"""
ESMind 患者完整数据提取
患者: 002680203800 (白浩)
提取: 首页(13变量) + 住院病历 + 血常规24项
"""
import json, urllib.request

ES_URL = "http://192.168.8.156:9230"
INDEX = "history2026_clinical_inhistory_0429122358"

def es_search(body):
    url = f"{ES_URL}/{INDEX}/_search"
    req = urllib.request.Request(url, data=json.dumps(body).encode(), 
                                  headers={"Content-Type": "application/json"},
                                  method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())

print("=" * 72)
print("  ESMind 患者数据提取报告")
print("  患者: 白浩 | ID: 002680203800 | 日期: 2026-06-02")
print("=" * 72)

source = es_search({
    "size": 1,
    "query": {"match_phrase": {"patient.patient_id": "002680203800"}}
})["hits"]["hits"][0]["_source"]

# ====================================================================
# 1. 首页 (13 变量)
# ====================================================================
bs = source.get("binganshouye", {})
print("\n【1】首页信息")
print("-" * 50)
fields_1 = {
    "性别": bs.get("sex", "N/A"),
    "年龄": f'{bs.get("age_value", "N/A")}岁',
    "ID号": bs.get("patient_id", "N/A"),
    "出生日期": bs.get("date_of_birth", "N/A"),
    "入院日期": bs.get("admission_time", "N/A"),
    "职业": bs.get("occupation_name", "N/A"),
    "入院后确诊日期": bs.get("admission_time", "N/A"),
    "出院科室": bs.get("dept_discharge_from_name", "N/A"),
}
for k, v in fields_1.items():
    print(f"  {k:12s}: {v}")

# 出院诊断 (shouyezhenduan)
szd = source.get("shouyezhenduan", [])
print(f"\n  出院诊断 (共{len(szd)}条, 限20条):")
for i, dx in enumerate(szd[:20]):
    # Get diagnosis fields via inner_hits
    pass
# Use inner_hits to get full diagnosis data
res = es_search({
    "_source": False,
    "size": 1,
    "query": {
        "bool": {
            "must": [
                {"match_phrase": {"patient.patient_id": "002680203800"}},
                {"nested": {
                    "path": "shouyezhenduan",
                    "inner_hits": {"size": 20, "_source": True},
                    "query": {"exists": {"field": "shouyezhenduan"}}
                }}
            ]
        }
    }
})
hits = res.get("hits", {}).get("hits", [])
if hits:
    ih = hits[0].get("inner_hits", {})
    dx_list = ih.get("shouyezhenduan", {}).get("hits", {}).get("hits", [])
    print(f"  (通过nested查询获取详细诊断)")
    for i, dx_hit in enumerate(dx_list[:20]):
        dx = dx_hit.get("_source", {})
        name = dx.get("diagnosis_name", dx.get("norm_diagnosis_name", dx.get("diagnosis_type_name", "N/A")))
        dtype = dx.get("diagnosis_type_name", "")
        dtime = dx.get("diagnosis_time", "")
        main = " (主诊断)" if dx.get("main_diagnosis") == "1" else ""
        print(f"    {i+1}. [{dtype}] {name}{main} [{dtime}]")
    # Check for diagnosis_name field existence
    if dx_list:
        print(f"    诊断字段: {list(dx_list[0].get('_source', {}).keys())}")

# ADL score
adl_found = any("adl" in k.lower() or "生活" in k or "自理" in k for k in bs)
if adl_found:
    for k in bs:
        if any(w in k.lower() for w in ['adl', 'barthel', 'daily', '生活', '自理', '评分']):
            print(f"\n  日常生活能力评定量表: {k}={bs[k]}")
else:
    print(f"\n  ⚠ 日常生活能力评定量表: 病案首页无此项记录")

# Ventilator
vent_found = any("ventilat" in k.lower() or "呼吸" in k for k in bs)
if vent_found:
    for k in bs:
        if "ventilat" in k.lower() or "呼吸" in k:
            print(f"  呼吸机使用时间: {k}={bs[k]}")
else:
    print(f"  ⚠ 呼吸机使用时间: 病案首页无此项记录")

# ====================================================================
# 2. 住院及门诊病历
# ====================================================================
print("\n【2】住院及门诊病历")
print("-" * 50)

# Try menzhenshuju
res = es_search({
    "_source": False,
    "size": 1,
    "query": {
        "bool": {
            "must": [
                {"match_phrase": {"patient.patient_id": "002680203800"}},
                {"nested": {
                    "path": "menzhenshuju",
                    "inner_hits": {"size": 1, "_source": True},
                    "query": {"exists": {"field": "menzhenshuju"}}
                }}
            ]
        }
    }
})
hits = res.get("hits", {}).get("hits", [])
if hits:
    ih = hits[0].get("inner_hits", {})
    mz_list = ih.get("menzhenshuju", {}).get("hits", {}).get("hits", [])
    if mz_list:
        mz = mz_list[0].get("_source", {})
        for k in ["history_of_present_illness", "past_history", "personal_history",
                   "family_history", "auxiliary_exam", "auxiliary_exam_result",
                   "现病史", "既往史", "个人史", "家族史", "辅助检查"]:
            for f in mz:
                if k in f.lower():
                    print(f"  ✅ {f}: {str(mz[f])[:300]}")
                    break
else:
    print("  ❌ 患者无门诊病历 (menzhenshuju) 数据")

# Check discharge summary (chuyuanjilu)
cyj = source.get("chuyuanjilu", {})
if isinstance(cyj, dict) and cyj.get("mr_content_html"):
    from html.parser import HTMLParser
    class EH(HTMLParser):
        def __init__(self):
            super().__init__()
            self.text = []
        def handle_data(self, data):
            t = data.strip()
            if t: self.text.append(t)
    p = EH()
    p.feed(cyj["mr_content_html"])
    content = "\n".join(p.text)
    print(f"\n  出院记录 (chuyuanjilu) 文本:")
    print(f"  {content[:500]}")

# ====================================================================
# 3. 血常规 (24 项)
# ====================================================================
print("\n【3】检验数据 - 血常规 (24项)")
print("-" * 50)

LAB_SUB_NAMES = {
    "白细胞计数": ["★白细胞", "白细胞计数", "白细胞数"],
    "嗜中性粒细胞绝对值": ["中性粒细胞绝对值", "嗜中性粒细胞绝对值", "NEUT#"],
    "嗜中性粒细胞百分比": ["中性粒细胞百分数", "嗜中性粒细胞百分数", "中性粒细胞百分比", "NEUT%"],
    "淋巴细胞绝对值": ["淋巴细胞绝对值", "LYMPH#"],
    "淋巴细胞百分比": ["淋巴细胞百分数", "淋巴细胞百分比", "LYMPH%"],
    "单核细胞绝对值": ["单核细胞绝对值", "MONO#"],
    "单核细胞百分比": ["单核细胞百分数", "单核细胞百分比", "MONO%"],
    "嗜酸性粒细胞绝对值": ["嗜酸性粒细胞绝对值", "EO#"],
    "嗜酸性粒细胞百分比": ["嗜酸性粒细胞百分数", "嗜酸性粒细胞百分比", "EO%"],
    "嗜碱性粒细胞绝对值": ["嗜碱性粒细胞绝对值", "BASO#"],
    "嗜碱性粒细胞百分比": ["嗜碱性粒细胞百分数", "嗜碱性粒细胞百分比", "BASO%"],
    "红细胞": ["★红细胞", "红细胞计数", "红细胞数"],
    "血红蛋白": ["★血红蛋白"],
    "红细胞压积": ["★红细胞压积"],
    "平均红细胞体积": ["*平均红细胞体积", "平均红细胞容积", "MCV"],
    "平均红细胞血红蛋白": ["*平均血红蛋白含量", "平均红细胞血红蛋白含量", "MCH"],
    "平均红细胞血红蛋白浓度": ["*平均血红蛋白浓度", "平均红细胞血红蛋白浓度", "MCHC"],
    "红细胞分布宽度变异系数": ["红细胞分布宽度CV", "红细胞分布宽度变异系数", "RDW-CV"],
    "红细胞分布宽度-SD": ["红细胞分布宽度SD", "红细胞分布宽度标准差", "RDW-SD"],
    "血小板计数": ["★血小板", "血小板计数", "血小板数"],
    "血小板分布宽度": ["血小板分布宽度", "PDW"],
    "平均血小板体积": ["平均血小板体积", "MPV"],
    "大型血小板比率": ["大血小板比率", "大型血小板比率", "P-LCR"],
    "血小板压积": ["血小板压积", "PCT"],
}

reports = source.get("jianyanbaogaofu", [])
matched = {}
for r in reports:
    name = r.get("lab_sub_item_name", "") or r.get("norm_lab_sub_item_name", "") or ""
    for item, keywords in LAB_SUB_NAMES.items():
        if item in matched:
            continue
        for kw in keywords:
            if kw in name or kw in name:
                matched[item] = {
                    "value": r.get("lab_result_value", ""),
                    "unit": r.get("lab_result_value_unit", ""),
                    "ref": r.get("ranges", ""),
                    "time": r.get("report_time", "")
                }
                break

print(f"  匹配: {len(matched)}/24 项\n")
for item in LAB_SUB_NAMES:
    if item in matched:
        v = matched[item]
        print(f"  ✅ {item:16s}: {v['value']:>10s} {v['unit']:12s} (参考: {v['ref']:15s}) [{v['time']}]")
    else:
        print(f"  ❌ {item:16s}: 未查到")

print("\n" + "=" * 72)
print("  报告结束")
print("=" * 72)
