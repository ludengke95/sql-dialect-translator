package com.translator.core.rewrite;

import java.util.List;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.util.SqlShuttle;

/**
 * 基于规则的改写 Visitor（包级可见）。
 * <p>
 * 持有筛选后的 {@link SqlRewriteRule} 列表，
 * 在 AST 遍历中自底向上地依次尝试每条匹配的规则。
 * </p>
 */
class RuleBasedRewriteVisitor extends SqlShuttle {

    /** 筛选后的改写规则列表 */
    private final List<SqlRewriteRule> rules;

    /**
     * 创建规则驱动改写 Visitor。
     *
     * @param rules 经方言筛选后的规则列表
     */
    RuleBasedRewriteVisitor(List<SqlRewriteRule> rules) {
        this.rules = rules;
    }

    @Override
    public SqlNode visit(SqlCall call) {
        // 先深度遍历子节点（自底向上改写）
        SqlCall visited = (SqlCall) super.visit(call);

        // 依次尝试每条规则（链式：后一条规则的输入是前一条的输出）
        SqlNode current = visited;
        for (SqlRewriteRule rule : rules) {
            if (rule.matches(current)) {
                current = rule.apply(current);
            }
        }
        return current;
    }
}
