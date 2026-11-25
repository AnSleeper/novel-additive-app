const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));
const cmds = new Map();
// aliases: find invoke wrapper function names by looking for `invoke("...")` in index bundle
for (const f of files) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  const re = /["']([a-z][a-z0-9]*(?:_[a-z0-9]+)+)["']\s*(?:,\s*\{|\))/g;
  let m;
  while ((m = re.exec(t))) {
    const n = m[1];
    if (!cmds.has(n)) cmds.set(n, new Set());
    cmds.get(n).add(f);
  }
}
console.log('TOTAL', cmds.size);
for (const k of [...cmds.keys()].sort()) console.log(k);
