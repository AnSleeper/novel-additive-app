/*
 * 移植补丁：向导第 2 步（章节拆分规则）
 *
 * 原桌面端逻辑里，规则对象只在表单发生变化时才写回 formData；
 * 而"下一步"按钮走的是父组件的纯跳步逻辑，不会调用子组件的 next()。
 * 结果：用户不动任何开关直接下一步，第 3 步会报「未配置 TXT 拆分规则」。
 *
 * 这里在每次进入该步骤时自动触发一次开关切换（取消→恢复），
 * 值仍然是用户看到的那一份，只是让 watch 有机会把规则写回 formData。
 */
(function () {
  'use strict';
  var armed = true;
  setInterval(function () {
    if (!document.body) return;
    var txt = document.body.innerText || '';
    var inStep2 = txt.indexOf('配置章节拆分规则') >= 0;
    if (!inStep2) {
      armed = true;
      return;
    }
    if (!armed) return;
    armed = false;
    var cb = document.querySelector('input[type="checkbox"]');
    if (!cb) return;
    try {
      cb.click();
      setTimeout(function () {
        var again = document.querySelector('input[type="checkbox"]');
        if (again) again.click();
      }, 300);
    } catch (e) {
      console.warn('[patch] step2 rule sync failed', e);
    }
  }, 1200);
})();
