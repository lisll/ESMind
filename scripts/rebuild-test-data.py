#!/usr/bin/env python3
"""
ESMind — 在测试ES上重建2条完整住院文档 + 验证查询
1. 删除旧索引
2. 基于真实mapping建新索引（含嵌套表定义）
3. 构造完整文档（patient + nested数据）
4. bulk导入
5. 验证 nested + inner_hits 查询
"""

import json
import urllib.request

ES = "http://192.168.8.59:9230"
INDEX = "xnk20231220_clinical_inhistory_0122113057"
TYPE = "typemr"

# ========== 1. 删除旧索引 ==========
print("[1/5] 删除旧索引...")
try:
    req = urllib.request.Request(f"{ES}/{INDEX}", method="DELETE")
    urllib.request.urlopen(req, timeout=30)
    print("      已删除")
except:
    print("      索引不存在，跳过")

# ========== 2. 建索引（基于真实mapping简化版）==========
print("[2/5] 创建索引 + mapping...")

# 从真实 mapping.json 读取 nested 表字段定义
with open('/home/dev/.hermes/cache/documents/doc_8b1ec7342bfe_mapping.json') as f:
    real_mapping = json.load(f)

real_props = real_mapping[INDEX]['mappings']['typemr']['properties']

# 只保留我们需要的表
needed_tables = [
    'patient', 'binganshouye',
    'shouyezhenduan', 'yizhu', 'jianyanbaogaofu', 'jianchabaogaofu'
]

# 建简化 mapping（保留完整字段定义但只针对需要的表）
new_props = {}
for tbl in needed_tables:
    if tbl in real_props:
        new_props[tbl] = real_props[tbl]

# 建简化 mapping（去掉自定义 analyzer，用 standard 替代）
def strip_custom_analyzer(node):
    """递归去掉 mapping 中的自定义 analyzer"""
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k in ('analyzer', 'similarity', 'search_analyzer'):
                if isinstance(v, str) and v not in ('standard', 'simple', 'keyword', 'whitespace', 'pattern', 'stop', 'english'):
                    if k == 'analyzer':
                        node[k] = 'standard'
                    elif k == 'similarity':
                        node[k] = 'BM25'
                    else:
                        del node[k]
            elif k == 'fields':
                strip_custom_analyzer(v)
            elif isinstance(v, dict):
                strip_custom_analyzer(v)
            elif isinstance(v, list):
                for item in v:
                    strip_custom_analyzer(item)

strip_custom_analyzer(new_props)

new_mapping = {
    "settings": {
        "index": {
            "number_of_shards": 1,
            "number_of_replicas": 0,
            "mapping": {
                "total_fields": {"limit": 3000}
            }
        }
    },
    "mappings": {
        TYPE: {
            "dynamic": False,
            "_all": {"enabled": False},
            "_source": {"enabled": True},
            "properties": new_props
        }
    }
}

req = urllib.request.Request(
    f"{ES}/{INDEX}",
    data=json.dumps(new_mapping).encode('utf-8'),
    headers={"Content-Type": "application/json"},
    method="PUT"
)
try:
    resp = urllib.request.urlopen(req, timeout=60)
    print(f"      建索引成功: {json.loads(resp.read())}")
except Exception as e:
    print(f"      ❌ 建索引失败: {e}")
    # 试读错误详情
    if hasattr(e, 'read'):
        print(f"      {e.read().decode()[:300]}")
    exit(1)

# ========== 3. 构造完整文档 ==========
print("[3/5] 构造完整文档...")

# 3a. 从 inner-shouyezhenduan（最早有 _source 的版本）读取 patient + shouyezhenduan
patient_src_file = '/home/dev/.hermes/cache/documents/doc_7d312410bb35_inner-shouyezhenduan.json'
with open(patient_src_file) as f:
    raw = f.read()
# 去掉首行的 "cat xxx" shell 前缀
if raw.startswith('cat'):
    raw = raw[raw.index('\n')+1:]
syz_data = json.loads(raw)

docs = {}
for hit in syz_data['hits']['hits']:
    hid = hit['_id']
    src = hit.get('_source', {})
    if src and 'patient' in src:
        docs[hid] = {'patient': src['patient']}

print(f"      患者: {list(docs.keys())}")

# 3b. 从 docvalue_fields 版本提取诊断字段值
syz_dv_file = '/home/dev/.hermes/cache/documents/doc_acabb1f22e9c_inner-shouyezhenduan.json'
with open(syz_dv_file) as f:
    syz_dv = json.load(f)
