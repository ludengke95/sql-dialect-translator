#!/usr/bin/env python3
"""
SDTP 基准测试执行器。

支持两种执行模式：
  --mode python     使用 Python mysql.connector（默认）
  --mode mysql-cli  使用 `mysql` 命令行客户端

用法:
    python runner.py --sdtp-host localhost --sdtp-port 3306
    python runner.py --mode mysql-cli --sdtp-host localhost --sdtp-port 3306
    python runner.py --benchmark tpch
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
from datetime import datetime

REPORT_TEMPLATE = """
========================================
  SDTP 基准测试报告
  测试时间: {timestamp}
  测试集: {benchmark}
  执行模式: {mode}
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


def read_queries(filepath):
    """读取 SQL 文件，按 `-- END` 标记分割为独立查询块。"""
    queries = []
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    blocks = content.split('-- END')
    for block in blocks:
        block = block.strip()
        if not block:
            continue
        lines = block.split('\n')
        sql_lines = []
        name = ""
        for line in lines:
            stripped = line.strip()
            if stripped.startswith('--'):
                if 'Q' in stripped or 'TPC' in stripped or '\u4e8b\u52a1' in stripped:
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


def execute_python(conn, sql_block):
    """通过 mysql.connector 执行 SQL。"""
    statements = [s.strip() for s in sql_block.split('\n') if s.strip()]
    cursor = conn.cursor()
    start = time.time()
    try:
        for stmt in statements:
            cursor.execute(stmt)
            upper = stmt.upper().strip()
            if upper.startswith('SELECT') or upper.startswith('WITH'):
                cursor.fetchall()
            else:
                try:
                    cursor.fetchall()
                except Exception:
                    pass
        duration = time.time() - start
        return (True, f"OK ({duration:.4f}s)", duration)
    except Exception as e:
        duration = time.time() - start
        return (False, f"ERROR: {str(e).replace(chr(10), ' ')[:200]}", duration)
    finally:
        cursor.close()


def execute_mysql_cli(sql_block, host, port, user, password, database):
    """通过 mysql 命令行客户端执行 SQL。"""
    tmp = tempfile.NamedTemporaryFile(mode='w', suffix='.sql', delete=False, encoding='utf-8')
    tmp.write(sql_block)
    if not sql_block.rstrip().endswith(';'):
        tmp.write(';')
    tmp.close()

    start = time.time()
    try:
        result = subprocess.run(
            ['mysql', '-h', host, '-P', str(port), '-u', user,
             f'-p{password}', database, '-f', '--connect-timeout=10',
             '-e', f'source {tmp.name}'],
            capture_output=True, timeout=60,
        )
        duration = time.time() - start

        if result.returncode == 0:
            return (True, f"OK ({duration:.4f}s)", duration)
        else:
            stderr = result.stderr.decode('utf-8', errors='replace').strip()
            err_msg = stderr[:300] if stderr else f"exit code {result.returncode}"
            return (False, f"ERROR: {err_msg}", duration)
    except subprocess.TimeoutExpired:
        duration = time.time() - start
        return (False, f"ERROR: timeout ({duration:.2f}s)", duration)
    except FileNotFoundError:
        return (False, "ERROR: mysql CLI not found", 0)
    except Exception as e:
        duration = time.time() - start
        return (False, f"ERROR: {str(e)[:200]}", duration)
    finally:
        os.unlink(tmp.name)


