#!/usr/bin/env python3
"""
SDTP 基准测试执行器。

通过 MySQL 协议连接 SDTP（MySQL → PostgreSQL 翻译代理），
执行 TPC-C 和 TPC-H 的 MySQL 方言查询，
记录每条 SQL 的成功/失败和耗时，输出汇总报告。

用法:
    # 运行所有测试
    python runner.py --sdtp-host localhost --sdtp-port 3306

    # 只运行 TPC-H
    python runner.py --benchmark tpch

    # 只运行 TPC-C
    python runner.py --benchmark tpcc

    # 测试失败时打印 SDTP 日志
    python runner.py --sdtp-logs-dir /path/to/sdtp/logs
"""

import argparse
import os
import sys
import time
import json
from datetime import datetime

REPORT_TEMPLATE = """
========================================
  SDTP 基准测试报告
  测试时间: {timestamp}
  测试集: {benchmark}
  总 SQL 数: {total}
  成功: {success}
  失败: {failed}
  成功率: {rate:.1f}%
  总耗时: {total_time:.2f}s
  平均耗时: {avg_time:.4f}s
  最大耗时: {max_time:.4f}s
  最小耗时: {min_time:.4f}s
========================================
"""


def connect_mysql(host, port, user, password, database):
    """通过 MySQL 协议连接 SDTP。"""
    import mysql.connector
    conn = mysql.connector.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        database=database,
        charset='utf8',
        autocommit=True,
    )
    return conn


def read_queries(filepath):
    """读取 SQL 文件，按 `-- END` 标记分割为独立的查询块。

    每块可以包含一条或多条 SQL 语句（作为一组一起执行）。
    """
    queries = []
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    blocks = content.split('-- END')
    for block in blocks:
        block = block.strip()
        if not block:
            continue
        # 只保留 SQL，去掉注释行（以 -- 开头）
        lines = block.split('\n')
        sql_lines = []
        name = ""
        for line in lines:
            stripped = line.strip()
            if stripped.startswith('--'):
                if 'Q' in stripped or 'TPC' in stripped or '事务' in stripped:
                    name = stripped.lstrip('- ')
                continue
            if stripped:
                sql_lines.append(stripped)
        if sql_lines:
            queries.append({
                'name': name.strip() if name else f"query_{len(queries)+1}",
                'sql': '\n'.join(sql_lines),
            })
    return queries


def execute_query(cursor, sql_block, timeout=30):
    """执行一条或多条 SQL 语句，返回执行结果。

    Returns: (success: bool, result: str, duration: float)
    """
    statements = [s.strip() for s in sql_block.split('\n') if s.strip()]
    start = time.time()

    try:
        # 发送每条语句
        for stmt in statements:
            if stmt.upper().startswith('SELECT') or stmt.upper().startswith('WITH'):
                cursor.execute(stmt)
                # 消费结果集
                cursor.fetchall()
            elif stmt.upper().startswith('INSERT') or stmt.upper().startswith('UPDATE') \
                    or stmt.upper().startswith('DELETE') or stmt.upper().startswith('SET'):
                cursor.execute(stmt)
            else:
                cursor.execute(stmt)
                try:
                    cursor.fetchall()
                except Exception:
                    pass

        duration = time.time() - start
        return (True, f"OK ({duration:.4f}s)", duration)
    except Exception as e:
        duration = time.time() - start
        err_msg = str(e).replace('\n', ' ')[:200]
        return (False, f"ERROR: {err_msg}", duration)


def format_duration(seconds):
    if seconds < 1:
        return f"{seconds*1000:.0f}ms"
    return f"{seconds:.2f}s"


