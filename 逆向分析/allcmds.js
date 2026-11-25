const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));
const set = new Map();
for (const f of files) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  const re = /"([a-z][a-z0-9]*(?:_[a-z0-9]+){1,4})"/g;
  let m;
  while ((m = re.exec(t))) {
    const n = m[1];
    if (!set.has(n)) set.set(n, new Set());
    set.get(n).add(f);
  }
}
for (const [k, v] of [...set.entries()].sort()) {
  console.log(k + ' <- ' + [...v].join(','));
}
