const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js'));
const idx = fs.readFileSync(path.join(dir, 'index-BgQ00rP4.js'), 'utf8');

function ctx(text, needle, before = 300, after = 500, max = 3) {
  const out = [];
  let i = 0;
  while ((i = text.indexOf(needle, i)) >= 0 && out.length < max) {
    out.push(text.slice(Math.max(0, i - before), i + after));
    i += needle.length;
  }
  return out;
}

console.log('success count', (idx.match(/success/g) || []).length);
for (const c of ctx(idx, 'success', 300, 400, 3)) console.log('---\n' + c);
console.log('\n\n#### export tail ####');
console.log(idx.slice(-1500));