for hit in syz_dv['hits']['hits']:
    hid = hit['_id']
    if hid in docs:
        ih = hit.get('inner_hits', {}).get('shouyezhenduan', {}).get('hits', {}).get('hits', [])
        syz_list = []
        for ih_hit in ih:
            f = ih_hit.get('fields', {})
            entry = {}
            for k, v in f.items():
                short_k = k.replace('shouyezhenduan.', '', 1)
                for suffix in ('.accurate', '.raw', '.keyword'):
                    if short_k.endswith(suffix):
                        short_k = short_k[:-len(suffix)]
                        break
                entry[short_k] = v[0] if isinstance(v, list) and v else ''
            syz_list.append(entry)
        docs[hid]['shouyezhenduan'] = syz_list
        print(f"      {hid[:40]}: shouyezhenduan {len(syz_list)} 条")

# 从 inner-yizhu 提取
with open('/home/dev/.hermes/cache/documents/doc_0dbe70692d9e_inner-yizhu.json') as f:
    yz_data = json.load(f)
for hit in yz_data['hits']['hits']:
    hid = hit['_id']
    if hid in docs:
        ih = hit.get('inner_hits', {}).get('yizhu', {}).get('hits', {}).get('hits', [])
        yz_list = []
        for ih_hit in ih:
            f = ih_hit.get('fields', {})
            entry = {}
            for k, v in f.items():
                short_k = k.replace('yizhu.', '', 1)
                for suffix in ('.accurate', '.raw', '.keyword'):
                    if short_k.endswith(suffix):
                        short_k = short_k[:-len(suffix)]
                        break
                entry[short_k] = v[0] if isinstance(v, list) and v else ''
            yz_list.append(entry)
        docs[hid]['yizhu'] = yz_list
        print(f"      {hid[:40]}: yizhu {len(yz_list)} 条")

# 从 inner-jianyan 提取
with open('/home/dev/.hermes/cache/documents/doc_ee9e997d99b1_inner-jianyan.json') as f:
    jy_data = json.load(f)
for hit in jy_data['hits']['hits']:
    hid = hit['_id']
    if hid in docs:
        ih = hit.get('inner_hits', {}).get('jianyanbaogaofu', {}).get('hits', {}).get('hits', [])
        jy_list = []
        for ih_hit in ih:
            f = ih_hit.get('fields', {})
            entry = {}
            for k, v in f.items():
                short_k = k.replace('jianyanbaogaofu.', '', 1)
                for suffix in ('.accurate', '.raw', '.keyword'):
                    if short_k.endswith(suffix):
                        short_k = short_k[:-len(suffix)]
                        break
                entry[short_k] = v[0] if isinstance(v, list) and v else ''
            jy_list.append(entry)
        docs[hid]['jianyanbaogaofu'] = jy_list
        print(f"      {hid[:40]}: jianyanbaogaofu {len(jy_list)} 条")

# 从 inner-jianchabao 提取
with open('/home/dev/.hermes/cache/documents/doc_f34229e341ed_inner-jianchabao.json') as f:
    jcb_data = json.load(f)
for hit in jcb_data['hits']['hits']:
    hid = hit['_id']
    if hid in docs:
        ih = hit.get('inner_hits', {}).get('jianchabaogaofu', {}).get('hits', {}).get('hits', [])
        jcb_list = []
        for ih_hit in ih:
            f = ih_hit.get('fields', {})
            entry = {}
            for k, v in f.items():
                short_k = k.replace('jianchabaogaofu.', '', 1)
                for suffix in ('.accurate', '.raw', '.keyword'):
                    if short_k.endswith(suffix):
                        short_k = short_k[:-len(suffix)]
                        break
                entry[short_k] = v[0] if isinstance(v, list) and v else ''
            jcb_list.append(entry)
        docs[hid]['jianchabaogaofu'] = jcb_list
        print(f"      {hid[:40]}: jianchabaogaofu {len(jcb_list)} 条")

# 添加 binganshouye
docs['fe37BJDXDSYY##2#000930502000#1']['binganshouye'] = {
    'admission_time': '2013-03-22 09:13:00',
    'discharge_time': '2013-03-29 14:28:00'
}
docs['2c64BJDXDSYY##2#000909403100#1']['binganshouye'] = {
    'admission_time': '2013-01-06 10:39:00',
    'discharge_time': '2013-01-11 14:03:00'
}

# ========== 4. Bulk 导入 ==========
print("[4/5] Bulk 导入...")
lines = []
for doc_id, doc in docs.items():
    action = {"index": {"_index": INDEX, "_type": TYPE, "_id": doc_id}}
    lines.append(json.dumps(action, ensure_ascii=False))
    lines.append(json.dumps(doc, ensure_ascii=False))

