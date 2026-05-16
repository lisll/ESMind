#!/usr/bin/env python3
"""
ESMind — 从真实ES导出的search response JSON文件导入数据到测试ES集群
兼容两种输入格式：
  1. ES search response: { hits: { hits: [{ _id, _source }] } }
  2. ndjson: 每行一条 source 文档（含 _id 字段）

用法:
  python3 scripts/import-data.py --index 索引名 --input esmind-export.json
"""

import json
import sys
import urllib.request
import urllib.error


def main():
    if len(sys.argv) < 5 or "--index" not in sys.argv or "--input" not in sys.argv:
        print("用法: python3 import-data.py --index 索引名 --input 文件路径")
        sys.exit(1)

    idx = sys.argv.index("--index")
    index = sys.argv[idx + 1]

    inp_idx = sys.argv.index("--input")
    input_path = sys.argv[inp_idx + 1]

    es = "http://192.168.8.59:9230"

    # ---------- 1. 读取文件 ----------
    print(f"[1/4] 读取: {input_path}")
    with open(input_path, "r", encoding="utf-8") as f:
        raw = f.read().strip()

    docs = []

    # 尝试1: ES search response 格式
    try:
        data = json.loads(raw)
        hits = data.get("hits", {}).get("hits", [])
        if hits:
            for h in hits:
                doc = h.get("_source", {})
                doc["_id"] = h["_id"]
                docs.append(doc)
            print(f"     识别为 search response 格式, {len(docs)} 条文档")
    except json.JSONDecodeError:
        pass

    # 尝试2: ndjson 格式 (每行一个JSON)
    if not docs:
        for line in raw.split("\n"):
            line = line.strip()
            if not line:
                continue
            try:
                doc = json.loads(line)
                if "_id" in doc:
                    docs.append(doc)
            except json.JSONDecodeError:
                continue
        if docs:
            print(f"     识别为 ndjson 格式, {len(docs)} 条文档")

    if not docs:
        print("❌ 无法解析文件内容（既不是 search response 也不是 ndjson）")
        sys.exit(1)

    # ---------- 2. 检查测试ES ----------
    print(f"[2/4] 连接测试ES: {es}")
    req = urllib.request.Request(f"{es}/")
    resp = urllib.request.urlopen(req, timeout=15)
    info = json.loads(resp.read())
    print(f"     ES {info['version']['number']} ✅")

    # ---------- 3. Bulk 导入 ----------
    print(f"[3/4] 导入 {len(docs)} 条到 {index} ...")
    # Detect mapping type from the first document (ES 6.x requires _type)
    mapping_type = "typemr"  # default
    for doc in docs:
        src_type = doc.pop("_type", None)
        if src_type:
            mapping_type = src_type
            break

    lines = []
    for doc in docs:
        doc_id = doc.pop("_id", None)
        action = {"index": {"_index": index, "_type": mapping_type}}
        if doc_id:
            action["index"]["_id"] = doc_id
        lines.append(json.dumps(action, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))

    req = urllib.request.Request(
        f"{es}/_bulk",
        data=("\n".join(lines) + "\n").encode("utf-8"),
        headers={"Content-Type": "application/x-ndjson"},
    )
    resp = urllib.request.urlopen(req, timeout=120)
    result = json.loads(resp.read())

    success = sum(1 for item in result.get("items", []) if "error" not in item.get("index", {}))
    fail = len(result.get("items", [])) - success

    # ---------- 4. 验证 ----------
    print(f"[4/4] 验证...")
    try:
        req = urllib.request.Request(f"{es}/{index}/_refresh", method="POST")
        urllib.request.urlopen(req, timeout=30)
    except Exception:
        pass

    req = urllib.request.Request(f"{es}/{index}/_count")
    resp = urllib.request.urlopen(req, timeout=15)
    count = json.loads(resp.read()).get("count", 0)

    print(f"\n=== 结果 ===")
    print(f"  索引: {index}")
    print(f"  成功: {success}")
    print(f"  失败: {fail}")
    print(f"  最终文档数: {count}")

    # 预览
    if count > 0:
        req = urllib.request.Request(f"{es}/{index}/_search?size=1&_source=patient.doc_list,patient.patient_id")
        resp = urllib.request.urlopen(req, timeout=15)
        sample = json.loads(resp.read())["hits"]["hits"][0]
        src = sample["_source"]
        print(f"\n  样本文档 doc_list: {json.dumps(src.get('patient',{}).get('doc_list',[]), ensure_ascii=False)}")
        print(f"  ✅ ESMind 可以开始测试了!")


if __name__ == "__main__":
    main()
