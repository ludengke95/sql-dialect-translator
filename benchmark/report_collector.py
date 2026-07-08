#!/usr/bin/env python3
import argparse
import os
import re
import json

# =============================================================================
# TPC-C / TPC-H 压测日志解析与报告自动汇总脚本
# =============================================================================

def extract_tpmc(log_path):
    """从 BenchmarkSQL 运行日志中提取吞吐量 tpmC。"""
    if not os.path.exists(log_path):
        return None
    try:
        content = open(log_path, 'r', errors='ignore').read()
        match = re.search(r'(?:Measured\s+)?tpmC\s*=\s*([\d\.]+)', content, re.IGNORECASE)
        if match:
            return float(match.group(1))
    except Exception as e:
        print(f"解析 {log_path} 失败: {str(e)}")
    return None

def compare_tpch_data(data1, data2):
    """对比两组 TPC-H 结果集的一致性。"""
    if len(data1) != len(data2):
        return False
    # 由于可能存在排序不一致（或未加排序导致），可以转换成 set 进行无序比较
    return set(data1) == set(data2)

def generate_summary(args):
    md = []
    md.append("# 📊 SDTP SQL 翻译代理基准测试 (Benchmark) 性能报告\n")
    md.append("本报告由自动化性能工作流自动汇总生成，包含 **TPC-C** 事务吞吐量测评与 **TPC-H** 复杂分析查询正确性与耗时对比。\n")

    # 1. TPC-C 汇总段
    md.append("## 1. TPC-C 事务吞吐量对比 (BenchmarkSQL)")
    tpmc_pg = extract_tpmc(args.tpcc_pg_log)
    tpmc_m8 = extract_tpmc(args.tpcc_mysql8_log)
    tpmc_m5 = extract_tpmc(args.tpcc_mysql5_log)

    md.append("| 压测组别 (TPC-C) | 连接驱动类型 | 吞吐量 (tpmC) | 性能损耗率 |")
    md.append("| :--- | :--- | :--- | :--- |")
    
    if tpmc_pg is not None:
        md.append(f"| **基线组 (Postgres 直连)** | PostgreSQL JDBC 4.2 | `{tpmc_pg:.2f}` | - (Base) |")
    else:
        md.append("| **基线组 (Postgres 直连)** | PostgreSQL JDBC 4.2 | `未执行` | - |")

    if tpmc_m8 is not None:
        loss = ((tpmc_pg - tpmc_m8) / tpmc_pg * 100) if tpmc_pg else 0.0
        md.append(f"| **实验组 1 (SDTP 代理)** | MySQL JDBC 8.0 (Connector/J) | `{tpmc_m8:.2f}` | `{loss:.2f}%` |")
    else:
        md.append("| **实验组 1 (SDTP 代理)** | MySQL JDBC 8.0 (Connector/J) | `未执行` | - |")

    if tpmc_m5 is not None:
        loss_m5 = ((tpmc_pg - tpmc_m5) / tpmc_pg * 100) if tpmc_pg else 0.0
        md.append(f"| **实验组 2 (SDTP 代理)** | MySQL JDBC 5.1 (Connector/J) | `{tpmc_m5:.2f}` | `{loss_m5:.2f}%` |")
    else:
        md.append("| **实验组 2 (SDTP 代理)** | MySQL JDBC 5.1 (Connector/J) | `未执行` | - |")
        
    md.append("\n> [!NOTE]\n> **tpmC** (Transactions Per Minute C) 是 TPC-C 标准的核心吞吐量指标。性能损耗反映了 SDTP 在 SQL 方言转换和协议桥接上的开销。\n")

    # 2. TPC-H 汇总段
    md.append("## 2. TPC-H 复杂分析查询对比 (22 Queries)")
    
    pg_data = {}
    mysql_data = {}
    
    if args.tpch_pg_json and os.path.exists(args.tpch_pg_json):
        pg_data = json.load(open(args.tpch_pg_json, 'r'))
    if args.tpch_mysql_json and os.path.exists(args.tpch_mysql_json):
        mysql_data = json.load(open(args.tpch_mysql_json, 'r'))

    md.append("| 查询编号 | 基线耗时 (PG 直连) | 代理耗时 (SDTP 翻译) | 性能损耗率 | 数据正确性校验 (Correctness) |")
    md.append("| :--- | :--- | :--- | :--- | :--- |")

    success_count = 0
    total_count = 0

    for q in range(1, 23):
        q_id = f"Q{q:02d}"
        if q_id not in pg_data and q_id not in mysql_data:
            continue
            
        total_count += 1
        pg_item = pg_data.get(q_id, {"status": "FAILED", "elapsed": 0.0, "data": []})
        my_item = mysql_data.get(q_id, {"status": "FAILED", "elapsed": 0.0, "data": []})

        if pg_item["status"] == "SUCCESS" and my_item["status"] == "SUCCESS":
            # 校验数据集是否一致
            is_correct = compare_tpch_data(pg_item["data"], my_item["data"])
            correctness_str = "✅ PASSED" if is_correct else "❌ FAILED (数据不一致)"
            if is_correct:
                success_count += 1
            
            elapsed_pg = pg_item["elapsed"]
            elapsed_my = my_item["elapsed"]
            loss_pct = ((elapsed_my - elapsed_pg) / elapsed_pg * 100) if elapsed_pg else 0.0
            loss_str = f"`{loss_pct:+.2f}%`" if loss_pct > 0 else f"`{loss_pct:.2f}%`"
            
            md.append(f"| **{q_id}** | `{elapsed_pg:.3f}s` | `{elapsed_my:.3f}s` | {loss_str} | {correctness_str} |")
        else:
            err_reason = []
            if pg_item["status"] != "SUCCESS":
                err_reason.append(f"PG 运行失败: {pg_item.get('error', '未知错误')}")
            if my_item["status"] != "SUCCESS":
                err_reason.append(f"SDTP 运行失败: {my_item.get('error', '未知错误')}")
            md.append(f"| **{q_id}** | - | - | - | ❌ ERROR ({', '.join(err_reason)}) |")

    if total_count > 0:
        md.append(f"\n**TPC-H 正确性汇总率**: `{success_count}/{total_count} ({success_count/total_count*100:.1f}%)` 通关\n")

    return "\n".join(md)

def main():
    parser = argparse.ArgumentParser(description='SDTP Benchmark Report Collector')
    parser.add_argument('--tpcc-pg-log', default='/tmp/run_pg.log')
    parser.add_argument('--tpcc-mysql8-log', default='/tmp/run_mysql8.log')
    parser.add_argument('--tpcc-mysql5-log', default='/tmp/run_mysql5.log')
    parser.add_argument('--tpch-pg-json', default='/tmp/tpch_baseline.json')
    parser.add_argument('--tpch-mysql-json', default='/tmp/tpch_sdtp.json')
    parser.add_argument('--output-markdown', help='如果指定则输出到文件，否则输出到 GITHUB_STEP_SUMMARY')
    args = parser.parse_args()

    summary_md = generate_summary(args)

    # 写入输出
    out_file = args.output_markdown or os.environ.get('GITHUB_STEP_SUMMARY')
    if out_file:
        with open(out_file, 'w', encoding='utf-8') as f:
            f.write(summary_md)
        print(f"汇总 Markdown 报告已写入: {out_file}")
    else:
        print("未检测到输出路径，打印 Summary Markdown 如下:")
        print(summary_md)

if __name__ == '__main__':
    main()
