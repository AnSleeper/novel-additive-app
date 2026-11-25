const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');

function show(file, pattern, before, after, max) {
  const t = fs.readFileSync(path.join(dir, file), 'utf8');
  const re = new RegExp(pattern, 'g');
  let m, n = 0;
  console.log('\n##### ' + file + ' :: ' + pattern);
  while ((m = re.exec(t)) && n < max) {
    console.log('\n--- @' + m.index + '\n' + t.slice(Math.max(0, m.index - before), m.index + after));
    n++;
  }
  console.log('\ntotal matches: ' + (t.match(re) || []).length);
}

const file = process.argv[2];
const pattern = process.argv[3];
const before = parseInt(process.argv[4] || '150', 10);
const after = parseInt(process.argv[5] || '500', 10);
const max = parseInt(process.argv[6] || '10', 10);
show(file, pattern, before, after, max);
