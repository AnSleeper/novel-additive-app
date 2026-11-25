const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));
const seen = new Set();
for (const f of files) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  const re = /"((?:pipeline|manual|chapter|project|stage)[a-z-]*)"/g;
  let m;
  while ((m = re.exec(t))) {
    const n = m[1];
    if (seen.has(n)) continue;
    seen.add(n);
    console.log('--- ' + f + ' [' + n + ']\n' + t.slice(Math.max(0, m.index - 200), m.index + 300));
  }
}
