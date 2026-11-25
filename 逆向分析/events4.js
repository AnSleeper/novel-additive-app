const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '_web', 'assets');
const t = fs.readFileSync(path.join(dir, 'Progress-DLX7DR-T.js'), 'utf8');
const names = ['projectInfo', 'tool_result', 'streamingContent=""', 'case"done"', 'streamingToolCalls=[]'];
for (const n of names) {
  let idx = -1, c = 0;
  while ((idx = t.indexOf(n, idx + 1)) !== -1 && c < 8) {
    c++;
    console.log('\n===== [' + n + '] @' + idx + '\n' + t.slice(Math.max(0, idx - 300), idx + 500));
  }
}
