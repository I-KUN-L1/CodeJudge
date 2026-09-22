/**
 * 单测全局准备（vitest setupFiles）
 *
 * 只放"环境补齐"，不放业务替身 —— 业务替身写在各自 spec 里，
 * 免得读某个用例时不知道它的依赖从哪来。
 */

// jsdom 没有实现 window.scrollTo，vue-router 的 scrollBehavior 会调用它，
// 于是每个路由跳转都往 stderr 打一段 "Not implemented" 栈。
// 它不影响断言，但会把真正的失败信息埋掉，所以直接补一个空实现。
if (typeof window !== 'undefined' && !window.__scrollToStubbed) {
  window.scrollTo = () => {};
  window.__scrollToStubbed = true;
}
