package com.translator.core.rewrite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.util.SqlShuttle;

import com.translator.core.DialectType;

/**
 * SQL 改写引擎。
 * <p>
 * 通过 Java SPI ({@link java.util.ServiceLoader}) 自动发现所有
 * {@link SqlRewriteRule} 实现，按 (source, target) 方言对筛选后，
 * 通过 {@link RuleBasedRewriteVisitor} 应用于 AST。
 * </p>
 * <p>
 * 也支持通过 {@link #addVisitor(SqlShuttle)} 注入自定义改写逻辑。
 * </p>
 */
public class SqlRewriteEngine {

    private final List<SqlShuttle> visitors = new ArrayList<>();

    /**
     * 创建一个用于 source→target 转换的改写引擎。
     * 通过 SPI 自动加载所有 SqlRewriteRule 实现并按方言筛选。
     *
     * @param source 源方言
     * @param target 目标方言
     */
    public SqlRewriteEngine(DialectType source, DialectType target) {
        if (source == target) {
            return; // 同方言无需转换
        }
        // 通过 SPI 加载规则，按方言对筛选
        List<SqlRewriteRule> matchedRules = RuleLoader.loadRules(source, target);
        if (!matchedRules.isEmpty()) {
            visitors.add(new RuleBasedRewriteVisitor(matchedRules));
        }
        // 后续可在此注册更多改写 visitor（标识符、类型转换等）
    }

    /**
     * 添加自定义改写 Visitor。
     *
     * @param visitor SqlNode visitor
     */
    public void addVisitor(SqlShuttle visitor) {
        visitors.add(visitor);
    }

    /**
     * 对 AST 应用所有改写规则。
     *
     * @param root 根 SqlNode
     * @return 改写后的 SqlNode
     */
    public SqlNode rewrite(SqlNode root) {
        SqlNode node = root;
        for (SqlShuttle visitor : visitors) {
            node = node.accept(visitor);
        }
        return node;
    }

    /**
     * 获取注册的改写规则列表（只读）。
     */
    public List<SqlShuttle> getVisitors() {
        return Collections.unmodifiableList(visitors);
    }
}
