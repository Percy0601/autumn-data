package xyz.wewin.autumn.data.meta.jdbc.sql;

import java.util.ArrayList;
import java.util.List;

import xyz.wewin.autumn.data.common.meta.MetaProperty;
import xyz.wewin.autumn.data.meta.jdbc.metadata.MetaTable;

/**
 * 再转换阶段：把抽取产物 {@link MarkedSql} 编成可复用的 {@link MetaSqlPlan}。
 *
 * <p>这一步是「动态 SQL」变慢的唯一入口，也是它变快的唯一机会：列查找、类型转换规则、setter 选择
 * 都在这里各槽做一次，编完之后同形状的每次执行只是取值 + 绑值。因此编译必须由
 * {@link MetaPlanCache} 兜住 —— 每个形状只编一次。</p>
 *
 * <p>{@code refs} 里的引用<b>允许不是列</b>：Mapper 文件里的 {@code #{keyword}}、{@code <foreach>}
 * 的循环值都可能是纯业务参数，这类槽原样交给驱动，不报错。引用不存在的列在抽取阶段就被
 * {@link MetaSqlBuilder} 的 {@code requireColumn} 拦下，轮不到编译期。</p>
 */
public final class MetaPlanCompiler {

	private MetaPlanCompiler() {
	}

	/** 编一个计划：槽数与 {@link MarkedSql#refs()} 一一对应，顺序即占位符顺序。 */
	public static MetaSqlPlan compile(MetaTable table, MarkedSql marked) {
		List<String> refs = marked.refs();
		List<MetaBindSlot> slots = new ArrayList<>(refs.size());
		for (String ref : refs) {
			MetaProperty column = table.findColumn(ref);
			slots.add(column == null ? MetaBindSlot.ofValue(ref) : MetaBindSlot.ofColumn(column));
		}
		return new MetaSqlPlan(marked.sql(), slots);
	}
}
