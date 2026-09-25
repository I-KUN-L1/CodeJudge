/**
 * 展示层格式化：枚举 → 中文、时间、体积等。
 *
 * 后端下发的枚举**一律以字符串形式**（status/verdict/language 等），
 * 映射集中在这里，避免各页面各写一份导致口径漂移。
 */

/* ------------------------------ 判题结论 ------------------------------ */

export const VERDICT = {
  AC: { label: '通过', cls: 'v-ac', full: 'Accepted' },
  WA: { label: '答案错误', cls: 'v-wa', full: 'Wrong Answer' },
  TLE: { label: '超时', cls: 'v-tle', full: 'Time Limit Exceeded' },
  MLE: { label: '超内存', cls: 'v-mle', full: 'Memory Limit Exceeded' },
  RE: { label: '运行错误', cls: 'v-re', full: 'Runtime Error' },
  CE: { label: '编译错误', cls: 'v-ce', full: 'Compile Error' },
  SE: { label: '系统错误', cls: 'v-se', full: 'System Error' },
};

export function verdictLabel(v) {
  return VERDICT[v]?.label || (v ? String(v) : '—');
}

export function verdictCls(v) {
  if (!v) return 'cj-dim';
  return VERDICT[v]?.cls || 'cj-dim';
}

/* ------------------------------ 提交状态 ------------------------------ */

export const SUBMISSION_STATUS = {
  PENDING: { label: '排队中', tag: 'info' },
  JUDGING: { label: '判题中', tag: 'primary' },
  SUCCESS: { label: '已完成', tag: 'success' },
  FAILED: { label: '失败', tag: 'danger' },
};

export function statusLabel(s) {
  return SUBMISSION_STATUS[s]?.label || (s ? String(s) : '—');
}

export function statusTagType(s) {
  return SUBMISSION_STATUS[s]?.tag || 'info';
}

/** 是否终态（终态才停止 WS 订阅与轮询） */
export function isTerminalStatus(s) {
  return s === 'SUCCESS' || s === 'FAILED';
}

/* ------------------------------ 语言 ------------------------------ */

/** 判题支持的四门语言（与后端 judge-api 的 Language 枚举名严格一致） */
export const LANGUAGES = [
  { value: 'JAVA', label: 'Java 21', ext: 'java' },
  { value: 'CPP', label: 'GCC 13 (C++17)', ext: 'cpp' },
  { value: 'PYTHON', label: 'Python 3.12', ext: 'py' },
  { value: 'GO', label: 'Go 1.22', ext: 'go' },
];

export function languageLabel(v) {
  return LANGUAGES.find((l) => l.value === v)?.label || (v ? String(v) : '—');
}

/**
 * 语言名 → highlight.js 语言名的映射见 `utils/highlight.js` 的 `hljsNameFor()`。
 * 那里同时负责**按需注册**语法定义 —— 全量 `import 'highlight.js'` 会把 190+ 种语言
 * 打进产物（实测多出 1.07 MB / gzip 373 KB），而 OJ 只用得上十几种。
 */

/* ------------------------------ 题目 ------------------------------ */

export function difficultyLabel(d) {
  return ['', '入门', '简单', '中等', '困难', '地狱'][d] || '未分级';
}

export function difficultyTagType(d) {
  return ['info', 'success', 'primary', 'warning', 'danger', 'danger'][d] || 'info';
}

export const PROBLEM_STATUS = {
  0: { label: '草稿', tag: 'info' },
  1: { label: '已发布', tag: 'success' },
  2: { label: '已下线', tag: 'warning' },
};

export function problemStatusLabel(s) {
  return PROBLEM_STATUS[s]?.label || '—';
}

export function problemStatusTagType(s) {
  return PROBLEM_STATUS[s]?.tag || 'info';
}

/* ------------------------------ 竞赛 ------------------------------ */

export const CONTEST_STATUS = {
  0: { label: '未开始', tag: 'info' },
  1: { label: '进行中', tag: 'success' },
  2: { label: '已结束', tag: 'warning' },
};

export function contestStatusLabel(s) {
  return CONTEST_STATUS[s]?.label || '—';
}

export function contestStatusTagType(s) {
  return CONTEST_STATUS[s]?.tag || 'info';
}

/* ------------------------------ 用户 ------------------------------ */

