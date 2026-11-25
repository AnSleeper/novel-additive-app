const fs = require('fs');
const path = require('path');
const buf = fs.readFileSync(path.join(__dirname, '_extract', 'fleshout.exe'));
const [off, len] = [parseInt(process.argv[2], 10), parseInt(process.argv[3] || '4000', 10)];
// decode from offset, stopping at NUL
let end = off;
while (end < buf.length && buf[end] !== 0) end++;
const slice = buf.subarray(off, Math.min(off + len, end));
console.log(slice.toString('utf8'));
