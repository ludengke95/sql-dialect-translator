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
  SDTP 日志 ERROR: {sdtp_error}
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
            combined = '\n'.join(sql_lines).strip().upper()
            # 只保留真正以 SQL 关键字开头的查询（SELECT 后可跟空格或\n）
            if combined.startswith('SELECT') or combined.startswith('WITH') \
               or combined.startswith('INSERT') or combined.startswith('UPDATE') \
               or combined.startswith('DELETE'):
                queries.append({
                    'name': name.strip() if name else f"query_{len(queries)+1}",
                    'sql': '\n'.join(sql_lines),
                })
    return queries


def check_sdtp_logs(log_dir):
    """检查 SDTP 日志中是否有 ERROR 或 Exception 行。"""
    if not log_dir or not os.path.isdir(log_dir):
        return False, []
    errors = []
    for f in sorted(os.listdir(log_dir)):
        if f.endswith('.log'):
            fpath = os.path.join(log_dir, f)
            try:
                with open(fpath, 'r', encoding='utf-8', errors='replace') as lf:
                    for line in lf:
                        if 'ERROR' in line or 'Exception' in line:
                            errors.append(line.rstrip())
            except Exception:
                pass
    return len(errors) > 0, errors


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
        with open(tmp.name, 'r', encoding='utf-8') as sql_file:
            result = subprocess.run(
                ['mysql', '-h', host, '-P', str(port), '--protocol', 'TCP', '-u', user,
                 f'-p{password}', database, '-f', '--connect-timeout=10'],
                stdin=sql_file,
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
        'sdtp_error': False,
        'sdtp_error_logs': [],
    }

    print(f"\n{'='*50}")
    print(f"  运行 {benchmark_name} 基准测试 ({len(queries)} 组 SQL，模式: {args.mode})")
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

    sdtp_has_error, sdtp_errors = check_sdtp_logs(args.sdtp_logs_dir)
    results['sdtp_error'] = sdtp_has_error
    results['sdtp_error_logs'] = sdtp_errors

    # 打印报表
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
        sdtp_error="\u2705 \u65e0" if not sdtp_has_error else f"\u274c {len(sdtp_errors)} 行",
    ))

    if failed_queries:
        print(f"\n{'!'*50}")
        print(f"  失败查询列表:")
        print(f"{'!'*50}")
        for fq in failed_queries:
            print(f"  \u274c {fq}")
        for d in results['details']:
            if not d['success']:
                print(f"\n  --- 失败 SQL ---")
                print(f"  [{d['name']}]")
                for line in d['sql'].split('\n'):
                    print(f"  > {line}")
                print(f"  错误: {d['result']}")

    if sdtp_has_error:
        print(f"\n{'='*50}")
        print(f"  SDTP 日志中发现 ERROR/Exception")
        print(f"  共 {len(sdtp_errors)} 行错误日志 (显示最后 20 行):")
        print(f"{'='*50}")
        for err_line in sdtp_errors[-20:]:
            print(f"  {err_line}")

    return results


def save_report(results, output_dir='.'):
    """保存测试报告。"""
    benchmark = results['benchmark']
    mode = results['mode']
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    json_path = os.path.join(output_dir, f'report_{benchmark}_{mode}_{timestamp}.json')
    txt_path = os.path.join(output_dir, f'report_{benchmark}_{mode}_{timestamp}.txt')

    sdtp_error_str = "\u2705 \u65e0" if not results['sdtp_error'] else f"\u274c {len(results['sdtp_error_logs'])} 行"

    with open(json_path, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"  JSON 报告: {json_path}")

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
            sdtp_error=sdtp_error_str,
        ))
        for d in results['details']:
            if not d['success']:
                f.write(f"\n--- 失败 SQL [{d['name']}] ---\n")
                f.write(f"{d['sql']}\n")
                f.write(f"\u9519误: {d['result']}\n")
        if results['sdtp_error']:
            f.write(f"\n--- SDTP 日志 ERROR ---\n")
            for err_line in results['sdtp_error_logs'][-50:]:
                f.write(f"{err_line}\n")
    print(f"  文本报告: {txt_path}")