/**
 * user.type → 中文名，用于**展示别人的身份**（管理员在用户列表里那一列）。
 *
 * <p>注意这里刻意**只做展示、不参与任何权限判定**：判断"当前登录者能做什么"
 * 一律看后端下发的能力码（`user.can('problem:create')` / `v-perm`）。
 * 曾经这里还有一个 `USER_TYPES` 数组（给登录页的角色选择器用），角色选择器
 * 与前端角色判定一起删掉后它就成了死代码 —— 留着只会让人以为前端还有一份角色表。
 */
export function userTypeLabel(t) {
  return { 1: '管理员', 2: '学员', 3: '教师' }[t] || '未知';
}

export function userStatusLabel(s) {
  // 后端 1=正常 0=禁用（沿用底座约定）
  return s === 1 ? '正常' : '已禁用';
}

/* ------------------------------ 时间 / 数字 ------------------------------ */

/**
 * 后端返回的时间格式不统一：
 *   · VO 上带 @JsonFormat 的是 "yyyy-MM-dd HH:mm:ss" 字符串；
 *   · 未标注的（如 contest 的字段）是 ISO 数组或 ISO 字符串。
 * 这里统一归一为 Date 再格式化，避免出现 "Invalid Date"。
 */
export function toDate(value) {
  if (!value) return null;
  if (value instanceof Date) return value;
  if (Array.isArray(value)) {
    // Jackson 默认的 [2026,9,21,15,30,0] 形态
    const [y, mo = 1, d = 1, h = 0, mi = 0, s = 0] = value;
    return new Date(y, mo - 1, d, h, mi, s);
  }
  if (typeof value === 'string') {
    // "yyyy-MM-dd HH:mm:ss" 在 Safari 下 new Date() 会失败，替换分隔符后再解析
    const normalized = value.includes('T') ? value : value.replace(' ', 'T');
    const d = new Date(normalized);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  return null;
}

export function fmtTime(value, withSeconds = true) {
  const d = toDate(value);
  if (!d) return '—';
  const p = (n) => String(n).padStart(2, '0');
  const base = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  return withSeconds ? `${base}:${p(d.getSeconds())}` : base;
}

export function fmtDate(value) {
  const d = toDate(value);
  if (!d) return '—';
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 相对时间（列表页用，比绝对时间更易读） */
export function fmtFromNow(value) {
  const d = toDate(value);
  if (!d) return '—';
  const diff = Date.now() - d.getTime();
  const abs = Math.abs(diff);
  if (abs < 60_000) return '刚刚';
  const units = [
    [86_400_000, '天'],
    [3_600_000, '小时'],
    [60_000, '分钟'],
  ];
  for (const [ms, unit] of units) {
    if (abs >= ms) {
      const n = Math.floor(abs / ms);
      return diff > 0 ? `${n}${unit}前` : `${n}${unit}后`;
    }
  }
  return '刚刚';
}

export function fmtMemory(kb) {
  if (kb === null || kb === undefined) return '—';
  if (kb < 1024) return `${kb} KB`;
  return `${(kb / 1024).toFixed(1)} MB`;
}

export function fmtDuration(ms) {
  if (ms === null || ms === undefined) return '—';
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`;
}

export function fmtPercent(rate) {
  // 后端在提交数为 0 时下发 null（刻意为之，见 ProblemVO.rate 注释）
  return rate === null || rate === undefined ? '—' : `${rate}%`;
}

/** 罚时秒 → "hh:mm:ss"（榜单展示） */
export function fmtPenalty(seconds) {
  if (seconds === null || seconds === undefined) return '—';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const p = (n) => String(n).padStart(2, '0');
  return h > 0 ? `${h}:${p(m)}:${p(s)}` : `${m}:${p(s)}`;
}

/** 竞赛记法单元格着色："+2" 通过(带罚次) / "-3" 未通过 / "" 未提交 */
export function icpcCellClass(cellText) {
  if (!cellText) return 'cj-dim';
  if (cellText.startsWith('+')) return 'v-ac';
  return 'v-wa';
}

/** 复制到剪贴板（带降级，http 非 localhost 下 navigator.clipboard 不可用） */
export async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* 落到下面的降级路径 */
  }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch {
    return false;
  }
}
