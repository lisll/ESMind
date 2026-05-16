#!/usr/bin/env python3
"""
ESMind — 从真实 ES 集群导出一批数据到文件
在能访问真实 ES 的机器上运行

用法:
  python3 export-data.py \
    --es http://真实IP:9200 \
    --index xnk20231220_clinical_inhistory_0122113057 \
    --max-docs 10 \
    --output ./esmind-sample.ndjson

输出: ndjson格式（每行一条完整的source文档），适合 bulk API 导入
"""

import argparse
import json
import sys
import time
import urllib.request
import urllib.error
import gzip


def main():
    parser = argparse.ArgumentParser(description="从真实ES导出测试数据")
    parser.add_argument("--es", required=True, help="真实ES地址，如 http://10.0.0.1:9200")
    parser.add_argument("--index", required=True, help="索引名")
    parser.add_argument("--max-docs", type=int, default=10, help="导出条数 (默认10)")
    parser.add_argument("--output", default="esmind-sample.ndjson", help="输出文件")
    parser.add_argument("--compress", action="store_true", help="压缩输出 (.ndjson.gz)")
    args = parser.parse_args()

    base_url = args.es.rstrip("/")
    index = args.index
    max_docs = args.max_docs
    output_path = args.output

    # ---------- 1. 检查索引 ----------
    print(f"[1/4] 检查索引: {base_url}/{index}")
    try:
        req = urllib.request.Request(f"{base_url}/{index}/_count")
        resp = urllib.request.urlopen(req, timeout=30)
        count_data = json.loads(resp.read())
        total = count_data.get("count", 0)
        print(f"     索引总文档数: {total}")
    except Exception as e:
        print(f"❌ 无法访问索引: {e}")
        sys.exit(1)

    # ---------- 2. 检查版本 ----------
    print(f"[2/4] 检查ES版本")
    try:
        req = urllib.request.Request(f"{base_url}/")
        resp = urllib.request.urlopen(req, timeout=30)
        info = json.loads(resp.read())
        version = info["version"]["number"]
        print(f"     ES版本: {version}")
    except Exception as e:
        print(f"⚠️  无法检测版本: {e}")
        version = "unknown"

    # ---------- 3. Scroll 导出 ----------
    print(f"[3/4] 开始scroll导出，最多 {max_docs} 条...")

    # 初始化 scroll
    scroll_body = {
        "size": min(max_docs, 100),
        "_source": ["*"],  # 强制导出所有字段
        "query": {"match_all": {}},
        "sort": ["_doc"],
    }
    scroll_timeout = "2m"

    req = urllib.request.Request(
        f"{base_url}/{index}/_search?scroll={scroll_timeout}",
        data=json.dumps(scroll_body).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    resp = urllib.request.urlopen(req, timeout=60)
    data = json.loads(resp.read())

    scroll_id = data.get("_scroll_id")
    hits = data.get("hits", {}).get("hits", [])
    docs_collected = len(hits)

    # 收集文档
    all_sources = []
    for hit in hits:
        source = hit.get("_source", {})
        source["_id"] = hit["_id"]  # 保留 _id 以便导入时还原
        all_sources.append(source)

    # 继续 scroll
    while docs_collected < max_docs and scroll_id:
        req = urllib.request.Request(
            f"{base_url}/_search/scroll",
            data=json.dumps({"scroll": scroll_timeout, "scroll_id": scroll_id}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        resp = urllib.request.urlopen(req, timeout=60)
        data = json.loads(resp.read())
        scroll_id = data.get("_scroll_id")
        hits = data.get("hits", {}).get("hits", [])

        if not hits:
            break

        for hit in hits:
            source = hit.get("_source", {})
            source["_id"] = hit["_id"]
            all_sources.append(source)

        docs_collected += len(hits)
        print(f"     已收集 {len(all_sources)} 条...")

    # 清除 scroll
    if scroll_id:
        try:
            req = urllib.request.Request(
                f"{base_url}/_search/scroll",
                data=json.dumps({"scroll_id": scroll_id}).encode("utf-8"),
                headers={"Content-Type": "application/json"},
                method="DELETE",
            )
            urllib.request.urlopen(req, timeout=10)
        except Exception:
            pass

    print(f"     共收集 {len(all_sources)} 条文档")

    # ---------- 4. 写入文件 ----------
    print(f"[4/4] 写入文件: {output_path}")

    if args.compress or output_path.endswith(".gz"):
        output_path = output_path if output_path.endswith(".gz") else output_path + ".gz"
        f_out = gzip.open(output_path, "wt", encoding="utf-8")
    else:
        f_out = open(output_path, "w", encoding="utf-8")

    with f_out:
        for doc in all_sources:
            f_out.write(json.dumps(doc, ensure_ascii=False) + "\n")

    file_size = 0
    if args.compress or output_path.endswith(".gz"):
        import os
        file_size = os.path.getsize(output_path)
    else:
        file_size = len(open(output_path, "rb").read())

    print(f"✅ 导出完成!")
    print(f"   文件: {output_path}")
    print(f"   大小: {file_size/1024:.1f} KB")
    print(f"   文档: {len(all_sources)} 条")
    print(f"")
    print(f"下一步: 将此文件传送到测试服务器，然后运行 import-data.py")


if __name__ == "__main__":
    main()
