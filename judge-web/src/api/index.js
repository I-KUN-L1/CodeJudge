import { http } from '@/api/http';

/**
 * 全量接口封装（与后端 Controller 一一对应，路径均为**经网关**后的路径）。
 *
 * 约定：http.js 的响应拦截器已经把 R<T> 拆成 data 直接返回，
 * 因此这里不再出现 `.then(r => r.data)` 之类的二次解包。
 */

/* ============================ 认证（judge-auth :9081） ============================ */
export const authApi = {
  login: (data) => http.post('/accounts/login', data),
  adminLogin: (data) => http.post('/accounts/admin/login', data),
  logout: () => http.post('/accounts/logout'),
  refresh: () => http.post('/accounts/refresh'),
  firstChangePassword: (data) => http.post('/accounts/password/first-change', data),
  myMenus: () => http.get('/menus/me'),
  jwks: () => http.get('/jwks'),
};

/* ============================ 用户（judge-user :9082） ============================ */
export const userApi = {
  me: () => http.get('/users/me'),
  getById: (id) => http.get(`/users/${id}`),
  updateMe: (data) => http.put('/users', data),
  page: (params) => http.get('/users/page', { params }),
  studentsPage: (params) => http.get('/students/page', { params }),
  teachersPage: (params) => http.get('/teachers/page', { params }),
  staffsPage: (params) => http.get('/staffs/page', { params }),
  add: (data) => http.post('/users', data),
  update: (id, data) => http.put(`/users/${id}`, data),
  remove: (id) => http.delete(`/users/${id}`),
  resetPassword: (id) => http.put(`/users/${id}/password/default`),
  updateStatus: (id, status) => http.put(`/users/${id}/status/${status}`),
  teacherRegister: (data) => http.post('/teachers/register', data),

  // 匿名可达（网关白名单）
  registerStudent: (data) => http.post('/students/register', data),
  changePassword: (data) => http.put('/students/password', data),
  checkCellphone: (cellphone) => http.get('/users/checkCellphone', { params: { cellphone } }),
};

/* ============================ 题目（judge-problem :9083） ============================ */
export const problemApi = {
  page: (params) => http.get('/problems/page', { params }),
  detail: (id) => http.get(`/problems/${id}`),
  create: (data) => http.post('/problems', data),
  update: (id, data) => http.put(`/problems/${id}`, data),
  updateStatus: (id, status) => http.put(`/problems/${id}/status/${status}`),
  remove: (id) => http.delete(`/problems/${id}`),
  versions: (id) => http.get(`/problems/${id}/versions`),

  listTestCases: (id) => http.get(`/problems/${id}/test-cases`),
  addTestCases: (id, forms) => http.post(`/problems/${id}/test-cases`, forms),
  replaceTestCases: (id, forms) => http.put(`/problems/${id}/test-cases`, forms),
  updateTestCase: (caseId, form) => http.put(`/test-cases/${caseId}`, form),
  removeTestCase: (caseId) => http.delete(`/test-cases/${caseId}`),

  tagList: (type) => http.get('/tags', { params: type ? { type } : {} }),
  createTag: (data) => http.post('/tags', data),
  removeTag: (id) => http.delete(`/tags/${id}`),
};

/* ======================== 提交 / 判题机（judge-submission :9084） ======================== */
export const submissionApi = {
  submit: (data) => http.post('/submissions', data),
  detail: (id) => http.get(`/submissions/${id}`),
  page: (params) => http.get('/submissions/page', { params }),
  rejudge: (id) => http.post(`/submissions/${id}/rejudge`),

  workers: () => http.get('/workers'),
  workerMetrics: () => http.get('/workers/metrics'),
};

/* ============================ 竞赛（judge-contest :9086） ============================ */
export const contestApi = {
  page: (params) => http.get('/contests/page', { params }),
  detail: (id) => http.get(`/contests/${id}`),
  create: (data) => http.post('/contests', data),
  register: (id) => http.post(`/contests/${id}/register`),
  rank: (id, params) => http.get(`/contests/${id}/rank`, { params }),
  freeze: (id) => http.post(`/contests/${id}/freeze`),
  snapshots: (id) => http.get(`/contests/${id}/snapshots`),
  rebuild: (id, refreeze = false) =>
    http.post(`/contests/${id}/rank/rebuild`, null, { params: { refreeze } }),
};

/* ============================ AI 点评（judge-ai :9087） ============================ */
export const aiApi = {
  // 流式点评见 @/utils/sse.js（fetch + ReadableStream，不用 axios）
  reviewOnce: (data) => http.post('/ai/review', data),
  history: (submissionId, limit = 20) => http.get(`/ai/review/${submissionId}`, { params: { limit } }),
  reviewDetail: (reviewId) => http.get(`/ai/review/detail/${reviewId}`),

  knowledgeCount: () => http.get('/ai/knowledge/count'),
  knowledgeSearch: (data) => http.post('/ai/knowledge/search', data),
  knowledgePreview: (data) => http.post('/ai/knowledge/preview', data),
  knowledgeUpload: (data) => http.post('/ai/knowledge/upload', data),
  knowledgeClear: (problemId) => http.delete('/ai/knowledge', { params: { problemId } }),
};

/* ============================ 可观测性（各服务 actuator，直连） ============================ */
/**
 * actuator 端点**不在网关路由内**（网关只声明了业务前缀），因此必须直连各服务端口。
 * 前端"系统监控"页用它做健康总览；正式的指标采集由 Prometheus 承担（见 deploy/monitoring）。
 */
export const SERVICE_PORTS = {
  'judge-gateway': 9080,
  'judge-auth': 9081,
  'judge-user': 9082,
  'judge-problem': 9083,
  'judge-submission': 9084,
  'judge-worker': 9085,
  'judge-contest': 9086,
  'judge-ai': 9087,
};

function actuatorBase(port) {
  const { protocol, hostname } = window.location;
  return `${protocol}//${hostname || 'localhost'}:${port}`;
}

/**
 * 探测单个服务健康。用原生 fetch 而非 axios 实例：
 * actuator 返回的是**未包装的**原生 JSON，且不应带 Authorization
 * （网关未路由 actuator，服务本身也不校验 JWT）。
 */
export async function probeHealth(name, port, timeoutMs = 4000) {
  const started = Date.now();
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const resp = await fetch(`${actuatorBase(port)}/actuator/health`, { signal: ctrl.signal });
    const body = await resp.json().catch(() => null);
    return {
      name,
      port,
      up: resp.ok && body?.status === 'UP',
      status: body?.status || `HTTP ${resp.status}`,
      detail: body,
      latencyMs: Date.now() - started,
    };
  } catch (e) {
    return {
      name,
      port,
      up: false,
      status: e.name === 'AbortError' ? '超时' : '不可达',
      latencyMs: Date.now() - started,
    };
  } finally {
    clearTimeout(timer);
  }
}

export function probeAllServices() {
  return Promise.all(
    Object.entries(SERVICE_PORTS).map(([name, port]) => probeHealth(name, port)),
  );
}

/** 抓取某服务的 Prometheus 原始指标文本（用于监控页做关键指标速览） */
export async function fetchPrometheus(port) {
  const resp = await fetch(`${actuatorBase(port)}/actuator/prometheus`);
  if (!resp.ok) {
    throw new Error(`/actuator/prometheus 返回 HTTP ${resp.status}`);
  }
  return resp.text();
}
