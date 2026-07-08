package com.translator.core.rewrite;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.translator.core.DialectType;

/**
 * 规则加载器（包级可见）。
 * <p>
 * 通过 Java SPI ({@link ServiceLoader}) 加载所有 {@link SqlRewriteRule} 实现，
 * 然后按 (source, target) 方言对筛选适用的规则。
 * 加载结果按方言对缓存，避免重复 SPI 调用和规则对象创建。
 * </p>
 */
final class RuleLoader {

    /** 所有方言规则的全量缓存（仅 SPI 加载一次） */
    private static volatile List<SqlRewriteRule> ALL_RULES_CACHE;

    /** 按方言对缓存的筛选结果：(source, target) → 匹配的规则列表 */
    private static final ConcurrentMap<String, List<SqlRewriteRule>> MATCHED_CACHE = new ConcurrentHashMap<>();

    private RuleLoader() {
        // 工具类，禁止实例化
    }

    /**
     * 获取按方言对筛选后的规则列表（带缓存）。
     *
     * @param source 源方言
     * @param target 目标方言
     * @return 匹配当前方言对的规则列表（不可变）
     */
    static List<SqlRewriteRule> loadRules(DialectType source, DialectType target) {
        String key = source.name() + "→" + target.name();
        return MATCHED_CACHE.computeIfAbsent(key, k -> filterByDialect(getAllRules(), source, target));
    }

    /**
     * 懒加载所有 SPI 规则，双重检查锁保证只加载一次。
     */
    private static List<SqlRewriteRule> getAllRules() {
        if (ALL_RULES_CACHE != null) {
            return ALL_RULES_CACHE;
        }
        synchronized (RuleLoader.class) {
            if (ALL_RULES_CACHE != null) {
                return ALL_RULES_CACHE;
            }
            List<SqlRewriteRule> rules = new ArrayList<>();
            ServiceLoader<SqlRewriteRule> loader = ServiceLoader.load(SqlRewriteRule.class);
            for (SqlRewriteRule rule : loader) {
                rules.add(rule);
            }
            ALL_RULES_CACHE = Collections.unmodifiableList(rules);
            return ALL_RULES_CACHE;
        }
    }

    /**
     * 筛选方言匹配的规则，结果包装为不可变列表。
     */
    private static List<SqlRewriteRule> filterByDialect(
            List<SqlRewriteRule> allRules, DialectType source, DialectType target) {
        List<SqlRewriteRule> matched = new ArrayList<>();
        for (SqlRewriteRule rule : allRules) {
            if (matchesDialect(rule.getSourceDialects(), source) && matchesDialect(rule.getTargetDialects(), target)) {
                matched.add(rule);
            }
        }
        return Collections.unmodifiableList(matched);
    }

    /**
     * 方言集合匹配逻辑：
     * <ul>
     *   <li>dialectSet 为空 → 通配所有方言</li>
     *   <li>dialectSet 非空 → 需包含实际 dialect</li>
     * </ul>
     */
    private static boolean matchesDialect(Set<DialectType> dialectSet, DialectType actual) {
        return dialectSet.isEmpty() || dialectSet.contains(actual);
    }
}