def run_benchmark(conn, queries, benchmark_name, sdtp_logs_dir=None):
    """运行一组基准测试查询。"""
    cursor = conn.cursor()
    results = {
        'benchmark': benchmark_name,
        'timestamp': datetime.now().isoformat(),
        'total': len(queries),
        'success': 0,
        'failed': 0,
        'details': [],
        'start_time': time.time(),
    }

    print(f"\n{'='*50}")
    print(f"  运行 {benchmark_name} 基准测试 ({len(queries)} 组 SQL)")
    print(f"{'='*50}")

    failed_queries = []

    for i, q in enumerate(queries, 1):
        print(f"\n[{i}/{len(queries)}] {q['name']}")
        print(f"  SQL: {q['sql'][:100]}{'...' if len(q['sql']) > 100 else ''}")

        success, result, duration = execute_query(cursor, q['sql'])

        status = "✅" if success else "❌"
        print(f"  {status} {result}")

        detail = {
            'name': q['name'],
            'sql': q['sql'],
            'success': success,
            'duration': round(duration, 4),
            'result': result,
        }
        results['details'].append(detail)

        if success:
            results['success'] += 1
        else:
            results['failed'] += 1
            failed_queries.append(q['name'])

    results['end_time'] = time.time()
    results['total_time'] = round(results['end_time'] - results['start_time'], 2)

    # 计算统计
    durations = [d['duration'] for d in results['details']]
    if durations:
        results['avg_time'] = round(sum(durations) / len(durations), 4)
        results['max_time'] = round(max(durations), 4)
        results['min_time'] = round(min(durations), 4)
    else:
        results['avg_time'] = results['max_time'] = results['min_time'] = 0

    results['rate'] = round(
        results['success'] / results['total'] * 100, 1
    ) if results['total'] > 0 else 0

    # 打印汇总
    print(REPORT_TEMPLATE.format(
        timestamp=results['timestamp'],
        benchmark=benchmark_name,
        total=results['total'],
        success=results['success'],
        failed=results['failed'],
        rate=results['rate'],
        total_time=results['total_time'],
        avg_time=results['avg_time'],
        max_time=results['max_time'],
        min_time=results['min_time'],
    ))

    # 打印失败查询
    if failed_queries:
        print(f"\n{'!'*50}")
        print(f"  失败查询列表:")
        print(f"{'!'*50}")
        for fq in failed_queries:
            print(f"  ❌ {fq}")
        # 打印失败的 SQL 详情
        for d in results['details']:
            if not d['success']:
                print(f"\n  --- 失败 SQL ---")
                print(f"  [{d['name']}]")
                for line in d['sql'].split('\n'):
                    print(f"  > {line}")
                print(f"  错误: {d['result']}")

    # 收集 SDTP 日志
    if sdtp_logs_dir and os.path.isdir(sdtp_logs_dir):
        print(f"\n{'='*50}")
        print(f"  SDTP 日志:")
        print(f"{'='*50}")
        try:
            log_file = None
            for f in sorted(os.listdir(sdtp_logs_dir)):
                if f.endswith('.log'):
                    log_file = os.path.join(sdtp_logs_dir, f)
                    break
            if log_file:
                with open(log_file, 'r', encoding='utf-8') as f:
                    # 只取最后 100 行
                    lines = f.readlines()
                    tail_lines = lines[-100:] if len(lines) > 100 else lines
                    print(f"  (最后 {len(tail_lines)} 行, 共 {len(lines)} 行)")
                    for line in tail_lines:
                        print(f"  {line.rstrip()}")
            else:
                print(f"  (未找到 .log 文件)")
        except Exception as e:
            print(f"  读取日志失败: {e}")

    cursor.close()
    return results


def save_report(results, output_dir='.'):
    """保存测试报告。"""
    benchmark = results['benchmark']
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    json_path = os.path.join(output_dir, f'report_{benchmark}_{timestamp}.json')
    txt_path = os.path.join(output_dir, f'report_{benchmark}_{timestamp}.txt')

    # JSON 报告
    with open(json_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"  JSON 报告: {json_path}")

    # 文本报告
    with open(txt_path, 'w', encoding='utf-8') as f:
        f.write(REPORT_TEMPLATE.format(
            timestamp=results['timestamp'],
            benchmark=benchmark,
            total=results['total'],
            success=results['success'],
            failed=results['failed'],
            rate=results['rate'],
            total_time=results['total_time'],
            avg_time=results['avg_time'],
            max_time=results['max_time'],
            min_time=results['min_time'],
        ))
        # 失败详情
        for d in results['details']:
            if not d['success']:
                f.write(f"\n--- 失败 SQL [{d['name']}] ---\n")
                f.write(f"{d['sql']}\n")
                f.write(f"错误: {d['result']}\n")
    print(f"  文本报告: {txt_path}")


