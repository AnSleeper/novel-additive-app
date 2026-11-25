const fs = require('fs');
const path = require('path');
const buf = fs.readFileSync(path.join(__dirname, '_extract', 'fleshout.exe'));
const text = buf.toString('utf8');
const needles = process.argv.slice(2);
for (const n of needles) {
  console.log('\n########## ' + n);
  let i = 0, c = 0;
  while ((i = text.indexOf(n, i)) >= 0 && c < 6) {
    console.log('\n--- @' + i + '\n' + text.slice(Math.max(0, i - 200), i + 1200));
    i += n.length; c++;
  }
  console.log('count=' + (text.split(n).length - 1));
}
