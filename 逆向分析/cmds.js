const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.js') && f !== 'index-BgQ00rP4.js');

const cmds = new Map();

for (const f of files) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  // find imports from index bundle
  const importRe = /import\s*\{([^}]*)\}\s*from\s*"\.\/index-BgQ00rP4\.js"/g;
  let m;
  const aliases = new Set();
  while ((m = importRe.exec(t))) {
    for (const part of m[1].split(',')) {
      const seg = part.trim();
      const mm = seg.match(/^([A-Za-z_$][\w$]*)\s+as\s+([A-Za-z_$][\w$]*)$/);
      if (mm && mm[1] === 'J') aliases.add(mm[2]);
      if (!mm && seg === 'J') aliases.add('J');
    }
  }
  for (const a of aliases) {
    const re = new RegExp('\\b' + a.replace(/\$/g, '\\$') + '\\(\\s*"([^"]+)"', 'g');
    let c;
    while ((c = re.exec(t))) {
      const name = c[1];
      if (!cmds.has(name)) cmds.set(name, new Set());
      cmds.get(name).add(f);
    }
  }
}

for (const [k, v] of [...cmds.entries()].sort()) {
  console.log(k, '<-', [...v].join(','));
}
