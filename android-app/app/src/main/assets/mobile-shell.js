/*
 * 移动端外壳（0.3.2 重写）
 * - 顶部工具条：工作台 / 提示词 / 设置 / 更新
 * - 管理页单栏：工作台与模型管理只留列表；提示词管理改成列表→编辑两级
 * - 返回：应用内路由栈优先；首页才提示退出
 * - 更新：启动查一次，只有新版才弹窗，应用内下载安装
 */
(function () {
  'use strict';

  var ICONS = {
    home: '<path d="m16 6 4 14"/><path d="M12 6v14"/><path d="M8 8v12"/><path d="M4 4v16"/>',
    prompt: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="8" y1="13" x2="16" y2="13"/><line x1="8" y1="17" x2="14" y2="17"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
    update: '<path d="M21 12a9 9 0 1 1-3-6.7"/><polyline points="21 3 21 9 15 9"/>'
  };

  var navStack = [location.hash || '#/projects'];
  window.__foBack = function () {
    // 编辑页优先返回列表
    var pm = document.querySelector('.prompt-management.fo-editor-open');
    if (pm) {
      pm.classList.remove('fo-editor-open');
      return true;
    }
    if (navStack.length > 1) {
      navStack.pop();
      var target = navStack[navStack.length - 1];
      if ((location.hash || '#/projects') !== target) location.hash = target;
      return true;
    }
    return false;
  };

  function svg(inner) {
    return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round">' + inner + '</svg>';
  }

  function build() {
    if (document.getElementById('fo-fab')) return;
    var box = document.createElement('div');
    box.id = 'fo-fab';
    box.innerHTML =
      '<button data-hash="/projects" title="工作台">' + svg(ICONS.home) + '<span>工作台</span></button>' +
      '<button data-hash="/prompt-management" title="提示词">' + svg(ICONS.prompt) + '<span>提示词</span></button>' +
      '<button data-hash="/model-management" title="设置">' + svg(ICONS.settings) + '<span>设置</span></button>' +
      '<button id="fo-check-update" title="检查更新">' + svg(ICONS.update) + '<span>更新</span></button>';
    var bar = document.querySelector('.title-bar');
    if (bar) bar.appendChild(box); else document.body.appendChild(box);

    box.addEventListener('click', function (e) {
      var b = e.target;
      while (b && b !== box && b.tagName !== 'BUTTON') b = b.parentNode;
      if (!b || b === box) return;
      if (b.id === 'fo-check-update') {
        checkUpdate(true);
        return;
      }
      var h = b.getAttribute('data-hash');
      if (!h || location.hash === '#' + h) return;
      location.hash = '#' + h;
    });

    markActive();
    window.addEventListener('hashchange', function () {
      var h = location.hash || '#/projects';
      if (navStack[navStack.length - 1] !== h) navStack.push(h);
      markActive();
      setTimeout(function () {
        if (!document.getElementById('fo-fab')) build();
        patchPages();
      }, 400);
    });

    setInterval(function () { checkUpdate(false); }, 3000);
    setInterval(patchPages, 1000);
    setInterval(patchProjectDelete, 1000);
    setInterval(refreshPipelineView, 2000);
  }

  /** 进度页兜底刷新：每 2 秒从后端拉状态，经 shim 事件总线喂给前端 store */
  function refreshPipelineView() {
    var inv = window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke;
    var bus = window.__TAURI_EVENT_PLUGIN_INTERNALS__;
    if (!inv || !bus || !bus.emit) return;
    var m = /#\/(?:progress|manual)\/([^/?]+)/.exec(location.hash || '');
    if (!m) return;
    var id;
    try { id = decodeURIComponent(m[1]); } catch (e) { return; }
    if (!id) return;

    inv('get_project', { id: id }).then(function (r) {
      if (!r || !r.success || !r.data || !r.data.project) return;
      var p = r.data.project;
      bus.emit('pipeline-project-status', {
        projectId: id, status: p.status, currentStage: p.currentStage
      });
      return inv('list_chapters', { projectId: id, includeIgnored: true }).then(function (c) {
        if (!c || !c.success || !c.data) return;
        var arr = c.data.chapters || [];
        for (var i = 0; i < arr.length; i++) {
          var ch = arr[i];
          var ss = ch.stageStatus || {};
          for (var k in ss) {
            if (!Object.prototype.hasOwnProperty.call(ss, k)) continue;
            bus.emit('pipeline-chapter-status', {
              projectId: id, chapterId: ch.id, chapterTitle: ch.title,
              stage: parseInt(k, 10), status: (ss[k] && ss[k].status) || 'pending',
              timestamp: (ss[k] && ss[k].updatedAt) || ''
            });
          }
        }
      });
    }).catch(function () {});
  }

  /** 工作台项目卡片加删除按钮 */
  function patchProjectDelete() {
    if ((location.hash || '#/projects') !== '#/projects') return;
    var items = document.querySelectorAll('.project-item');
    for (var i = 0; i < items.length; i++) {
      var card = items[i];
      if (card.querySelector('.fo-del-btn')) continue;
      var nameEl = card.querySelector('.name');
      var name = nameEl ? (nameEl.textContent || '').trim() : '该项目';
      var btn = document.createElement('div');
      btn.className = 'fo-del-btn';
      btn.textContent = '删除';
      btn.setAttribute('title', '删除项目 ' + name);
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        e.preventDefault();
        var target = this.parentElement;
        var n = target.querySelector('.name');
        var label = n ? (n.textContent || '').trim() : '该项目';
        if (!window.confirm('确定删除项目「' + label + '」？此操作不可撤销。')) return;
        var inv = window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke;
        if (!inv) return;
        // 从前端拿到项目 id：命令返回里有 id，用列表 API 反查
        inv('list_projects', {}).then(function (r) {
          var list = (r && r.data && r.data.projects) || [];
          for (var k = 0; k < list.length; k++) {
            if ((list[k].name || '').trim() === label) {
              return inv('delete_project', { id: list[k].id });
            }
          }
          throw new Error('未找到项目');
        }).then(function () {
          return inv('list_projects', {});
        }).then(function () {
          location.reload();
        }).catch(function (err) {
          alert('删除失败：' + (err && err.message ? err.message : err));
        });
      });
      card.appendChild(btn);
    }
  }

  function markActive() {
    var box = document.getElementById('fo-fab');
    if (!box) return;
    var cur = location.hash || '#/projects';
    var btns = box.querySelectorAll('button[data-hash]');
    for (var i = 0; i < btns.length; i++) {
      var h = '#' + btns[i].getAttribute('data-hash');
      if (cur === h || (h === '#/projects' && (cur === '#/' || cur === ''))) {
        btns[i].classList.add('fo-active');
      } else {
        btns[i].classList.remove('fo-active');
      }
    }
  }

  /** 每页的移动端补丁：标记工作台、模型详情按钮、提示词两级、删桌面按钮 */
  function patchPages() {
    patchWorkspace();
    patchModelDetails();
    patchPromptEditor();
    patchChapterControls();
    hideDesktopButtons();
  }

  /** 工作区：开始按钮移到顶部 + 章节列表可折叠 */
  function patchChapterControls() {
    var nav = document.querySelector('.chapter-navigation');
    if (!nav) return;
    var header = nav.querySelector('.nav-header') || nav;
    var content = nav.querySelector('.nav-content');
    // 顶部控制条（幂等）
    var bar = nav.querySelector('.fo-chapter-bar');
    if (!bar) {
      bar = document.createElement('div');
      bar.className = 'fo-chapter-bar';
      header.insertBefore(bar, header.firstChild);
    }

    // 把「开始改写 / 开始」主按钮 clone 到顶部；点击转发给原按钮
    var startBtns = document.querySelectorAll('.action-btn-main.primary, .panel-footer .m-btn.primary');
    var orig = null;
    for (var i = 0; i < startBtns.length; i++) {
      var t = (startBtns[i].textContent || '');
      if (t.indexOf('开始') >= 0 || t.indexOf('启动') >= 0) { orig = startBtns[i]; break; }
    }
    var topStart = bar.querySelector('.fo-start-top');
    if (orig && !topStart) {
      topStart = document.createElement('button');
      topStart.className = 'fo-start-top m-btn primary';
      topStart.type = 'button';
      topStart.textContent = '▶ ' + (orig.textContent || '开始').trim();
      topStart.addEventListener('click', function () {
        if (!orig.isConnected) return;
        orig.click();
      });
      bar.appendChild(topStart);
      // 隐藏底部的原按钮，避免两处重复
      orig.classList.add('fo-hide-original-start');
    }
    // 同步顶部按钮文字（比如「启动中…」）
    if (orig && topStart) {
      var label = (orig.textContent || '').trim();
      if (label && topStart.textContent.indexOf(label) < 0 && label.indexOf('启动中') >= 0) {
        topStart.textContent = '▶ ' + label;
      }
    }

    // 折叠章节按钮
    var toggle = bar.querySelector('.fo-chapter-toggle');
    if (!toggle && content) {
      toggle = document.createElement('button');
      toggle.className = 'fo-chapter-toggle';
      toggle.type = 'button';
      toggle.textContent = '隐藏章节';
      toggle.addEventListener('click', function () {
        var hidden = content.style.display === 'none';
        content.style.display = hidden ? '' : 'none';
        toggle.textContent = hidden ? '隐藏章节' : '显示章节';
      });
      bar.appendChild(toggle);
    }
  }

  function patchWorkspace() {
    // 只在首页路由生效，避免误伤提示词页的编辑区
    if ((location.hash || '#/projects') !== '#/projects') return;
    var main = document.querySelector('.main');
    if (main) main.style.setProperty('display', 'none', 'important');
  }

  function patchModelDetails() {
    var root = document.querySelector('.model-management');
    if (!root) return;
    var items = root.querySelectorAll('.model-item');
    for (var i = 0; i < items.length; i++) {
      var item = items[i];
      if (item.querySelector('.fo-detail-btn')) continue;
      var idx = i;
      var btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'fo-detail-btn';
      btn.textContent = '详情';
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        var inv = window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke;
        if (!inv) return;
        inv('list_ai_models', {}).then(function (r) {
          var ms = (r && r.data && r.data.models) || [];
          var m = ms[idx];
          if (!m) { alert('模型详情暂不可用'); return; }
          alert(
            '名称：' + m.name + '\n' +
            '提供商：' + m.provider + '\n' +
            '端点：' + (m.baseUrl || '-') + '\n' +
            '模型 ID：' + m.model + '\n' +
            'Temperature：' + m.temperature + '\n' +
            'Max Tokens：' + m.maxTokens + '\n' +
            '超时：' + (m.timeout || 300) + 's'
          );
        }).catch(function () { alert('模型详情暂不可用'); });
      });
      item.appendChild(btn);
    }
  }

  /** 提示词管理：列表 → 点模板进编辑页；编辑页加返回按钮 */
  function patchPromptEditor() {
    var root = document.querySelector('.prompt-management');
    if (!root) return;
    var main = root.querySelector('.main');
    if (!main) return;
    if (!main.querySelector('#fo-editor-back')) {
      var back = document.createElement('div');
      back.id = 'fo-editor-back';
      back.innerHTML = '<button type="button">← 返回模板列表</button>';
      back.querySelector('button').addEventListener('click', function () {
        root.classList.remove('fo-editor-open');
      });
      main.insertBefore(back, main.firstChild);
    }
    if (root.__foClickBound) return;
    root.__foClickBound = true;
    // 点模板列表里的任意一项就进编辑页（排除按钮/输入框）
    document.addEventListener('click', function (e) {
      var pm = document.querySelector('.prompt-management');
      if (!pm || pm.classList.contains('fo-editor-open')) return;
      var t = e.target;
      // 排除顶部按钮区
      if (t.closest('button, input, select')) {
        // 「新建模板」按钮也要进编辑页
        if ((t.textContent || '').indexOf('新建模板') < 0) return;
      }
      var list = pm.querySelector('.side-list');
      var inList = list && (t === list || list.contains(t));
      var isNew = (t.textContent || '').indexOf('新建模板') >= 0;
      if (inList || isNew) {
        setTimeout(function () { pm.classList.add('fo-editor-open'); }, 300);
      }
    }, true);
  }

  /** 工作区里「回到顶部 / 回到底部」是 PC 产物，手机上没意义 */
  function hideDesktopButtons() {
    var bs = document.querySelectorAll('button');
    for (var i = 0; i < bs.length; i++) {
      var t = (bs[i].textContent || '').trim();
      if (t.indexOf('回到顶部') >= 0 || t.indexOf('回到底部') >= 0) {
        bs[i].style.setProperty('display', 'none', 'important');
      }
    }
    // PC 文案：手机上没有「左侧」，改成「上方」
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var node;
    while ((node = walker.nextNode())) {
      if (node.nodeValue && node.nodeValue.indexOf('左侧章节列表') >= 0) {
        node.nodeValue = node.nodeValue.replace(/左侧章节列表/g, '上方章节列表');
      }
    }
  }

  /** 只有确实有新版才提示；其余全部静默 */
  function checkUpdate(force) {
    var internals = window.__TAURI_INTERNALS__;
    if (!internals || !internals.invoke) return;
    if (!force) {
      try {
        if (sessionStorage.getItem('fo_update_checked') === '1') return;
        sessionStorage.setItem('fo_update_checked', '1');
      } catch (e) {
      }
    }
    internals.invoke('check_update', {}).then(function (r) {
      var d = r && r.data;
      if (d && d.hasUpdate) {
        showUpdateDialog(d);
        return;
      }
      // 主动检查时给出明确反馈；自动检查保持静默
      if (!force) return;
      var why = d && d.error ? String(d.error) : '';
      if (why) {
        showToast('检查失败：' + why.slice(0, 60));
      } else {
        showToast('已是最新版本');
      }
    }).catch(function (e) {
      if (force) showToast('检查失败：' + String(e && e.message || e).slice(0, 60));
    });
  }

  /** 轻量提示条，2 秒后自动消失 */
  function showToast(msg) {
    var old = document.getElementById('fo-toast');
    if (old && old.parentNode) old.parentNode.removeChild(old);
    var t = document.createElement('div');
    t.id = 'fo-toast';
    t.textContent = msg;
    document.body.appendChild(t);
    setTimeout(function () {
      if (t.parentNode) t.parentNode.removeChild(t);
    }, 2200);
  }

  function showUpdateDialog(d) {
    if (document.getElementById('fo-upd')) return;
    var esc = function (s) {
      return String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    };
    var mask = document.createElement('div');
    mask.id = 'fo-upd';
    mask.innerHTML =
      '<div class="fo-upd-card">' +
      '<div class="fo-upd-title">发现新版本 ' + esc(d.latest) + '</div>' +
      '<div class="fo-upd-cur">当前版本 ' + esc(d.current) + '</div>' +
      '<div class="fo-upd-notes">' + (esc(d.notes).slice(0, 800) || '（无更新说明）') + '</div>' +
      '<div id="fo-upd-prog"><div id="fo-upd-bar"></div><span id="fo-upd-pct">0%</span></div>' +
      '<div class="fo-upd-actions">' +
      '<button class="fo-upd-later">稍后</button>' +
      '<button class="fo-upd-go">立即更新</button>' +
      '</div>' +
      '</div>';
    document.body.appendChild(mask);
    var go = mask.querySelector('.fo-upd-go');
    var prog = mask.querySelector('#fo-upd-prog');
    var bar = mask.querySelector('#fo-upd-bar');
    var pct = mask.querySelector('#fo-upd-pct');
    prog.style.display = 'none';
    var inv = window.__TAURI_INTERNALS__ && window.__TAURI_INTERNALS__.invoke;

    // 监听下载进度事件
    var bus = window.__TAURI_EVENT_PLUGIN_INTERNALS__;
    var unlisten = null;
    if (bus && bus.listen) {
      bus.listen('update-download-progress', function (e) {
        var p = e && e.payload;
        if (!p) return;
        if (typeof p.percent === 'number') {
          bar.style.width = p.percent + '%';
          pct.textContent = p.percent + '%';
          go.textContent = '下载中 ' + p.percent + '%';
        } else if (typeof p.bytes === 'number') {
          pct.textContent = (p.bytes / 1024 / 1024).toFixed(1) + ' MB';
          go.textContent = '下载中 ' + pct.textContent;
        }
      }).then(function (u) { unlisten = u; }).catch(function () {});
    }
    function cleanup() {
      if (unlisten) { try { unlisten(); } catch (e) {} unlisten = null; }
      if (mask.parentNode) mask.parentNode.removeChild(mask);
    }
    mask.querySelector('.fo-upd-later').addEventListener('click', cleanup);
    go.addEventListener('click', function () {
      if (!inv || (!d.apkUrl && !d.pageUrl)) return;
      go.disabled = true;
      prog.style.display = 'block';
      bar.style.width = '0%';
      pct.textContent = '0%';
      go.textContent = '下载中 0%';
      inv('download_update', { url: d.apkUrl || d.pageUrl }).then(function (r) {
        if (!r || !r.success || !r.data || !r.data.path) throw new Error('下载失败');
        bar.style.width = '100%';
        pct.textContent = '100%';
        go.textContent = '准备安装…';
        return inv('install_update', { path: r.data.path });
      }).then(function () {
        cleanup();
      }).catch(function () {
        prog.style.display = 'none';
        go.disabled = false;
        go.textContent = '重试更新';
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { setTimeout(build, 400); });
  } else {
    setTimeout(build, 400);
  }
})();
