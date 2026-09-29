package com.codejudge.common.domain;

import com.codejudge.common.constants.Constant;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应包络（全平台唯一响应结构，BUG-003 包络码统一的落点）。
 *
 * <h3>包络码决策（BUG-003）</h3>
 * 历史上"错误码"有两套事实：HTTP 状态码与包络 {@code code} 各说各话
 * （同一越权有时 HTTP 403、有时 HTTP 200 + code 403），调用方只能猜。
 * 统一后的契约按「请求死在哪一层」划界：
 *
 * <ol>
 *   <li><b>业务层（各服务，本类）</b>：业务失败（含鉴权之外的 401 语义、403 越权、
 *       404 不存在、423 禁用、400 参数、429 限流、500/503 故障）一律
 *       <b>HTTP 200 + {@code body.code} 携带语义码</b>。业务层不造 HTTP 错误 ——
 *       {@code body.code} 是唯一事实来源，前端/脚本/压测断言只看它。</li>
 *   <li><b>传输/鉴权层（网关自身）</b>：请求未达业务层（未登录、token 无效/吊销、
 *       无路由、限流），网关写<b>真实 HTTP 状态码 + 同值包络 code</b> ——
 *       此时没有业务语义可给，浏览器/监控/重试策略依赖标准 HTTP 语义。</li>
 * </ol>
 *
 * <p>客户端约定：成功判定恒为 {@code body.code == 200}（不是 HTTP 200，也不是
 * 旧式 {@code code == 1}）；HTTP 状态码仅作传输层语义。两层的包络结构完全一致
 * （code/msg/requestId），调用方无需区分来源即可按同一套逻辑解析。
 *
 * @param <T> 业务数据类型
 * @see com.codejudge.common.advice.CommonExceptionAdvice 业务异常 → 包络码的统一映射
 */
@Data
public class R<T> implements Serializable {

    private int code;
    private String msg;
    private T data;
    private String requestId;

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMsg("OK");
        r.setData(data);
        r.setRequestId(Constant.getRequestId());
        return r;
    }

    public static <T> R<T> error(String msg) {
        return error(0, msg);
    }

    public static <T> R<T> error(int code, String msg) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setRequestId(Constant.getRequestId());
        return r;
    }

    public boolean success() {
        return code == 200;
    }
}