body = '\n'.join(lines) + '\n'
req = urllib.request.Request(
    f"{ES}/_bulk",
    data=body.encode('utf-8'),
    headers={"Content-Type": "application/x-ndjson"},
)
resp = urllib.request.urlopen(req, timeout=60)
result = json.loads(resp.read())
success = sum(1 for item in result['items'] if 'error' not in item.get('index', {}))
fail = len(result['items']) - success
print(f"      成功: {success}, 失败: {fail}")
if fail > 0:
    for item in result['items']:
        if 'error' in item.get('index', {}):
            print(f"      ❌ {item['index']['_id'][:40]}: {item['index']['error']['reason'][:100]}")

# 刷新
req = urllib.request.Request(f"{ES}/{INDEX}/_refresh", method="POST")
urllib.request.urlopen(req, timeout=30)

# 验证文档数
req = urllib.request.Request(f"{ES}/{INDEX}/_count")
resp = urllib.request.urlopen(req, timeout=15)
count = json.loads(resp.read())['count']
print(f"      最终文档数: {count}")

# ========== 5. 验证查询 ==========
print("\n[5/5] 验证查询...")

# 5a. match_all
req = urllib.request.Request(
    f"{ES}/{INDEX}/_search?pretty",
    data=json.dumps({"size": 2, "query": {"match_all": {}}}).encode('utf-8'),
    headers={"Content-Type": "application/json"},
)
resp = urllib.request.urlopen(req, timeout=15)
result = json.loads(resp.read())
print(f"  match_all: {result['hits']['total']} 条命中")
for h in result['hits']['hits']:
    src_keys = list(h['_source'].keys())
    print(f"    {h['_id'][:40]}: _source keys = {src_keys}")

# 5b. nested 查询 shouyezhenduan
print("\n  nested shouyezhenduan:")
req = urllib.request.Request(
    f"{ES}/{INDEX}/_search?pretty",
    data=json.dumps({
        "size": 2,
        "query": {
            "nested": {
                "path": "shouyezhenduan",
                "query": {"match": {"shouyezhenduan.diagnosis_name": "心肌梗死"}},
                "inner_hits": {"size": 5}
            }
        }
    }).encode('utf-8'),
    headers={"Content-Type": "application/json"},
)
resp = urllib.request.urlopen(req, timeout=15)
result = json.loads(resp.read())
print(f"    {result['hits']['total']} 条命中")
for h in result['hits']['hits']:
    hid = h['_id'][:40]
    ih = h.get('inner_hits', {}).get('shouyezhenduan', {}).get('hits', {}).get('hits', [])
    for ih_hit in ih:
        src = ih_hit.get('_source', {})
        dn = src.get('diagnosis_name', src.get('diagnosis_name.accurate', ''))
        print(f"    {hid}: {dn}")

# 5c. nested 查询 yizhu
print("\n  nested yizhu:")
req = urllib.request.Request(
    f"{ES}/{INDEX}/_search?pretty",
    data=json.dumps({
        "size": 2,
        "query": {
            "nested": {
                "path": "yizhu",
                "query": {"match": {"yizhu.order_item_name": "护理"}},
                "inner_hits": {"size": 5}
            }
        }
    }).encode('utf-8'),
    headers={"Content-Type": "application/json"},
)
resp = urllib.request.urlopen(req, timeout=15)
result = json.loads(resp.read())
print(f"    {result['hits']['total']} 条命中")
for h in result['hits']['hits']:
    hid = h['_id'][:40]
    ih = h.get('inner_hits', {}).get('yizhu', {}).get('hits', {}).get('hits', [])
    for ih_hit in ih:
        src = ih_hit.get('_source', {})
        print(f"    {hid}: {src.get('order_item_name', '')}")

# 5d. 多条件组合查询
print("\n  bool + nested 组合查询:")
req = urllib.request.Request(
    f"{ES}/{INDEX}/_search?pretty",
    data=json.dumps({
        "size": 2,
        "query": {
            "bool": {
                "must": [
                    {"range": {"binganshouye.discharge_time": {
                        "gte": "2013-01-01", "lte": "2013-12-31"
                    }}},
                    {"nested": {
                        "path": "shouyezhenduan",
                        "query": {"match": {"shouyezhenduan.diagnosis_name": "梗死"}},
                        "inner_hits": {"size": 5}
                    }}
                ]
            }
        }
    }).encode('utf-8'),
    headers={"Content-Type": "application/json"},
)
resp = urllib.request.urlopen(req, timeout=15)
result = json.loads(resp.read())
print(f"    {result['hits']['total']} 条命中")
for h in result['hits']['hits']:
    hid = h['_id'][:40]
    bsy = h['_source'].get('binganshouye', {})
    print(f"    {hid}: 出院 {bsy.get('discharge_time','?')}")
    ih = h.get('inner_hits', {}).get('shouyezhenduan', {}).get('hits', {}).get('hits', [])
    for ih_hit in ih:
        src = ih_hit.get('_source', {})
        print(f"      诊断: {src.get('diagnosis_name', '')}")

print("\n✅ 导入+验证完成!")
