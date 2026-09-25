package com.codejudge.common.domain;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * 分页请求参数
 */
@Data
public class PageQuery implements Serializable {

    public static final Integer DEFAULT_PAGE_SIZE = 10;
    public static final Integer MAX_PAGE_SIZE = 200;

    /**
     * 排序列名白名单：只允许标识符字符。
     * <p>MyBatis-Plus 的 {@code OrderItem.column} 是<b>字符串拼接</b>进 ORDER BY 的（官方明确要求
     * 调用方自行做列名校验），不过滤就是布尔盲注入口 —— {@code sortBy=if(ascii(substr(...))=54,id,username)}
     * 这类表达式可以逐字符拖库。不匹配时静默丢弃、回退调用方给的默认排序列。
     */
    private static final Pattern SAFE_COLUMN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private Integer pageNo = 1;
    private Integer pageSize = DEFAULT_PAGE_SIZE;
    private String sortBy;
    private Boolean isAsc = true;

    public <T> Page<T> toMpPage(OrderItem... orders) {
        // 页码/页宽钳制：MAX_PAGE_SIZE 之前只有常量定义而无使用点，实际靠分页插件兜底；
        // 此处在请求入口再钳一层，负数页码/超大页宽不会透传到 SQL
        int no = (pageNo == null || pageNo < 1) ? 1 : pageNo;
        int size = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE
                : Math.min(pageSize, MAX_PAGE_SIZE);
        Page<T> page = Page.of(no, size);
        if (StringUtils.hasText(sortBy) && SAFE_COLUMN.matcher(sortBy.trim()).matches()) {
            // 关键：OrderItem 必须携带排序列名与方向，空 OrderItem 会导致排序静默失效
            OrderItem item = new OrderItem();
            item.setColumn(sortBy.trim());
            item.setAsc(Boolean.TRUE.equals(isAsc));
            page.addOrder(item);
        }
        if (orders != null) {
            for (OrderItem order : orders) {
                page.addOrder(order);
            }
        }
        return page;
    }

    public <T> Page<T> toMpPage(String defaultSortBy, boolean defaultAsc) {
        if (!StringUtils.hasText(sortBy)) {
            sortBy = defaultSortBy;
            isAsc = defaultAsc;
        }
        return toMpPage();
    }
}
