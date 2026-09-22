/**
 * highlight.js 按需注册
 *
 * 为什么不用 `import hljs from 'highlight.js'`：
 * 全量包会把 **190+ 种语言**的语法定义全部打进产物，实测单块 1.07 MB
 * （gzip 373 KB），而判题平台实际会出现的语言不超过十几种。
 * 这里只注册需要的，产物降到 ~100 KB 量级。
 *
 * ⚠️ 用 `hljs.highlightAuto()` 自动识别时，候选集就是这里注册的语言
 * —— 所以新增语言必须在本文件补一行，否则会出现"整块不高亮"却无任何报错的情况。
 */

import hljs from 'highlight.js/lib/core';

// 判题支持的四门语言（与 judge-api 的 Language 枚举一致）
import java from 'highlight.js/lib/languages/java';
import cpp from 'highlight.js/lib/languages/cpp';
import c from 'highlight.js/lib/languages/c';
import python from 'highlight.js/lib/languages/python';
import go from 'highlight.js/lib/languages/go';

// 题面 / 题解 / 编译日志 / 配置里会出现的其它语言
import javascript from 'highlight.js/lib/languages/javascript';
import typescript from 'highlight.js/lib/languages/typescript';
import json from 'highlight.js/lib/languages/json';
import bash from 'highlight.js/lib/languages/bash';
import sql from 'highlight.js/lib/languages/sql';
import markdown from 'highlight.js/lib/languages/markdown';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';
import plaintext from 'highlight.js/lib/languages/plaintext';

const registry = {
  java,
  cpp,
  c,
  python,
  go,
  javascript,
  typescript,
  json,
  bash,
  sql,
  markdown,
  xml,
  yaml,
  plaintext,
};

for (const [name, def] of Object.entries(registry)) {
  hljs.registerLanguage(name, def);
}

/**
 * 把后端的 Language 枚举名映射到 hljs 语言名。
 * 单独抽出来是因为这两个枚举**不重合**（后端叫 CPP，hljs 叫 cpp；
 * 后端叫 PYTHON，hljs 叫 python），散在各组件里必然漂移。
 */
export const BACKEND_LANGUAGE_TO_HLJS = {
  JAVA: 'java',
  CPP: 'cpp',
  PYTHON: 'python',
  GO: 'go',
};

export function hljsNameFor(backendLanguage) {
  const key = String(backendLanguage || '').toUpperCase();
  return BACKEND_LANGUAGE_TO_HLJS[key] || 'plaintext';
}

export default hljs;
