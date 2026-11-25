const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const t = fs.readFileSync(path.join(dir, 'Progress-DLX7DR-T.js'), 'utf8');
const names = ['handleStreamEvent', 'manual-stream', 'manual_stream', 'stream-event', 'listen(', 'unlisten', 'payload'];
for (const n of names) {
  let idx = -1, c = 0;
  while ((idx = t.indexOf(n, idx + 1)) !== -1 && c < 6) {
    c++;
    console.log('\n===== [' + n + '] @' + idx + '\n' + t.slice(Math.max(0, idx - 400), idx + 600));
  }
}
