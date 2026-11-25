const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const all = new Set();
for (const f of fs.readdirSync(dir).filter((x) => x.endsWith('.js'))) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  const re = /"((?:pipeline|manual)[a-zA-Z0-9:_-]*)"/g;
  let m;
  while ((m = re.exec(t))) all.add(m[1]);
}
console.log([...all].sort().join('\n'));
