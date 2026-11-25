/*
 * FleshOut Android —— Tauri IPC 兼容层
 * 将原桌面端 window.__TAURI_INTERNALS__ 的 invoke/event 协议桥接到原生后端。
 * 前端资源保持原样，不做任何业务改动。
 */
(function () {
  'use strict';

  var pending = new Map();
  var reqSeq = 0;
  var cbSeq = 0;
  var callbacks = new Map();
  var listeners = new Map();
  var listenerSeq = 0;

  function makeError(e) {
    var err = new Error((e && e.message) || '操作失败');
    if (e && e.code) err.code = e.code;
    return err;
  }

  function post(cmd, payload) {
    return new Promise(function (resolve, reject) {
      var id = 'r' + (++reqSeq);
      pending.set(id, { resolve: resolve, reject: reject });
      try {
        window.FleshOutNative.call(id, cmd, JSON.stringify(payload === undefined ? null : payload));
      } catch (e) {
        pending.delete(id);
        reject(e);
      }
    });
  }

  window.__FO_RESOLVE = function (id, json) {
    var p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    var res;
    try {
      res = JSON.parse(json);
    } catch (e) {
      p.reject(new Error('响应解析失败'));
      return;
    }
    if (res && res.ok) {
      p.resolve(res.data === undefined ? null : res.data);
    } else {
      p.reject(makeError(res && res.error));
    }
  };

  function dispatch(event, payload) {
    var set = listeners.get(event);
    if (!set || set.size === 0) return;
    var entries = Array.from(set.values());
    for (var i = 0; i < entries.length; i++) {
      var entry = entries[i];
      var cb = callbacks.get(entry.handlerId);
      if (!cb) continue;
      try {
        cb.fn({ event: event, id: entry.listenerId, payload: payload });
      } catch (e) {
        console.error('[event] handler error', e);
      }
      if (cb.once) {
        callbacks.delete(entry.handlerId);
        set.delete(entry.listenerId);
      }
    }
  }

  window.__FO_EVENT = function (event, json) {
    var payload = null;
    try {
      payload = JSON.parse(json);
    } catch (e) {
      payload = null;
    }
    dispatch(event, payload);
  };

  function transformCallback(cb, once) {
    var id = ++cbSeq;
    callbacks.set(id, { fn: cb, once: !!once });
    return id;
  }

  function toByteArray(text) {
    var bytes = new TextEncoder().encode(text);
    var out = new Array(bytes.length);
    for (var i = 0; i < bytes.length; i++) out[i] = bytes[i];
    return out;
  }

  function invoke(cmd, args, options) {
    args = args || {};
    switch (cmd) {
      case 'plugin:event|listen': {
        var event = args.event;
        var handlerId = args.handler;
        var listenerId = ++listenerSeq;
        if (!listeners.has(event)) listeners.set(event, new Map());
        listeners.get(event).set(listenerId, { handlerId: handlerId, listenerId: listenerId });
        return Promise.resolve(listenerId);
      }
      case 'plugin:event|unlisten': {
        var ev = args.event;
        var lid = args.eventId;
        var set = listeners.get(ev);
        if (set) set.delete(lid);
        return Promise.resolve(null);
      }
      case 'plugin:event|emit':
      case 'plugin:event|emit_to': {
        dispatch(args.event, args.payload);
        return Promise.resolve(null);
      }
      case 'plugin:resources|close':
        return Promise.resolve(null);

      case 'plugin:dialog|open': {
        var opt = args.options || {};
        // 移植补丁：模板导入要能选到 zip 压缩包。
        // 桌面端 filters 只写了 json，安卓文件选择器会据此把 zip 藏起来。
        if (opt.filters && opt.filters.length) {
          for (var fi = 0; fi < opt.filters.length; fi++) {
            var exts = opt.filters[fi].extensions;
            if (exts && Array.prototype.indexOf.call(exts, 'json') >= 0 &&
                Array.prototype.indexOf.call(exts, 'zip') < 0) {
              exts.push('zip');
            }
          }
        }
        return post('dialog:open', opt);
      }
      case 'plugin:dialog|save':
        return post('dialog:save', args.options || {});

      case 'plugin:fs|read_text_file':
        // 移植补丁：模板导入前前端会先读文件做 JSON.parse 校验。
        // zip 是二进制，读出来必然解析失败，导致确认弹窗根本打不开。
        // 给 zip 一个合法的占位 JSON，让流程走到后端的批量导入命令。
        if (String(args.path || '').toLowerCase().endsWith('.zip')) {
          return Promise.resolve(toByteArray('{"name":"ZIP 模板包（批量导入）"}'));
        }
        return post('fs:read_text_file', { path: args.path }).then(function (text) {
          return toByteArray(text || '');
        });
      case 'plugin:fs|write_text_file': {
        var path = null;
        if (options && options.headers && options.headers.path) {
          path = decodeURIComponent(options.headers.path);
        }
        var content = '';
        try {
          content = new TextDecoder().decode(args);
        } catch (e) {
          content = '';
        }
        return post('fs:write_text_file', { path: path, content: content });
      }

      default:
        return post('cmd:' + cmd, args);
    }
  }

  var internals = window.__TAURI_INTERNALS__ || {};
  internals.invoke = invoke;
  internals.transformCallback = transformCallback;
  internals.convertFileSrc = function (p) {
    return p;
  };
  internals.metadata = {
    currentWindow: { label: 'main' },
    currentWebview: { label: 'main', windowLabel: 'main' }
  };
  internals.ipc = function () {
    return Promise.resolve();
  };
  window.__TAURI_INTERNALS__ = internals;

  window.__TAURI_EVENT_PLUGIN_INTERNALS__ = {
    unregisterListener: function () {},
    emit: function (event, payload) {
      dispatch(event, payload);
    }
  };

  if (!window.__TAURI__) {
    window.__TAURI__ = {};
  }

  if (typeof crypto === 'undefined' || !crypto.randomUUID) {
    var cryptoObj = window.crypto || {};
    cryptoObj.randomUUID = function () {
      var d = Date.now().toString(16);
      return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        var r = (Math.random() * 16) | 0;
        var v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
      }) + d.slice(-4);
    };
    try {
      window.crypto = cryptoObj;
    } catch (e) {}
  }
})();