def run_benchmark(args, queries, benchmark_name):
    """运行一组基准测试查询。"""
    results = {
        'benchmark': benchmark_name,
        'mode': args.mode,
        'timestamp': datetime.now().isoformat(),
        'total': len(queries),
        'success': 0,
        'failed': 0,
        'details': [],
        'start_time': time.time(),
    }

    print(f"\n{'='*50}")
    print(f"  \u8fd0\u884c {benchmark_name} \u57fa\u51c6\u6d4b\u8bd5 ({len(queries)} \u7ec4 SQL\uff0c\u6a21\u5f0f: {args.mode})")
    print(f"{'='*50}")

    failed_queries = []

    for i, q in enumerate(queries, 1):
        print(f"\n[{i}/{len(queries)}] {q['name']}")
        print(f"  SQL: {q['sql'][:100]}{'...' if len(q['sql']) > 100 else ''}")

        if args.mode == 'mysql-cli':
            success, result, duration = execute_mysql_cli(
                q['sql'], args.sdtp_host, args.sdtp_port,
                args.sdtp_user, args.sdtp_password, args.sdtp_database
            )
        else:
            success, result, duration = execute_python(args._conn, q['sql'])

        status = "\u2705" if success else "\u274c"
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

    print(REPORT_TEMPLATE.format(
        timestamp=results['timestamp'],
        benchmark=benchmark_name,
        mode=args.mode,
        total=results['total'],
        success=results['success'],
        failed=results['failed'],
        rate=results['rate'],
        total_time=results['total_time'],
        avg_time=results['avg_time'],
        max_time=results['max_time'],
        min_time=results['min_time'],
    ))

    if failed_queries:
        print(f"\n{'!'*50}")
        print(f"  \u5931\u8d25\u67e5\u8be2\u5217\u8868:")
        print(f"{'!'*50}")
        for fq in failed_queries:
            print(f"  \u274c {fq}")
        for d in results['details']:
            if not d['success']:
                print(f"\n  --- \u5931\u8d25 SQL ---")
                print(f"  [{d['name']}]")
                for line in d['sql'].split('\n'):
                    print(f"  > {line}")
                print(f"  \u9519\u8bef: {d['result']}")

    if args.sdtp_logs_dir and os.path.isdir(args.sdtp_logs_dir):
        print(f"\n{'='*50}")
        print(f"  SDTP \u65e5\u5fd7:")
        print(f"{'='*50}")
        try:
            log_file = None
            for f in sorted(os.listdir(args.sdtp_logs_dir)):
                if f.endswith('.log'):
                    log_file = os.path.join(args.sdtp_logs_dir, f)
                    break
            if log_file:
                with open(log_file, 'r', encoding='utf-8') as f:
                    lines = f.readlines()
                    tail_lines = lines[-100:] if len(lines) > 100 else lines
                    print(f"  (\u6700\u540e {len(tail_lines)} \u884c, \u5171 {len(lines)} \u884c)")
                    for line in tail_lines:
                        print(f"  {line.rstrip()}")
            else:
                print(f"  (\u672a\u627e\u5230 .log \u6587\u4ef6)")
        except Exception as e:
            print(f"  \u8bfb\u53d6\u65e5\u5fd7\u5931\u8d25: {e}")

    return results


def save_report(results, output_dir='.'):
    """保存测试报告。"""
    benchmark = results['benchmark']
    mode = results['mode']
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    json_path = os.path.join(output_dir, f'report_{benchmark}_{mode}_{timestamp}.json')
    txt_path = os.path.join(output_dir, f'report_{benchmark}_{mode}_{timestamp}.txt')

    with open(json_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"  JSON \u62a5\u544a: {json_path}")

    with open(txt_path, 'w', encoding='utf-8') as f:
        f.write(REPORT_TEMPLATE.format(
            timestamp=results['timestamp'],
            benchmark=benchmark,
            mode=mode,
            total=results['total'],
            success=results['success'],
            failed=results['failed'],
            rate=results['rate'],
            total_time=results['total_time'],
            avg_time=results['avg_time'],
            max_time=results['max_time'],
            min_time=results['min_time'],
        ))
        for d in results['details']:
            if not d['success']:
                f.write(f"\n--- \u5931\u8d25 SQL [{d['name']}] ---\n")
                f.write(f"{d['sql']}\n")
                f.write(f"\u9519\u8bef: {d['result']}\n")
    print(f"  \u6587\u672c\u62a5\u544a: {txt_path}")