def main():
    parser = argparse.ArgumentParser(description='SDTP 基准测试执行器')
    parser.add_argument('--mode', choices=['python', 'mysql-cli'], default='python',
                        help='执行模式')
    parser.add_argument('--sdtp-host', default='localhost')
    parser.add_argument('--sdtp-port', type=int, default=3306)
    parser.add_argument('--sdtp-user', default='root')
    parser.add_argument('--sdtp-password', default='proxy_password')
    parser.add_argument('--sdtp-database', default='mydb')
    parser.add_argument('--benchmark', choices=['tpch', 'tpcc', 'all'], default='all')
    parser.add_argument('--sdtp-logs-dir', default=None,
                        help='SDTP 日志目录')
    parser.add_argument('--output-dir', default='.',
                        help='报告输出目录')
    parser.add_argument('--tpch-queries', default=None,
                        help='TPC-H 查询文件路径')
    parser.add_argument('--tpcc-queries', default=None,
                        help='TPC-C 查询文件路径')
    args = parser.parse_args()

    base_dir = os.path.dirname(os.path.abspath(__file__))
    tpch_file = args.tpch_queries or os.path.join(base_dir, 'tpch', 'queries_mysql.sql')
    tpcc_file = args.tpcc_queries or os.path.join(base_dir, 'tpcc', 'queries_mysql.sql')

    os.makedirs(args.output_dir, exist_ok=True)

    print(f"SDTP 基准测试执行器")
    print(f"  模式: {args.mode}")
    print(f"  连接: {args.sdtp_user}@{args.sdtp_host}:{args.sdtp_port}/{args.sdtp_database}")
    print(f"  测试集: {args.benchmark}")

    if args.mode == 'python':
        print(f"\n连接 SDTP...")
        try:
            import mysql.connector
            args._conn = mysql.connector.connect(
                host=args.sdtp_host, port=args.sdtp_port,
                user=args.sdtp_user, password=args.sdtp_password,
                database=args.sdtp_database,
                charset='utf8', autocommit=True,
            )
            print(f"  \u2705 连接成功")
        except Exception as e:
            print(f"  \u274c 连接失败: {e}")
            sys.exit(1)
    else:
        args._conn = None
        try:
            subprocess.run(['mysql', '--version'], capture_output=True, timeout=5, check=True)
            print(f"  \u2705 mysql CLI 可用")
        except FileNotFoundError:
            print(f"  \u274c mysql CLI 未安装")
            sys.exit(1)
        except Exception as e:
            print(f"  \u274c mysql CLI 检查失败: {e}")
            sys.exit(1)

    all_results = []

    try:
        if args.benchmark in ('tpch', 'all'):
            queries = read_queries(tpch_file)
            print(f"\n读取 TPC-H 查询: {len(queries)} 组")
            results = run_benchmark(args, queries, 'TPC-H')
            save_report(results, args.output_dir)
            all_results.append(results)

        if args.benchmark in ('tpcc', 'all'):
            queries = read_queries(tpcc_file)
            print(f"\n读取 TPC-C 查询: {len(queries)} 组")
            results = run_benchmark(args, queries, 'TPC-C')
            save_report(results, args.output_dir)
            all_results.append(results)

    finally:
        if args._conn:
            args._conn.close()

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

    any_sdtp_error = any(r.get('sdtp_error', False) for r in all_results)
    if any_sdtp_error:
        print(f"\n  ❌ SDTP 日志中发现 ERROR/Exception，测试视为不通过")

    if total_failed > 0 or any_sdtp_error:
        sys.exit(1)
    else:
        sys.exit(0)


if __name__ == '__main__':
    main()
