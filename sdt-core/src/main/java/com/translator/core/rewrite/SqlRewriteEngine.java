package com.translator.core.rewrite;

import com.translator.core.DialectType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.util.SqlShuttle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SQL 改写引擎。
 * 管理可插拔的改写规则链，依次应用于 AST。
 */
public class SqlRewriteEngine {

    private final List<SqlShuttle> visitors = new ArrayList<>();

    /**
     * 创建一个用于 source→target 转换的改写引擎。
     *
     * @param source 源方言
     * @param target 目标方言
     */
    public SqlRewriteEngine(DialectType source, DialectType target) {
        // 注册函数改写规则
        visitors.add(new FunctionRewriteVisitor(source, target));
        // 后续可在此注册更多改写 visitor（标识符、类型转换等）
    }

    /**
     * 添加自定义改写规则。
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
