package com.translator.core.rewrite;

import java.util.Set;

import org.apache.calcite.sql.SqlNode;

import com.translator.core.DialectType;

/**
 * SQL 改写规则 SPI 接口。
 * <p>
 * 每条规则自描述适用的方言范围、节点匹配条件和改写逻辑。
 * 实现类需通过 {@code META-INF/services/} 注册，
 * 由 Java {@link java.util.ServiceLoader} 自动发现。
 * </p>
 *
 * <h3>方言匹配规则</h3>
 * <ul>
 *   <li>{@link #getSourceDialects()} 返回空集合 → 匹配所有源方言</li>
 *   <li>{@link #getTargetDialects()} 返回空集合 → 匹配所有目标方言</li>
 *   <li>返回非空集合 → 仅匹配集合中包含的方言</li>
 * </ul>
 *
 * <h3>实现方式</h3>
 * 大多数函数改写场景应继承 {@link FunctionRewriteRule} 便捷基类，无需直接实现本接口。
 * 仅在需要非函数节点的复杂匹配逻辑时才直接实现。
 *
 * @see FunctionRewriteRule
 */
public interface SqlRewriteRule {

    /**
     * 获取适用的源方言集合。
     *
     * @return 适用的源方言集合，空集合表示通配所有方言
     */
    Set<DialectType> getSourceDialects();

    /**
     * 获取适用的目标方言集合。
     *
     * @return 适用的目标方言集合，空集合表示通配所有方言
     */
    Set<DialectType> getTargetDialects();

    /**
     * 判断当前 AST 节点是否匹配此规则。
     * 仅在方言筛选通过后才会调用。
     *
     * @param node 当前遍历到的 SqlNode（已深度遍历子节点）
     * @return true 表示匹配，将调用 {@link #apply(SqlNode)} 进行改写
     */
    boolean matches(SqlNode node);

    /**
     * 执行改写，返回改写后的 SqlNode。
     * 仅在 {@link #matches(SqlNode)} 返回 true 时调用。
     *
     * @param node 匹配到的原始 SqlNode
     * @return 改写后的 SqlNode
     */
    SqlNode apply(SqlNode node);
}
