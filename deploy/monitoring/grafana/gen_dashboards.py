#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CodeJudge Grafana 仪表盘生成器。

为什么要用生成器而不是手写 JSON：
  4 个看板共 30+ 个 panel，手写 JSON 要重复几十遍 datasource/gridPos/fieldConfig，
  改一个阈值要翻几百行，且极易写出「少个逗号 → Grafana 静默不加载」这类问题
  （表现为面板一个都不出现、无任何报错，非常难查）。用代码生成保证结构一致。

用法：
    python deploy/monitoring/grafana/gen_dashboards.py
    产物写到同目录 dashboards/*.json

改完阈值/查询后重新执行本脚本即可；Grafana 侧由 provisioning 每 30s 自动重载。
"""
import json
import pathlib

DS = {"type": "prometheus", "uid": "codejudge-prom"}
OUT = pathlib.Path(__file__).parent / "dashboards"

_id = [0]


def nid():
    _id[0] += 1
    return _id[0]


def stat(title, expr, x, y, w=4, h=4, unit="none", legend=None,
         thresholds=None, decimals=None, color_mode="value", graph="none"):
    """单值统计面板。thresholds 形如 [(None,'green'), (0.05,'red')]"""
    steps = [{"color": "green", "value": None}]
    if thresholds:
        steps = [{"color": c, "value": v} for v, c in thresholds]
    defaults = {
        "unit": unit,
        "color": {"mode": "thresholds" if thresholds else "palette-classic"},
        "thresholds": {"mode": "absolute", "steps": steps},
    }
    if decimals is not None:
        defaults["decimals"] = decimals
    return {
        "id": nid(), "type": "stat", "title": title, "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [{"refId": "A", "expr": expr, "datasource": DS,
                     "legendFormat": legend or "", "instant": True, "range": False}],
        "options": {
            "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
            "colorMode": color_mode, "graphMode": graph, "textMode": "auto",
            "justifyMode": "auto", "orientation": "auto",
        },
        "fieldConfig": {"defaults": defaults, "overrides": []},
    }


def ts(title, targets, x, y, w=8, h=8, unit="none", legend_mode="table",
       stack=False, fill=10, decimals=None, min_=None, max_=None):
    """时间序列面板。targets 形如 [("A", expr, "{{application}}"), ...]"""
    defaults = {
        "unit": unit,
        "custom": {
            "drawStyle": "line", "lineInterpolation": "smooth", "lineWidth": 2,
            "fillOpacity": fill if stack else fill, "showPoints": "never",
            "spanNulls": False, "stacking": {"mode": "normal" if stack else "none", "group": "A"},
            "axisPlacement": "auto", "scaleDistribution": {"type": "linear"},
        },
    }
    if decimals is not None:
        defaults["decimals"] = decimals
    if min_ is not None:
        defaults["min"] = min_
    if max_ is not None:
        defaults["max"] = max_
    return {
        "id": nid(), "type": "timeseries", "title": title, "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [{"refId": r, "expr": e, "datasource": DS, "legendFormat": lf}
                    for r, e, lf in targets],
        "options": {
            "legend": {"displayMode": legend_mode, "placement": "bottom",
                       "showLegend": True, "calcs": ["mean", "max", "lastNotNull"]},
            "tooltip": {"mode": "multi", "sort": "desc"},
        },
        "fieldConfig": {"defaults": defaults, "overrides": []},
    }


def table(title, targets, x, y, w=12, h=8, renames=None, unit="none"):
    """
    表格面板。
    必须显式放行 `Value` 列：Grafana 默认会把 Table 格式下的 `Value #A`
    重命名为 `Value`，若在 organize 里排除它，整张表就只剩 label 列 —— 看起来「表是空的」。
    """
    renames = renames or {}
    rename_map = {"Time": "", **renames}
    return {
        "id": nid(), "type": "table", "title": title, "datasource": DS,
        "gridPos": {"h": h, "w": w, "x": x, "y": y},
        "targets": [{"refId": r, "expr": e, "datasource": DS, "format": "table",
                     "instant": True, "range": False} for r, e in targets],
        "transformations": [
            {"id": "organize", "options": {
                "excludeByName": {"Time": True},
                "indexByName": {},
                "renameByName": rename_map,
            }},
        ],
        "options": {"showHeader": True, "sortBy": [{"displayName": "Value", "desc": True}]},
        "fieldConfig": {"defaults": {"unit": unit, "custom": {"align": "auto"}}, "overrides": []},
    }


def row(title, y, collapsed=False):
    return {
        "id": nid(), "type": "row", "title": title,
        "gridPos": {"h": 1, "w": 24, "x": 0, "y": y},
        "collapsed": collapsed, "panels": [],
    }


def dashboard(uid, title, description, tags, panels, templating=None):
    return {
        "uid": uid, "title": title, "description": description,
        "tags": tags + ["codejudge"],
        "timezone": "browser", "editable": False, "schemaVersion": 39,
        "version": 1, "refresh": "30s",
        "time": {"from": "now-30m", "to": "now"},
        "templating": {"list": templating or []},
        "annotations": {"list": [{"builtIn": 1, "datasource": {"type": "grafana", "uid": "-- Grafana --"},
                                  "enable": True, "hide": True, "name": "Annotations & Alerts",
                                  "type": "dashboard"}]},
        "panels": panels,
    }


# =====================================================================
# 公共变量：所有看板共用一个 application 多选变量
# =====================================================================
def app_var(all_value="$__all"):
    return {
        "name": "application",
        "label": "服务",
        "type": "query",
        "datasource": DS,
        "query": {"query": 'label_values(up{job="codejudge"}, application)', "refId": "A"},
        "refresh": 2,               # 2 = On Time Range Change
        "includeAll": True,
        "multi": True,
        "allValue": all_value,
        "current": {"selected": True, "text": ["All"], "value": [all_value]},
        "sort": 1,
    }


# =====================================================================
# 看板 1：服务总览 —— 一屏回答「现在整体活着吗 / 慢不慢 / 判题通不通」
# =====================================================================
def overview():
    p = []
    p.append(row("一屏概览", 0))
    p.append(stat("在线服务数", 'sum(up{job="codejudge", application=~"$application"})',
                  0, 1, 3, 4, unit="none",
                  thresholds=[(8, "green"), (6, "yellow"), (0.1, "red")]))
    p.append(stat("总请求 QPS",
                  'sum(rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[1m]))',
                  3, 1, 3, 4, unit="reqps", decimals=2, color_mode="none"))
    p.append(stat("总 5xx 错误率",
                  '(sum(rate(http_server_requests_seconds_count{job="codejudge", application=~"$application", status=~"5.."}[5m])) '
                  '/ clamp_min(sum(rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[5m])), 0.001))',
                  6, 1, 3, 4, unit="percentunit", decimals=3,
                  thresholds=[(None, "green"), (0.01, "yellow"), (0.05, "red")]))
    p.append(stat("待判队列积压", 'max(judge_queue_backlog{job="codejudge"})',
                  9, 1, 3, 4, decimals=0,
                  thresholds=[(None, "green"), (50, "yellow"), (200, "red")]))
    p.append(stat("死信任务数", 'max(judge_dead_tasks{job="codejudge"})',
                  12, 1, 3, 4, decimals=0,
                  thresholds=[(None, "green"), (0.1, "red")]))
    p.append(stat("在线判题机", 'max(judge_workers_online{job="codejudge"})',
                  15, 1, 3, 4, decimals=0,
                  thresholds=[(None, "red"), (1, "green")]))
    p.append(stat("Prometheus 抓取耗时",
                  'max(scrape_duration_seconds{job="codejudge"})',
                  18, 1, 3, 4, unit="s", decimals=3, color_mode="none"))
    p.append(stat("Grafana 数据源头",
                  'count(up{job="codejudge"} == 1)',
                  21, 1, 3, 4, decimals=0, color_mode="none"))

    p.append(row("服务状态与流量", 5))
    p.append(table("服务状态明细",
                   [("A", 'up{job="codejudge", application=~"$application"}')],
                   0, 6, 8, 8, renames={"application": "服务", "instance": "实例",
                                        "job": "作业", "Value": "存活(1=是)"}))
    p.append(ts("各服务 QPS",
                [("A", 'sum by (application) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[1m]))',
                  "{{application}}")],
                8, 6, 8, 8, unit="reqps", decimals=2))
    p.append(ts("各服务 P95 延迟",
                [("A", 'histogram_quantile(0.95, sum by (application, le) (rate(http_server_requests_seconds_bucket{job="codejudge", application=~"$application"}[5m])))',
                  "{{application}}")],
                16, 6, 8, 8, unit="s", decimals=3))

    p.append(row("错误与资源", 14))
    p.append(ts("各服务 5xx 错误率",
                [("A", 'sum by (application) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application", status=~"5.."}[5m])) '
                       '/ clamp_min(sum by (application) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[5m])), 0.001)',
                  "{{application}}")],
                0, 15, 8, 8, unit="percentunit", decimals=4))
    p.append(ts("JVM 堆占用",
                [("A", 'sum by (application) (jvm_memory_used_bytes{job="codejudge", application=~"$application", area="heap"}) '
                       '/ sum by (application) (jvm_memory_max_bytes{job="codejudge", application=~"$application", area="heap"})',
                  "{{application}}")],
                8, 15, 8, 8, unit="percentunit", decimals=3, min_=0, max_=1))
    p.append(ts("进程 CPU 使用率",
                [("A", 'process_cpu_usage{job="codejudge", application=~"$application"}', "{{application}}")],
                16, 15, 8, 8, unit="percentunit", decimals=3, min_=0))
    return dashboard(
        "codejudge-overview", "CodeJudge 服务总览",
        "8 个 judge-* 服务的存活、流量、错误率、延迟与判题链路健康状况。排障第一入口。",
        ["overview"], p, templating=[app_var()])


# =====================================================================
# 看板 2：HTTP 性能 —— 定位「哪个接口慢 / 哪个接口在报错」
# =====================================================================
def http_perf():
    p = []
    p.append(row("端到端延迟", 0))
    p.append(ts("P50 / P95 / P99 延迟（全服务聚合）",
                [("A", 'histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application=~"$application"}[5m])))', "P50"),
                 ("B", 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application=~"$application"}[5m])))', "P95"),
                 ("C", 'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application=~"$application"}[5m])))', "P99")],
                0, 1, 12, 9, unit="s", decimals=3))
    p.append(ts("平均延迟 by 服务",
                [("A", 'sum by (application) (rate(http_server_requests_seconds_sum{job="codejudge", application=~"$application"}[5m])) '
                       '/ clamp_min(sum by (application) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[5m])), 0.001)',
                  "{{application}}")],
                12, 1, 12, 9, unit="s", decimals=3))

    p.append(row("请求量与状态码", 10))
    p.append(ts("QPS by 服务",
                [("A", 'sum by (application) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[1m]))',
                  "{{application}}")],
                0, 11, 12, 8, unit="reqps", decimals=2))
    p.append(ts("状态码分布（堆叠）",
                [("A", 'sum by (status) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[5m]))',
                  "{{status}}")],
                12, 11, 12, 8, unit="reqps", decimals=3, stack=True))

    p.append(row("接口维度下钻", 20))
    p.append(table("Top 15 接口（按请求速率）",
                   [("A", 'topk(15, sum by (application, uri, method) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application"}[5m])))')],
                   0, 21, 12, 10,
                   renames={"application": "服务", "uri": "接口", "method": "方法", "Value": "QPS"},
                   unit="reqps"))
    p.append(table("Top 15 接口（按 P95 延迟）",
                   [("A", 'topk(15, histogram_quantile(0.95, sum by (application, uri, le) '
                         '(rate(http_server_requests_seconds_bucket{job="codejudge", application=~"$application"}[5m]))))')],
                   12, 21, 12, 10,
                   renames={"application": "服务", "uri": "接口", "Value": "P95(s)"},
                   unit="s"))

    p.append(row("异常明细", 31))
    p.append(ts("5xx 速率 by 接口",
                [("A", 'sum by (application, uri) (rate(http_server_requests_seconds_count{job="codejudge", application=~"$application", status=~"5.."}[5m]))',
                  "{{application}} {{uri}}")],
                0, 32, 24, 9, unit="reqps", decimals=4))
    return dashboard(
        "codejudge-http", "CodeJudge HTTP 性能",
        "按服务 / 接口维度下钻延迟、吞吐与错误。定位「哪个接口慢、哪个接口在报错」。",
        ["http"], p, templating=[app_var()])


# =====================================================================
# 看板 3：判题链路 —— HTTP 全绿但判题卡死，只有这里能看到
# =====================================================================
def judge_pipeline():
    p = []
    p.append(row("判题链路体检", 0))
    p.append(stat("待判队列积压", 'max(judge_queue_backlog{job="codejudge"})',
                  0, 1, 4, 5, decimals=0, graph="area",
                  thresholds=[(None, "green"), (50, "yellow"), (200, "red")]))
    p.append(stat("在线判题机", 'max(judge_workers_online{job="codejudge"})',
                  4, 1, 4, 5, decimals=0, graph="area",
                  thresholds=[(None, "red"), (1, "green")]))
    p.append(stat("死信任务", 'max(judge_dead_tasks{job="codejudge"})',
                  8, 1, 4, 5, decimals=0,
                  thresholds=[(None, "green"), (0.1, "red")]))
    p.append(stat("提交接口 QPS",
                  'sum(rate(http_server_requests_seconds_count{job="codejudge", application="judge-submission", uri=~"/submissions.*"}[1m]))',
                  12, 1, 4, 5, unit="reqps", decimals=3, color_mode="none"))
    p.append(stat("提交接口 P95",
                  'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application="judge-submission", uri=~"/submissions.*"}[5m])))',
                  16, 1, 4, 5, unit="s", decimals=3,
                  thresholds=[(None, "green"), (0.5, "yellow"), (1, "red")]))
    p.append(stat("worker 消费速率",
                  'sum(rate(http_server_requests_seconds_count{job="codejudge", application="judge-worker"}[1m]))',
                  20, 1, 4, 5, unit="reqps", decimals=3, color_mode="none"))

    p.append(row("积压与容量趋势", 6))
    p.append(ts("待判队列积压趋势",
                [("A", 'judge_queue_backlog{job="codejudge"}', "队列积压")],
                0, 7, 12, 9, unit="none", decimals=0, fill=25))
    p.append(ts("在线判题机数量",
                [("A", 'judge_workers_online{job="codejudge"}', "在线 worker")],
                12, 7, 12, 9, unit="none", decimals=0, fill=25))

    p.append(row("提交 → 判题 → 回传", 16))
    p.append(ts("提交接口吞吐与延迟",
                [("A", 'sum(rate(http_server_requests_seconds_count{job="codejudge", application="judge-submission", uri=~"/submissions.*"}[1m]))', "提交 QPS"),
                 ("B", 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application="judge-submission", uri=~"/submissions.*"}[5m])))', "提交 P95(s)")],
                0, 17, 12, 9, unit="short"))
    p.append(ts("判题进度查询与 WS 相关接口",
                [("A", 'sum by (uri) (rate(http_server_requests_seconds_count{job="codejudge", application="judge-submission"}[1m]))', "{{uri}}")],
                12, 17, 12, 9, unit="reqps", decimals=4))

    p.append(row("judge-worker 资源与连接池", 26))
    p.append(ts("judge-worker 堆占用",
                [("A", 'sum(jvm_memory_used_bytes{job="codejudge", application="judge-worker", area="heap"}) '
                       '/ sum(jvm_memory_max_bytes{job="codejudge", application="judge-worker", area="heap"})', "堆占用比")],
                0, 27, 12, 8, unit="percentunit", decimals=3, min_=0, max_=1))
    p.append(ts("数据库连接池（active / pending）",
                [("A", 'sum by (application) (hikaricp_connections_active{job="codejudge", application=~"judge-(submission|worker)"})', "{{application}} active"),
                 ("B", 'sum by (application) (hikaricp_connections_pending{job="codejudge", application=~"judge-(submission|worker)"})', "{{application}} pending")],
                12, 27, 12, 8, unit="none", decimals=2))
    return dashboard(
        "codejudge-judge-pipeline", "CodeJudge 判题链路",
        "队列积压 / 在线判题机 / 死信 / 提交吞吐。这是「HTTP 全绿但判题卡死」唯一能暴露出来的地方。",
        ["judge"], p, templating=[])


# =====================================================================
# 看板 4：AI 点评 —— SSE 长连接需要单独的观察口径
# =====================================================================
def ai_review():
    p = []
    p.append(row("AI 点评体检", 0))
    p.append(stat("judge-ai QPS",
                  'sum(rate(http_server_requests_seconds_count{job="codejudge", application="judge-ai"}[1m]))',
                  0, 1, 4, 5, unit="reqps", decimals=3, color_mode="none"))
    p.append(stat("SSE 流失败率",
                  '(sum(rate(http_server_requests_seconds_count{job="codejudge", application="judge-ai", uri=~"/ai/review.*", status=~"5.."}[5m])) '
                  '/ clamp_min(sum(rate(http_server_requests_seconds_count{job="codejudge", application="judge-ai", uri=~"/ai/review.*"}[5m])), 0.001))',
                  4, 1, 4, 5, unit="percentunit", decimals=4,
                  thresholds=[(None, "green"), (0.02, "yellow"), (0.1, "red")]))
    p.append(stat("点评请求数（15 分钟）",
                  'sum(increase(http_server_requests_seconds_count{job="codejudge", application="judge-ai", uri=~"/ai/review.*"}[15m]))',
                  8, 1, 4, 5, decimals=0, color_mode="none"))
    p.append(stat("在线判题机（判题链路对照）", 'max(judge_workers_online{job="codejudge"})',
                  12, 1, 4, 5, decimals=0, color_mode="none"))
    p.append(stat("judge-ai 堆占用",
                  'sum(jvm_memory_used_bytes{job="codejudge", application="judge-ai", area="heap"}) '
                  '/ sum(jvm_memory_max_bytes{job="codejudge", application="judge-ai", area="heap"})',
                  16, 1, 4, 5, unit="percentunit", decimals=3,
                  thresholds=[(None, "green"), (0.7, "yellow"), (0.85, "red")]))
    p.append(stat("judge-ai CPU",
                  'process_cpu_usage{job="codejudge", application="judge-ai"}',
                  20, 1, 4, 5, unit="percentunit", decimals=3, color_mode="none"))

    p.append(row("SSE 长连接特征", 6))
    p.append(ts("点评接口 P50 / P95 / P99",
                [("A", 'histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application="judge-ai", uri=~"/ai/review.*"}[5m])))', "P50"),
                 ("B", 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application="judge-ai", uri=~"/ai/review.*"}[5m])))', "P95"),
                 ("C", 'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{job="codejudge", application="judge-ai", uri=~"/ai/review.*"}[5m])))', "P99")],
                0, 7, 12, 9, unit="s", decimals=3))
    p.append(ts("judge-ai 各接口 QPS",
                [("A", 'sum by (uri) (rate(http_server_requests_seconds_count{job="codejudge", application="judge-ai"}[1m]))', "{{uri}}")],
                12, 7, 12, 9, unit="reqps", decimals=4))

    p.append(row("事件流质量与资源", 16))
    p.append(ts("SSE 请求数（按 uri，含成功/失败）",
                [("A", 'sum by (uri, status) (rate(http_server_requests_seconds_count{job="codejudge", application="judge-ai", uri=~"/ai/review.*"}[5m]))',
                  "{{uri}} {{status}}")],
                0, 17, 12, 9, unit="reqps", decimals=4))
    p.append(ts("RAG / 内部接口调用（review-context）",
                [("A", 'sum by (uri) (rate(http_server_requests_seconds_count{job="codejudge", application="judge-ai"}[5m]))', "{{uri}}")],
                12, 17, 12, 9, unit="reqps", decimals=4))

    p.append(row("JVM 明细", 26))
    p.append(ts("GC 暂停时间（每秒）",
                [("A", 'sum by (application) (rate(jvm_gc_pause_seconds_sum{job="codejudge", application=~"judge-(ai|submission)"}[5m]))',
                  "{{application}}")],
                0, 27, 12, 8, unit="s", decimals=5))
    p.append(ts("非堆 / 直接内存使用",
                [("A", 'sum by (area) (jvm_memory_used_bytes{job="codejudge", application="judge-ai"})', "{{area}}")],
                12, 27, 12, 8, unit="bytes"))
    return dashboard(
        "codejudge-ai-review", "CodeJudge AI 点评",
        "judge-ai（WebFlux + SSE + RAG）的请求量、流式耗时、失败率与内存。SSE 是长连接，延迟口径与普通接口不同，必须单看。",
        ["ai"], p, templating=[])


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for fn, d in [
        ("codejudge-overview.json", overview()),
        ("codejudge-http.json", http_perf()),
        ("codejudge-judge-pipeline.json", judge_pipeline()),
        ("codejudge-ai-review.json", ai_review()),
    ]:
        path = OUT / fn
        path.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        n_panels = len([x for x in d["panels"] if x["type"] != "row"])
        print("%-42s panels=%d  bytes=%d" % (fn, n_panels, path.stat().st_size))


if __name__ == "__main__":
    main()
