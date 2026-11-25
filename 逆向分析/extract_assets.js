const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const http = require('http');

const WORK = __dirname;
const EXE = path.join(WORK, '_extract', 'fleshout.exe');
const OUT = path.join(WORK, '_web');
const PORT = 9333;

const ASSETS = [
  '/index.html',
  '/assets/index-AoZ0d9K0.js',
  '/assets/index-BgQ00rP4.js',
  '/assets/index-BJCd8mTr.js',
  '/assets/index-tAdUFASl.css',
  '/assets/ManagementPageShell-C0pgEU4b.js',
  '/assets/ManagementPageShell-Q9GHkIot.css',
  '/assets/ModelManagement-CGJkAAxC.js',
  '/assets/ModelManagement-DwomBVRD.css',
  '/assets/pencil-DKMfAOpG.js',
  '/assets/pipeline-CB9O-Iho.js',
  '/assets/plus-CUdRZyIR.js',
  '/assets/Progress-BU3DqSiX.css',
  '/assets/Progress-DLX7DR-T.js',
  '/assets/project-CxYZdS6r.js',
  '/assets/ProjectEditDialog-D1P7b8u7.css',
  '/assets/ProjectEditDialog-HBPVTwhR.js',
  '/assets/ProjectList-BDCRcxrS.css',
  '/assets/ProjectList-R3GfWTol.js',
  '/assets/PromptManagement-CgC2HdaM.css',
  '/assets/PromptManagement-D12WuRb_.js',
  '/assets/PromptRewriteEditor-CNNBLtXg.css',
  '/assets/PromptRewriteEditor-DSPe_4lF.js',
  '/assets/trash-2-Be3iQKOl.js',
  '/assets/upload-CaxeDiFi.js',
  '/assets/useApiCall-D9_rs-oO.js',
  '/assets/Wizard-DA7O3k4k.css',
  '/assets/Wizard-DMWAeO_F.js',
  '/assets/zap-BHIoZeh9.js',
  '/icons/128x128.png',
  '/icons/128x128@2x.png',
  '/icons/icon.png',
  '/favicon.ico',
];

function getJson(url) {
  return new Promise((resolve, reject) => {
    http.get(url, (res) => {
      let d = '';
      res.on('data', (c) => (d += c));
      res.on('end', () => {
        try { resolve(JSON.parse(d)); } catch (e) { reject(e); }
      });
    }).on('error', reject);
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const env = Object.assign({}, process.env, {
    WEBVIEW2_USER_DATA_FOLDER: path.join(WORK, '_wv2data'),
    WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: `--remote-debugging-port=${PORT}`,
  });
  const child = spawn(EXE, [], { env, cwd: path.dirname(EXE), detached: false });
  child.on('error', (e) => console.error('spawn error', e));

  let targets = null;
  for (let i = 0; i < 60; i++) {
    await sleep(500);
    try {
      targets = await getJson(`http://127.0.0.1:${PORT}/json`);
      if (targets && targets.length) break;
    } catch (e) { /* not up yet */ }
  }
  if (!targets || !targets.length) {
    console.error('no devtools target');
    child.kill();
    process.exit(1);
  }
  const page = targets.find((t) => t.type === 'page') || targets[0];
  console.log('target:', page.url);

  const ws = new WebSocket(page.webSocketDebuggerUrl);
  let id = 0;
  const pending = new Map();
  ws.onmessage = (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      if (msg.error) reject(new Error(JSON.stringify(msg.error)));
      else resolve(msg.result);
    }
  };
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const mid = ++id;
      pending.set(mid, { resolve, reject });
      ws.send(JSON.stringify({ id: mid, method, params }));
    });
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });

  await send('Runtime.enable');
  await sleep(2500);

  const expr = `(async () => {
    const paths = ${JSON.stringify(ASSETS)};
    const out = {};
    for (const p of paths) {
      try {
        const r = await fetch(p);
        if (!r.ok) { out[p] = { error: 'HTTP ' + r.status }; continue; }
        const buf = await r.arrayBuffer();
        let bin = '';
        const bytes = new Uint8Array(buf);
        const chunk = 0x8000;
        for (let i = 0; i < bytes.length; i += chunk) {
          bin += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
        }
        out[p] = { b64: btoa(bin), size: bytes.length };
      } catch (e) { out[p] = { error: String(e) }; }
    }
    return out;
  })()`;

  const res = await send('Runtime.evaluate', {
    expression: expr,
    awaitPromise: true,
    returnByValue: true,
    timeout: 60000,
  });
  const data = res.result && res.result.value;
  if (!data) {
    console.error('no data', JSON.stringify(res).slice(0, 2000));
    child.kill();
    process.exit(1);
  }
  for (const [p, v] of Object.entries(data)) {
    if (v.error) { console.log('FAIL', p, v.error); continue; }
    const dest = path.join(OUT, p.replace(/\//g, path.sep));
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.writeFileSync(dest, Buffer.from(v.b64, 'base64'));
    console.log('OK', p, v.size);
  }
  child.kill();
  process.exit(0);
}

main().catch((e) => { console.error(e); process.exit(1); });
