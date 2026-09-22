package com.codejudge.common.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code Map<String, String>} ↔ JSON 字符串 的 MyBatis 类型处理器。
 *
 * <p>用途：承载"键值对型 JSON 列"，当前用于 {@code problem_version.template_code}
 * （形如 {@code {"java":"...","python":"..."}} 的多语言模板代码）。
 *
 * <p>使用方式：实体类上标注 {@code @TableName(autoResultMap = true)}，
 * 字段上标注 {@code @TableField(typeHandler = JsonMapTypeHandler.class)}。
 *
 * <p>设计要点（与 {@link JsonStringListTypeHandler} 保持一致）：
 * <ul>
 *   <li>读取时容错 —— 空串 / 非法 JSON / 非对象结构均返回空 Map，绝不抛异常阻断查询；
 *       题目详情接口不应因为一列模板代码格式异常就整体 500；</li>
 *   <li>写入时容错 —— 空 Map 写入 null，避免存 "{}" 造成无意义数据；</li>
 *   <li>序列化失败时退化为 null 而非抛异常 —— 模板代码是辅助信息，不能阻断建题主流程。</li>
 * </ul>
 */
@MappedTypes(Map.class)
public class JsonMapTypeHandler extends BaseTypeHandler<Map<String, String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> TYPE = new TypeReference<>() {
    };

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map<String, String> parameter,
                                    JdbcType jdbcType) throws SQLException {
        if (parameter == null || parameter.isEmpty()) {
            ps.setString(i, null);
            return;
        }
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            // 退化为不落库而非整体失败：模板代码缺失不影响题面与用例
            ps.setString(i, null);
        }
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Map<String, String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Map<String, String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Map<String, String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        String text = raw.trim();
        if (!text.startsWith("{")) {
            // 兼容历史脏数据：非对象结构一律按空 Map 处理，不阻断查询
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> map = MAPPER.readValue(text, TYPE);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }
}
