const fs = require('fs');
const path = require('path');
const buf = fs.readFileSync(path.join(__dirname, '_extract', 'fleshout.exe'));

// decode whole buffer as utf8 with offset mapping (approximate): walk manually
const out = [];
let i = 0;
const n = buf.length;
let cur = '';
let start = 0;
function flush() {
  if (cur.length >= 12) out.push({ off: start, s: cur });
  cur = '';
}

while (i < n) {
  const b = buf[i];
  let len = 0;
  if (b < 0x80) {
    // printable ascii or whitespace
    if (b === 0x09 || b === 0x0a || b === 0x0d || (b >= 0x20 && b <= 0x7e)) {
      len = 1;
    } else len = 0;
  } else if (b >= 0xc2 && b <= 0xdf) len = 2;
  else if (b >= 0xe0 && b <= 0xef) len = 3;
  else if (b >= 0xf0 && b <= 0xf4) len = 4;

  if (len > 0 && i + len <= n) {
    const chunk = buf.subarray(i, i + len);
    const s = chunk.toString('utf8');
    if (s.includes('\uFFFD')) { flush(); i += 1; continue; }
    if (cur === '') start = i;
    cur += s;
    i += len;
  } else {
    flush();
    i += 1;
  }
}
flush();

fs.writeFileSync(path.join(__dirname, 'strings_utf8.txt'), out.map((o) => `@${o.off}\t${o.s.replace(/\r?\n/g, '\\n')}`).join('\n'), 'utf8');
console.log('segments', out.length);
