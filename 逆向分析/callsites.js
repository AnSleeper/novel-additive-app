const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const targets = process.argv.slice(2);

for (const f of targets) {
  const t = fs.readFileSync(path.join(dir, f), 'utf8');
  // find imported alias for J
  const importRe = /import\s*\{([^}]*)\}\s*from\s*"\.\/index-BgQ00rP4\.js"/g;
  let m;
  const aliases = new Set();
  while ((m = importRe.exec(t))) {
    for (const part of m[1].split(',')) {
      const seg = part.trim();
      const mm = seg.match(/^J\s+as\s+([A-Za-z_$][\w$]*)$/);
      if (mm) aliases.add(mm[1]);
      if (seg === 'J') aliases.add('J');
    }
  }
  console.log('\n\n########## ' + f + ' aliases=' + [...aliases].join(','));
  for (const a of aliases) {
    const re = new RegExp('\\b' + a.replace(/\$/g, '\\$') + '\\(\\s*"', 'g');
    let c;
    while ((c = re.exec(t))) {
      const start = Math.max(0, c.index - 250);
      console.log('\n--- @' + c.index + '\n' + t.slice(start, c.index + 900));
    }
  }
}
