const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));
const out = [];
const seen = new Set();
for (const f of files) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  const re = /["']([a-z][a-z0-9]*(?:_[a-z0-9]+)+)["']\s*(,\s*\{[^;]{0,700}?\)\s*[,;)]|\))/g;
  let m;
  while ((m = re.exec(t))) {
    const n = m[1];
    if (seen.has(n)) continue;
    seen.add(n);
    out.push('### ' + n + '  [' + f + ']\n' + m[0].slice(0, 700));
  }
}
fs.writeFileSync(path.join(__dirname, '_cmd_callsites.txt'), out.join('\n\n'), 'utf8');
console.log('wrote', out.length);
