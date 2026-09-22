/**
 * Prometheus 文本格式解析（/actuator/prometheus 的响应）
 *
 * 只解析前端监控页需要的部分，**不引入 prom-client 之类的依赖** ——
 * 服务端指标的权威采集与存储由 Prometheus 负责（见 deploy/monitoring），
 * 这里只是让管理页能在浏览器里直接看一眼"现在什么情况"，不必为了看数字去开 Grafana。
 *
 * 文本格式要点：
 *   · `# HELP <name> ...` / `# TYPE <name> ...` 是元数据行，跳过；
 *   · 样本行形如 `name{label="v",label2="v2"} 123.45 [timestamp]`；
 *   · label 值里的 `"`、`\`、`\n` 会被转义，反向解析时必须还原，
 *     否则带引号的 URI（如 /problems/{id}）会解析错位。
 */

const LABEL_RE = /([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\.)*)"/g;

function unescapeLabelValue(v) {
  return v.replace(/\\(.)/g, (_, c) => (c === 'n' ? '\n' : c));
}

/** 解析 label 串（大括号内部的裸文本） */
function parseLabels(raw) {
  const labels = {};
  if (!raw) return labels;
  LABEL_RE.lastIndex = 0;
  let m;
  while ((m = LABEL_RE.exec(raw)) !== null) {
    labels[m[1]] = unescapeLabelValue(m[2]);
  }
  return labels;
}

/**
 * @param {string} text /actuator/prometheus 响应体
 * @returns {Record<string, Array<{labels: object, value: number}>>}
 */
export function parseMetrics(text) {
  const out = {};
  if (!text) return out;

  for (const line of text.split('\n')) {
    if (!line || line.startsWith('#')) continue;
    const trimmed = line.trim();
    if (!trimmed) continue;

    let name;
    let labelRaw = '';
    let rest;

    const braceIdx = trimmed.indexOf('{');
    if (braceIdx !== -1) {
      name = trimmed.slice(0, braceIdx);
      const closeIdx = trimmed.lastIndexOf('}');
      if (closeIdx <= braceIdx) continue; // 畸形行
      labelRaw = trimmed.slice(braceIdx + 1, closeIdx);
      rest = trimmed.slice(closeIdx + 1).trim();
    } else {
      const sp = trimmed.indexOf(' ');
      if (sp === -1) continue;
      name = trimmed.slice(0, sp);
      rest = trimmed.slice(sp + 1).trim();
    }

    if (!/^[a-zA-Z_:][a-zA-Z0-9_:]*$/.test(name)) continue;

    // 采样值后可能跟时间戳（微秒），取第一个 token 即可
    const valueToken = rest.split(/\s+/)[0];
    const value = Number(valueToken);
    if (!Number.isFinite(value)) continue;

    (out[name] ||= []).push({ labels: parseLabels(labelRaw), value });
  }

  return out;
}

/** 对某指标的所有序列求和（可选 filter 过滤标签） */
export function sumMetric(metrics, name, filter) {
  const series = metrics[name];
  if (!series?.length) return 0;
  let total = 0;
  for (const s of series) {
    if (filter && !filter(s.labels)) continue;
    total += s.value;
  }
  return total;
}

/** 找到某指标的第一条序列值 */
export function firstValue(metrics, name, filter) {
  const series = metrics[name];
  if (!series?.length) return null;
  for (const s of series) {
    if (filter && !filter(s.labels)) continue;
    return s.value;
  }
  return null;
}

/** 按某个标签聚合求和 → Map<labelValue, sum> */
export function groupSum(metrics, name, labelKey, filter) {
  const acc = new Map();
  for (const s of metrics[name] || []) {
    if (filter && !filter(s.labels)) continue;
    const k = s.labels[labelKey] ?? '';
    acc.set(k, (acc.get(k) || 0) + s.value);
  }
  return acc;
}

/**
 * 从 http_server_requests_seconds_* 推导 HTTP 概览。
 *
 * ⚠️ `http_server_requests_seconds_count` 是**进程启动以来的累计值**，不是速率。
 * 因此这里的"错误率"是"累计 5xx 占比"，用于快速判断"服务有没有在报错"，
 * 不能当成实时 QPS/错误率 —— 真正的速率要靠 Prometheus 的 `rate()`（见 Grafana 面板）。
 */
export function httpOverview(metrics) {
  const filter = (l) => l.uri !== '/actuator/prometheus' && !String(l.uri || '').startsWith('/actuator');

  const total = sumMetric(metrics, 'http_server_requests_seconds_count', filter);
  const sum = sumMetric(metrics, 'http_server_requests_seconds_sum', filter);

  let serverErrors = 0;
  let clientErrors = 0;
  for (const s of metrics['http_server_requests_seconds_count'] || []) {
    if (!filter(s.labels)) continue;
    const status = String(s.labels.status || '');
    if (status.startsWith('5')) serverErrors += s.value;
    else if (status.startsWith('4')) clientErrors += s.value;
  }

  const avgMs = total > 0 ? (sum / total) * 1000 : null;

  return {
    total,
    serverErrors,
    clientErrors,
    serverErrorRate: total > 0 ? (serverErrors / total) * 100 : null,
    clientErrorRate: total > 0 ? (clientErrors / total) * 100 : null,
    avgMs,
  };
}

/** URI 维度的请求量 Top-N（累计计数） */
export function topUris(metrics, n = 8) {
  const grouped = groupSum(metrics, 'http_server_requests_seconds_count', 'uri', (l) =>
    !String(l.uri || '').startsWith('/actuator'),
  );
  return [...grouped.entries()]
    .map(([uri, count]) => ({ uri, count }))
    .sort((a, b) => b.count - a.count)
    .slice(0, n);
}

/** JVM 堆内存（bytes） */
export function heapUsedBytes(metrics) {
  // Spring Boot 3 的指标名：jvm_memory_used_bytes{area="heap",id="G1 Eden Space",...}
  return sumMetric(metrics, 'jvm_memory_used_bytes', (l) => l.area === 'heap');
}

/** 进程 CPU 使用率（0~1） */
export function processCpu(metrics) {
  return firstValue(metrics, 'process_cpu_usage');
}

/** 数据库连接池活跃连接数 */
export function hikariActive(metrics) {
  const series = metrics['hikaricp_connections_active'];
  if (!series?.length) return null;
  return series.reduce((m, s) => Math.max(m, s.value), 0);
}

export function fmtBytes(bytes) {
  if (bytes === null || bytes === undefined) return '—';
  const units = ['B', 'KB', 'MB', 'GB'];
  let v = bytes;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`;
}
