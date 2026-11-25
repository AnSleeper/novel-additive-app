const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));
const targets = process.argv.slice(2);
const out = [];

for (const f of files) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  for (const name of targets) {
    let idx = -1;
    while ((idx = t.indexOf('"' + name + '"', idx + 1)) !== -1) {
      const start = Math.max(0, idx - 120);
      const snippet = t.slice(start, Math.min(t.length, idx + 700));
      out.push('\n===== ' + f + ' @' + idx + ' [' + name + ']\n' + snippet);
    }
  }
}
fs.writeFileSync(path.join(__dirname, '_callargs.txt'), out.join('\n'), 'utf8');
console.log('wrote', out.length, 'snippets');
