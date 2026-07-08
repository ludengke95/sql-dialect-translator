package com.translator.core.rewrite;

import java.util.Set;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

/**
 * 函数改写的便捷基类。
 * <p>
 * 适用于按函数名匹配的常见场景，子类只需实现：
 * <ul>
 *   <li>{@link #getFunctionNames()} — 需要匹配的函数名集合</li>
 *   <li>{@link #apply(SqlNode)} — 具体改写逻辑</li>
 *   <li>{@link #getSourceDialects()} / {@link #getTargetDialects()} — 方言适用范围</li>
 * </ul>
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * public class MyRule extends FunctionRewriteRule {
 *     protected Set<String> getFunctionNames() {
 *         return Collections.singleton("MYFUNC");
 *     }
 *     public Set<DialectType> getSourceDialects() { return Collections.emptySet(); }
 *     public Set<DialectType> getTargetDialects() { return Collections.emptySet(); }
 *     public SqlNode apply(SqlNode node) {
 *         SqlCall call = (SqlCall) node;
 *         // ... 改写逻辑 ...
 *     }
 * }
 * }</pre>
 *
 * @see SqlRewriteRule
 */
public abstract class FunctionRewriteRule implements SqlRewriteRule {

    /**
     * 返回需要匹配的函数名集合。
     * 函数名大小写不敏感，base class 自动转为大写比较。
     *
     * @return 函数名集合
     */
    protected abstract Set<String> getFunctionNames();

    /**
     * 默认匹配逻辑：判断 node 是否为 SqlCall 且 operator 名称在函数名集合中。
     *
     * @param node 当前 AST 节点
     * @return true 如果 node 是目标函数调用
     */
    @Override
    public boolean matches(SqlNode node) {
        if (!(node instanceof SqlCall)) {
            return false;
        }
        String name = ((SqlCall) node).getOperator().getName();
        if (name == null || name.isEmpty()) {
            return false;
        }
        return getFunctionNames().contains(name.toUpperCase());
    }

    /**
     * 执行函数改写。
     *
     * @param node 匹配到的函数调用 SqlNode（保证是 SqlCall 实例）
     * @return 改写后的 SqlNode
     */
    @Override
    public abstract SqlNode apply(SqlNode node);
}