def main():
    parser = argparse.ArgumentParser(description='SDTP 基准测试执行器')
    parser.add_argument('--sdtp-host', default='localhost')
    parser.add_argument('--sdtp-port', type=int, default=3306)
    parser.add_argument('--sdtp-user', default='root')
    parser.add_argument('--sdtp-password', default='proxy_password')
    parser.add_argument('--sdtp-database', default='mydb')
    parser.add_argument('--benchmark', choices=['tpch', 'tpcc', 'all'], default='all')
    parser.add_argument('--sdtp-logs-dir', default=None,
                        help='SDTP 日志目录，失败时打印日志')
    parser.add_argument('--output-dir', default='.',
                        help='报告输出目录')
    parser.add_argument('--tpch-queries', default=None,
                        help='TPC-H 查询文件路径（默认 benchmark/tpch/queries_mysql.sql）')
    parser.add_argument('--tpcc-queries', default=None,
                        help='TPC-C 查询文件路径（默认 benchmark/tpcc/queries_mysql.sql）')
    args = parser.parse_args()

    # 确定基准目录
    base_dir = os.path.dirname(os.path.abspath(__file__))
    tpch_file = args.tpch_queries or os.path.join(base_dir, 'tpch', 'queries_mysql.sql')
    tpcc_file = args.tpcc_queries or os.path.join(base_dir, 'tpcc', 'queries_mysql.sql')

    os.makedirs(args.output_dir, exist_ok=True)

    print(f"SDTP 基准测试执行器")
    print(f"  连接: {args.sdtp_user}@{args.sdtp_host}:{args.sdtp_port}/{args.sdtp_database}")
    print(f"  测试集: {args.benchmark}")

    # 连接 SDTP
    print(f"\n连接 SDTP...")
    try:
        conn = connect_mysql(
            args.sdtp_host, args.sdtp_port,
            args.sdtp_user, args.sdtp_password,
            args.sdtp_database,
        )
        print(f"  ✅ 连接成功")
    except Exception as e:
        print(f"  ❌ 连接失败: {e}")
        sys.exit(1)

    all_results = []

    try:
        # 运行 TPC-H
        if args.benchmark in ('tpch', 'all'):
            queries = read_queries(tpch_file)
            print(f"\n读取 TPC-H 查询: {len(queries)} 组")
            results = run_benchmark(conn, queries, 'TPC-H', args.sdtp_logs_dir)
            save_report(results, args.output_dir)
            all_results.append(results)

        # 运行 TPC-C
        if args.benchmark in ('tpcc', 'all'):
            queries = read_queries(tpcc_file)
            print(f"\n读取 TPC-C 查询: {len(queries)} 组")
            results = run_benchmark(conn, queries, 'TPC-C', args.sdtp_logs_dir)
            save_report(results, args.output_dir)
            all_results.append(results)

    finally:
        conn.close()

    # 汇总报告
    print(f"\n{'='*60}")
    print(f"  总汇总报告")
    print(f"{'='*60}")
    total_success = sum(r['success'] for r in all_results)
    total_failed = sum(r['failed'] for r in all_results)
    total_all = total_success + total_failed
    total_time = sum(r['total_time'] for r in all_results)

    print(f"  总 SQL 数: {total_all}")
    print(f"  总成功: {total_success}")
    print(f"  总失败: {total_failed}")
    print(f"  总成功率: {total_success/total_all*100:.1f}%" if total_all > 0 else "  N/A")
    print(f"  总耗时: {total_time:.2f}s")
    print(f"{'='*60}")

    if total_failed > 0:
        print(f"\n⚠️  存在失败查询，请检查上方详细信息。")
        sys.exit(1)
    else:
        print(f"\n✅ 所有测试通过！")
        sys.exit(0)


if __name__ == '__main__':
    main()