def main():
    parser = argparse.ArgumentParser(description='SDTP \u57fa\u51c6\u6d4b\u8bd5\u6267\u884c\u5668')
    parser.add_argument('--mode', choices=['python', 'mysql-cli'], default='python',
                        help='\u6267\u884c\u6a21\u5f0f\uff1apython\uff08mysql.connector\uff09\u6216 mysql-cli\uff08mysql \u547d\u4ee4\u884c\uff09')
    parser.add_argument('--sdtp-host', default='localhost')
    parser.add_argument('--sdtp-port', type=int, default=3306)
    parser.add_argument('--sdtp-user', default='root')
    parser.add_argument('--sdtp-password', default='proxy_password')
    parser.add_argument('--sdtp-database', default='mydb')
    parser.add_argument('--benchmark', choices=['tpch', 'tpcc', 'all'], default='all')
    parser.add_argument('--sdtp-logs-dir', default=None,
                        help='SDTP \u65e5\u5fd7\u76ee\u5f55\uff0c\u5931\u8d25\u65f6\u6253\u5370\u65e5\u5fd7')
    parser.add_argument('--output-dir', default='.',
                        help='\u62a5\u544a\u8f93\u51fa\u76ee\u5f55')
    parser.add_argument('--tpch-queries', default=None,
                        help='TPC-H \u67e5\u8be2\u6587\u4ef6\u8def\u5f84')
    parser.add_argument('--tpcc-queries', default=None,
                        help='TPC-C \u67e5\u8be2\u6587\u4ef6\u8def\u5f84')
    args = parser.parse_args()

    base_dir = os.path.dirname(os.path.abspath(__file__))
    tpch_file = args.tpch_queries or os.path.join(base_dir, 'tpch', 'queries_mysql.sql')
    tpcc_file = args.tpcc_queries or os.path.join(base_dir, 'tpcc', 'queries_mysql.sql')

    os.makedirs(args.output_dir, exist_ok=True)

    print(f"SDTP \u57fa\u51c6\u6d4b\u8bd5\u6267\u884c\u5668")
    print(f"  \u6a21\u5f0f: {args.mode}")
    print(f"  \u8fde\u63a5: {args.sdtp_user}@{args.sdtp_host}:{args.sdtp_port}/{args.sdtp_database}")
    print(f"  \u6d4b\u8bd5\u96c6: {args.benchmark}")

    if args.mode == 'python':
        print(f"\n\u8fde\u63a5 SDTP...")
        try:
            import mysql.connector
            args._conn = mysql.connector.connect(
                host=args.sdtp_host, port=args.sdtp_port,
                user=args.sdtp_user, password=args.sdtp_password,
                database=args.sdtp_database,
                charset='utf8', autocommit=True,
            )
            print(f"  \u2705 \u8fde\u63a5\u6210\u529f")
        except Exception as e:
            print(f"  \u274c \u8fde\u63a5\u5931\u8d25: {e}")
            sys.exit(1)
    else:
        args._conn = None
        try:
            subprocess.run(['mysql', '--version'], capture_output=True, timeout=5, check=True)
            print(f"  \u2705 mysql CLI \u53ef\u7528")
        except FileNotFoundError:
            print(f"  \u274c mysql CLI \u672a\u5b89\u88c5")
            sys.exit(1)
        except Exception as e:
            print(f"  \u274c mysql CLI \u68c0\u67e5\u5931\u8d25: {e}")
            sys.exit(1)

    all_results = []

    try:
        if args.benchmark in ('tpch', 'all'):
            queries = read_queries(tpch_file)
            print(f"\n\u8bfb\u53d6 TPC-H \u67e5\u8be2: {len(queries)} \u7ec4")
            results = run_benchmark(args, queries, 'TPC-H')
            save_report(results, args.output_dir)
            all_results.append(results)

        if args.benchmark in ('tpcc', 'all'):
            queries = read_queries(tpcc_file)
            print(f"\n\u8bfb\u53d6 TPC-C \u67e5\u8be2: {len(queries)} \u7ec4")
            results = run_benchmark(args, queries, 'TPC-C')
            save_report(results, args.output_dir)
            all_results.append(results)

    finally:
        if args._conn:
            args._conn.close()

    print(f"\n{'='*60}")
    print(f"  \u603b\u6c47\u603b\u62a5\u544a")
    print(f"{'='*60}")
    total_success = sum(r['success'] for r in all_results)
    total_failed = sum(r['failed'] for r in all_results)
    total_all = total_success + total_failed
    total_time = sum(r['total_time'] for r in all_results)

    print(f"  \u603b SQL \u6570: {total_all}")
    print(f"  \u603b\u6210\u529f: {total_success}")
    print(f"  \u603b\u5931\u8d25: {total_failed}")
    print(f"  \u603b\u6210\u529f\u7387: {total_success/total_all*100:.1f}%" if total_all > 0 else "  N/A")
    print(f"  \u603b\u8017\u65f6: {total_time:.2f}s")
    print(f"{'='*60}")

    if total_failed > 0:
        sys.exit(1)
    else:
        sys.exit(0)


if __name__ == '__main__':
    main()
