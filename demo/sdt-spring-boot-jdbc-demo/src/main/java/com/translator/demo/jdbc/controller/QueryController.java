package com.translator.demo.jdbc.controller;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QueryController {

    @Autowired
    private DataSource dataSource;

    @PostMapping("/query")
    public ResponseEntity<?> executeQuery(@RequestBody Map<String, String> request) {
        String sql = request.get("sql");
        if (sql == null || sql.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Parameter 'sql' is required");
        }

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            boolean hasResultSet = stmt.execute(sql);
            if (hasResultSet) {
                try (ResultSet rs = stmt.getResultSet()) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    ResultSetMetaData md = rs.getMetaData();
                    int columnCount = md.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            String columnName = md.getColumnLabel(i);
                            row.put(columnName, rs.getObject(i));
                        }
                        list.add(row);
                    }
                    return ResponseEntity.ok(list);
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("updateCount", updateCount);
                return ResponseEntity.ok(result);
            }
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/info")
    public ResponseEntity<?> getInfo() {
        try (Connection conn = dataSource.getConnection()) {
            String targetDialect = "Unknown";
            if (conn.isWrapperFor(com.translator.jdbc.TranslatorConnection.class)) {
                com.translator.jdbc.TranslatorConnection tc =
                        conn.unwrap(com.translator.jdbc.TranslatorConnection.class);
                if (tc.getTargetDialect() != null) {
                    targetDialect = tc.getTargetDialect().name();
                }
            } else {
                targetDialect = conn.getMetaData().getDatabaseProductName();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("targetDialect", targetDialect);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
