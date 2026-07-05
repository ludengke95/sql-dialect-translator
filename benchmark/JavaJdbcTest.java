import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java JDBC TPC-C / TPC-H integration test.
 *
 * <p>Connects to SDTP Proxy via MySQL JDBC driver, executes MySQL-dialect
 * queries, and verifies translation + protocol compatibility.
 *
 * <p>Usage:
 * <pre>
 *   javac -cp mysql-connector-java-8.0.33.jar JavaJdbcTest.java
 *   java -cp .:mysql-connector-java-8.0.33.jar JavaJdbcTest \
 *       --host localhost --port 3306 --user root --password proxy_password \
 *       --database mydb
 * </pre>
 */
public class JavaJdbcTest {

    private static final Pattern END_MARKER = Pattern.compile("\n-- END\\b[^\\n]*");
    private static final Pattern NAME_MARKER = Pattern.compile("--\\s*(.+)");
    private static final Pattern Q_NAME = Pattern.compile("\\bQ\\d+\\b");

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String database;
    private final String tpchQueries;
    private final String tpccQueries;
    private final String outputDir;

    public JavaJdbcTest(String host, int port, String user, String password,
                         String database, String tpchQueries, String tpccQueries,
                         String outputDir) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.database = database;
        this.tpchQueries = tpchQueries;
        this.tpccQueries = tpccQueries;
        this.outputDir = outputDir;
    }

    // ==================== Query File Parsing ====================

    static List<QueryBlock> readQueries(String filepath) throws IOException {
        String content = new String(Files.readAllBytes(Paths.get(filepath)), StandardCharsets.UTF_8);
        String[] blocks = END_MARKER.split(content);
        List<QueryBlock> result = new ArrayList<>();

        for (String block : blocks) {
            block = block.trim();
            if (block.isEmpty()) continue;

            String[] lines = block.split("\n");
            StringBuilder sqlBuilder = new StringBuilder();
            String name = "";

            for (String line : lines) {
                String stripped = line.trim();
                if (stripped.startsWith("--")) {
                    Matcher m = Q_NAME.matcher(stripped);
                    String cnName = "事务";
                    if (m.find() || stripped.contains(cnName)) {
                        Matcher nm = NAME_MARKER.matcher(stripped);
                        if (nm.matches()) {
                            name = nm.group(1).trim();
                        }
                    }
                    continue;
                }
                if (!stripped.isEmpty()) {
                    if (sqlBuilder.length() > 0) sqlBuilder.append('\n');
                    sqlBuilder.append(stripped);
                }
            }

            String sql = sqlBuilder.toString().trim().toUpperCase();
            if (sql.isEmpty()) continue;
            if (!sql.startsWith("SELECT") && !sql.startsWith("WITH")
                    && !sql.startsWith("INSERT") && !sql.startsWith("UPDATE")
                    && !sql.startsWith("DELETE")) {
                continue;
            }

            String rawName = name.isEmpty() ? "query_" + (result.size() + 1) : name;
            result.add(new QueryBlock(rawName, sqlBuilder.toString().trim()));
        }
        return result;
    }

    static class QueryBlock {
        final String name;
        final String sql;
        QueryBlock(String name, String sql) { this.name = name; this.sql = sql; }
    }

    // ==================== Report Data ====================

    static class Report {
        String benchmark;
        String mode = "java-jdbc";
        String timestamp;
        int total;
        int success;
        int failed;
        List<Detail> details = new ArrayList<>();
        double startTime;
        double endTime;
        double totalTime;
        double avgTime;
        double maxTime;
        double minTime;
        double rate;

        void addDetail(String name, String sql, boolean ok, double duration, String resultMsg) {
            details.add(new Detail(name, sql, ok, duration, resultMsg));
        }

        void finalize() {
            total = details.size();
            success = (int) details.stream().filter(d -> d.success).count();
            failed = total - success;
            totalTime = endTime - startTime;
            DoubleSummaryStatistics stats = details.stream()
                    .mapToDouble(d -> d.duration).summaryStatistics();
            avgTime = stats.getAverage();
            maxTime = stats.getMax();
            minTime = stats.getMin();
            rate = total > 0 ? (double) success / total * 100.0 : 0.0;
        }
    }

    static class Detail {
        String name; String sql; boolean success; double duration; String result;
        Detail(String name, String sql, boolean success, double duration, String result) {
            this.name = name; this.sql = sql; this.success = success;
            this.duration = duration; this.result = result;
        }
    }

    // ==================== Execution ====================

    static Detail execute(Connection conn, QueryBlock q) {
        String[] statements = q.sql.split("\n");
        long start = System.nanoTime();
        try {
            for (String stmt : statements) {
                stmt = stmt.trim();
                if (stmt.isEmpty()) continue;
                try (Statement s = conn.createStatement()) {
                    boolean isResultSet;
                    try {
                        isResultSet = s.execute(stmt);
                    } catch (SQLException e) {
                        if (stmt.endsWith(";")) {
                            isResultSet = s.execute(stmt.substring(0, stmt.length() - 1));
                        } else {
                            throw e;
                        }
                    }
                    if (isResultSet) {
                        try (ResultSet rs = s.getResultSet()) {
                            while (rs.next()) { /* consume all rows */ }
                        }
                    }
                }
            }
            double duration = (System.nanoTime() - start) / 1_000_000_000.0;
            return new Detail(q.name, q.sql, true, duration,
                    "OK (" + String.format("%.4f", duration) + "s)");
        } catch (Exception e) {
            double duration = (System.nanoTime() - start) / 1_000_000_000.0;
            String msg = e.getMessage();
            if (msg != null && msg.length() > 200) msg = msg.substring(0, 200);
            return new Detail(q.name, q.sql, false, duration, "ERROR: " + msg);
        }
    }

    Report runBenchmark(List<QueryBlock> queries, String benchmarkName) {
        Report report = new Report();
        report.benchmark = benchmarkName;
        report.timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date());
        report.startTime = System.nanoTime() / 1_000_000_000.0;

        System.out.println();
        System.out.println("==================================================");
        System.out.println("  Running " + benchmarkName + " (" + queries.size()
                + " blocks, mode: java-jdbc)");
        System.out.println("==================================================");

        try (Connection conn = DriverManager.getConnection(
                "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=false&allowPublicKeyRetrieval=true",
                user, password)) {

            for (int i = 0; i < queries.size(); i++) {
                QueryBlock q = queries.get(i);
                System.out.printf("\n[%d/%d] %s%n", i + 1, queries.size(), q.name);
                String sqlPreview = q.sql.length() > 100
                        ? q.sql.substring(0, 100) + "..." : q.sql;
                System.out.println("  SQL: " + sqlPreview.replace("\n", "\\n"));

                Detail detail = execute(conn, q);
                String status = detail.success ? "\u2705" : "\u274c";
                System.out.println("  " + status + " " + detail.result);

                report.addDetail(detail.name, detail.sql, detail.success,
                        detail.duration, detail.result);
            }
        } catch (SQLException e) {
            System.err.println("FATAL: Connection failed: " + e.getMessage());
            System.exit(1);
        }

        report.endTime = System.nanoTime() / 1_000_000_000.0;
        report.finalize();
        printSummary(report);
        return report;
    }

    void printSummary(Report r) {
        System.out.println();
        System.out.println("========================================");
        System.out.println("  JDBC Test Report");
        System.out.println("  Timestamp: " + r.timestamp);
        System.out.println("  Benchmark: " + r.benchmark);
        System.out.println("  Mode: " + r.mode);
        System.out.println("  Total: " + r.total);
        System.out.println("  Success: " + r.success);
        System.out.println("  Failed: " + r.failed);
        System.out.printf("  Rate: %.1f%%%n", r.rate);
        System.out.printf("  Total time: %.2fs%n", r.totalTime);
        System.out.printf("  Avg time: %.4fs%n", r.avgTime);
        System.out.printf("  Max time: %.4fs%n", r.maxTime);
        System.out.printf("  Min time: %.4fs%n", r.minTime);
        System.out.println("========================================");
        if (r.failed > 0) {
            for (Detail d : r.details) {
                if (!d.success) {
                    System.out.println("  Failed: " + d.name);
                    System.out.println("    Error: " + d.result);
                }
            }
        }
    }

    // ==================== JSON Output ====================

    static String toJson(Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"benchmark\": \"").append(escapeJson(r.benchmark)).append("\",\n");
        sb.append("  \"mode\": \"").append(escapeJson(r.mode)).append("\",\n");
        sb.append("  \"timestamp\": \"").append(escapeJson(r.timestamp)).append("\",\n");
        sb.append("  \"total\": ").append(r.total).append(",\n");
        sb.append("  \"success\": ").append(r.success).append(",\n");
        sb.append("  \"failed\": ").append(r.failed).append(",\n");
        sb.append("  \"total_time\": ").append(String.format("%.2f", r.totalTime)).append(",\n");
        sb.append("  \"avg_time\": ").append(String.format("%.4f", r.avgTime)).append(",\n");
        sb.append("  \"max_time\": ").append(String.format("%.4f", r.maxTime)).append(",\n");
        sb.append("  \"min_time\": ").append(String.format("%.4f", r.minTime)).append(",\n");
        sb.append("  \"rate\": ").append(String.format("%.1f", r.rate)).append(",\n");
        sb.append("  \"sdtp_error\": false,\n");
        sb.append("  \"details\": [\n");
        for (int i = 0; i < r.details.size(); i++) {
            Detail d = r.details.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": \"").append(escapeJson(d.name)).append("\",\n");
            sb.append("      \"sql\": \"").append(escapeJson(d.sql)).append("\",\n");
            sb.append("      \"success\": ").append(d.success).append(",\n");
            sb.append("      \"duration\": ").append(String.format("%.4f", d.duration)).append(",\n");
            sb.append("      \"result\": \"").append(escapeJson(d.result)).append("\"\n");
            sb.append("    }");
            if (i < r.details.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ==================== Main ====================

    public static void main(String[] args) throws Exception {
        String host = "localhost";
        int port = 3306;
        String user = "root";
        String password = "proxy_password";
        String database = "mydb";
        String tpchQueries = "benchmark/tpch/queries_mysql.sql";
        String tpccQueries = "benchmark/tpcc/queries_mysql.sql";
        String outputDir = ".";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--user": user = args[++i]; break;
                case "--password": password = args[++i]; break;
                case "--database": database = args[++i]; break;
                case "--tpch-queries": tpchQueries = args[++i]; break;
                case "--tpcc-queries": tpccQueries = args[++i]; break;
                case "--output-dir": outputDir = args[++i]; break;
                case "--help": printUsage(); return;
            }
        }

        JavaJdbcTest test = new JavaJdbcTest(host, port, user, password, database,
                tpchQueries, tpccQueries, outputDir);

        System.out.println("Java JDBC SDTP Integration Test");
        System.out.println("  Connection: " + user + "@" + host + ":" + port + "/" + database);

        List<Report> allReports = new ArrayList<>();

        if (new File(tpchQueries).exists()) {
            List<QueryBlock> list = readQueries(tpchQueries);
            System.out.println("\nRead TPC-H queries: " + list.size() + " blocks");
            Report r = test.runBenchmark(list, "TPC-H");
            saveReport(r, outputDir);
            allReports.add(r);
        }

        if (new File(tpccQueries).exists()) {
            List<QueryBlock> list = readQueries(tpccQueries);
            System.out.println("\nRead TPC-C queries: " + list.size() + " blocks");
            Report r = test.runBenchmark(list, "TPC-C");
            saveReport(r, outputDir);
            allReports.add(r);
        }

        // Summary
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  Final Summary");
        System.out.println("============================================================");
        int totalSuccess = allReports.stream().mapToInt(r -> r.success).sum();
        int totalFailed = allReports.stream().mapToInt(r -> r.failed).sum();
        int totalAll = totalSuccess + totalFailed;
        System.out.println("  Total queries: " + totalAll);
        System.out.println("  Total success: " + totalSuccess);
        System.out.println("  Total failed: " + totalFailed);
        if (totalAll > 0)
            System.out.printf("  Total rate: %.1f%%%n", (double) totalSuccess / totalAll * 100.0);
        System.out.printf("  Total time: %.2fs%n",
                allReports.stream().mapToDouble(r -> r.totalTime).sum());
        System.out.println();
        if (totalFailed > 0) { System.out.println("  \u274c FAILED"); System.exit(1); }
        else { System.out.println("  \u2705 PASSED"); }
    }

    static void printUsage() {
        System.out.println("JavaJdbcTest — Java JDBC TPC-C/TPC-H integration test");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --host <host>           SDTP Proxy host (default: localhost)");
        System.out.println("  --port <port>           SDTP Proxy port (default: 3306)");
        System.out.println("  --user <user>           SDTP auth user (default: root)");
        System.out.println("  --password <password>   SDTP auth password (default: proxy_password)");
        System.out.println("  --database <database>   Target database (default: mydb)");
        System.out.println("  --tpch-queries <file>   TPC-H queries file (default: benchmark/tpch/queries_mysql.sql)");
        System.out.println("  --tpcc-queries <file>   TPC-C queries file (default: benchmark/tpcc/queries_mysql.sql)");
        System.out.println("  --output-dir <dir>      Report output directory (default: .)");
    }

    static void saveReport(Report report, String outputDir) throws IOException {
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String bn = report.benchmark.toLowerCase();
        String jsonPath = outputDir + "/report_" + bn + "_" + report.mode + "_" + timestamp + ".json";
        String txtPath = outputDir + "/report_" + bn + "_" + report.mode + "_" + timestamp + ".txt";

        Files.write(Paths.get(jsonPath), toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("  JSON report: " + jsonPath);

        StringBuilder txt = new StringBuilder();
        txt.append("========================================\n");
        txt.append("  JDBC Test Report\n");
        txt.append("  Timestamp: ").append(report.timestamp).append("\n");
        txt.append("  Benchmark: ").append(report.benchmark).append("\n");
        txt.append("  Mode: ").append(report.mode).append("\n");
        txt.append("  Total: ").append(report.total).append("\n");
        txt.append("  Success: ").append(report.success).append("\n");
        txt.append("  Failed: ").append(report.failed).append("\n");
        txt.append(String.format("  Rate: %.1f%%\n", report.rate));
        txt.append(String.format("  Total time: %.2fs\n", report.totalTime));
        txt.append(String.format("  Avg time: %.4fs\n", report.avgTime));
        txt.append(String.format("  Max time: %.4fs\n", report.maxTime));
        txt.append(String.format("  Min time: %.4fs\n", report.minTime));
        txt.append("========================================\n");
        for (Detail d : report.details) {
            if (!d.success) {
                txt.append("\n--- Failed SQL [").append(d.name).append("] ---\n");
                txt.append(d.sql).append("\n");
                txt.append("Error: ").append(d.result).append("\n");
            }
        }
        Files.write(Paths.get(txtPath), txt.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("  Text report: " + txtPath);
    }
}