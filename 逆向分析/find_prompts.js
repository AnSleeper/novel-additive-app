const fs = require('fs');
// 用法: node find_prompts.js <fleshout.exe 路径>
const buf = fs.readFileSync(process.argv[2] || 'fleshout.exe');
const needles = [
  '你是一位敏锐的成人向小说内容分析师',
  '请分析以下章节内容',
  '你是一名专业的小说',
  '项目未配置改写提示词',
  '项目未配置识别提示词',
  '**重要：输出格式要求**',
  '你是一位小说手动改写助手',
];
function findAll(needle) {
  const nb = Buffer.from(needle, 'utf8');
  const res = [];
  let i = 0;
  while ((i = buf.indexOf(nb, i)) !== -1) { res.push(i); i += 1; }
  return res;
}
for (const n of needles) {
  const pos = findAll(n);
  console.log('=== ' + n + ' -> ' + pos.length + ' at ' + pos.slice(0, 10).join(','));
}
