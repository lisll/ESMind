#!/usr/bin/env python3
"""
ESMind — 处理从真实ES导出的原始JSON，导入到测试ES

用法：
  # 1. 解压从 4.146 传回的包
  tar xzf esmind-export-20260516.tar.gz

  # 2. 导入
  python3 scripts/import-raw.py \
    --input /tmp/esmind-export-20260516 \
    --index xnk20231220_clinical_inhistory_0122113057

输出：将解析后的完整文档直接 bulk 导入测试 ES
"""

import json
import os
import sys
import glob
import urllib.request
import urllib.error

ES = "http://192.168.8.59:9230"
TYPE = "typemr"


def main():
    if len(sys.argv) < 3 or "--input" not in sys.argv or "--index" not in sys.argv:
        print("用法: python3 import-raw.py --input /tmp/esmind-export-xxx --index 索引名")
        sys.exit(1)

    idx = sys.argv.index("--input")
    input_dir = sys.argv[idx + 1]
    idx = sys.argv.index("--index")
    index = sys.argv[idx + 1]

    # ---------- 1. 读取所有原始 JSON ----------
    print(f"[1/4] 读取: {input_dir}/docs/*.json")
    json_files = sorted(glob.glob(os.path.join(input_dir, "docs", "*.json")))
    print(f"      找到 {len(json_files)} 个文件")

    docs = []
    for fpath in json_files:
        with open(fpath, "r", encoding="utf-8") as f:
            raw = json.load(f)

        hits = raw.get("hits", {}).get("hits", [])
        if not hits:
            continue

        hit = hits[0]
        doc_id = hit["_id"]
        source = hit.get("_source", {})
        inner_hits = hit.get("inner_hits", {})

        doc = {"_id": doc_id}

        # 从 _source 获取 patient
        if source and isinstance(source, dict):
            if "patient" in source:
                doc["patient"] = source["patient"]
            # object 表（binganshouye, ruyuanjilu）
            for obj_field in ("binganshouye", "ruyuanjilu"):
                if obj_field in source:
                    doc[obj_field] = source[obj_field]

        # 从 inner_hits 获取 nested 表数据
        for path in inner_hits:
            ih_hits = inner_hits[path].get("hits", {}).get("hits", [])
            if not ih_hits:
                continue

            items = []
            for ih_hit in ih_hits:
                ih_src = ih_hit.get("_source", {})
                if ih_src:
                    # 清洗字段名前缀
                    cleaned = {}
                    for k, v in ih_src.items():
                        if k.startswith(f"{path}."):
                            cleaned[k[len(path) + 1:]] = v
                        else:
                            cleaned[k] = v
                    items.append(cleaned)

            if items:
                doc[path] = items

        # 只保留有数据的文档
        if len(doc) > 1:  # 至少要有 _id 之外的字段
            docs.append(doc)

    print(f"      解析出 {len(docs)} 条文档")
    if not docs:
        print("❌ 无解析结果")
        sys.exit(1)

    # 统计业务表覆盖
    table_stats = {}
    for doc in docs:
        for k in doc:
            if k == "_id":
                continue
            if k not in table_stats:
                table_stats[k] = {"docs": 0, "total": 0}
            table_stats[k]["docs"] += 1
            if isinstance(doc[k], list):
                table_stats[k]["total"] += len(doc[k])
            else:
                table_stats[k]["total"] += 1

    print(f"  业务表覆盖:")
    for t, s in sorted(table_stats.items()):
        print(f"    {t}: {s['docs']}/{len(docs)} 文档, {s['total']} 条")

    # ---------- 2. 连接测试 ES ----------
    print(f"\n[2/4] 连接测试 ES: {ES}")
    try:
        req = urllib.request.Request(f"{ES}/")
        resp = urllib.request.urlopen(req, timeout=15)
        info = json.loads(resp.read())
        print(f"     ES {info['version']['number']} ✅")
    except Exception as e:
        print(f"❌ 无法连接测试 ES: {e}")
        sys.exit(1)

    # ---------- 3. 检查/创建索引 ----------
    print(f"\n[3/4] 检查索引: {index}")

    # 检查索引是否存在
    req = urllib.request.Request(f"{ES}/{index}/_count")
    try:
        resp = urllib.request.urlopen(req, timeout=15)
        count = json.loads(resp.read()).get("count", 0)
        print(f"      索引已存在，当前 {count} 条文档")
        print(f"      导入后将新增 {len(docs)} 条")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"      索引不存在，需要先创建索引")
            mapping_path = os.path.join(input_dir, "..", "mapping.json")
            if os.path.exists(mapping_path):
                print(f"      从 {mapping_path} 创建索引...")
                with open(mapping_path) as f:
                    mapping = json.load(f)
                req = urllib.request.Request(
                    f"{ES}/{index}",
                    data=json.dumps(mapping).encode("utf-8"),
                    headers={"Content-Type": "application/json"},
                    method="PUT",
                )
                urllib.request.urlopen(req, timeout=60)
                print(f"      索引创建成功")
            else:
                print(f"⚠️  需要先建索引，映射文件不存在：{mapping_path}")
                want_continue = input("      继续导入（索引不存在，bulk 会自动建索引，但 mapping 可能不对）? [y/N]: ")
                if want_continue.lower() != "y":
                    sys.exit(1)
        else:
            print(f"❌ 错误: {e}")
            sys.exit(1)

    # ---------- 4. Bulk 导入 ----------
    print(f"\n[4/4] 导入 {len(docs)} 条到 {index} ...")

    lines = []
    for doc in docs:
        doc_id = doc.pop("_id")
        action = {"index": {"_index": index, "_type": TYPE, "_id": doc_id}}
        lines.append(json.dumps(action, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))

    body = "\n".join(lines) + "\n"
    req = urllib.request.Request(
        f"{ES}/_bulk",
        data=body.encode("utf-8"),
        headers={"Content-Type": "application/x-ndjson"},
    )
    resp = urllib.request.urlopen(req, timeout=180)
    result = json.loads(resp.read())

    success = sum(1 for item in result.get("items", []) if "error" not in item.get("index", {}))
    failed = len(result.get("items", [])) - success

    if failed > 0:
        for item in result["items"]:
            if "error" in item.get("index", {}):
                err = item["index"]["error"]
                print(f"  ❌ {item['index']['_id'][:40]}: {err['reason'][:150]}")

    # 刷新
    try:
        req = urllib.request.Request(f"{ES}/{index}/_refresh", method="POST")
        urllib.request.urlopen(req, timeout=30)
    except Exception:
        pass

    # 验证
    req = urllib.request.Request(f"{ES}/{index}/_count")
    resp = urllib.request.urlopen(req, timeout=15)
    total = json.loads(resp.read()).get("count", 0)

    print(f"\n=== 结果 ===")
    print(f"  成功: {success}")
    print(f"  失败: {failed}")
    print(f"  索引总文档数: {total}")
    print(f"  ✅ ESMind 测试数据就绪！")


if __name__ == "__main__":
    main()
