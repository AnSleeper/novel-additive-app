const fs = require('fs');
const L = fs.readFileSync('strings_utf8.txt', 'utf8').split(/\r?\n/);
const out = [];
L.forEach((s, i) => {
  const body = s.replace(/^@\d+\t/, '');
  if (body.length > 150 && /[\u4e00-\u9fa5]/.test(body)) {
    if (/你是一名|你是一位|你是|核心功能|参数：|工具/.test(body)) {
      out.push('===== LINE ' + (i + 1) + ' len=' + body.length + '\n' + body + '\n');
    }
  }
});
fs.writeFileSync('_prompts_all.txt', out.join('\n'), 'utf8');
console.log('blocks', out.length);
