const fs = require('fs');
// 用法: node dump_prompt_ranges.js <fleshout.exe 路径>
const buf = fs.readFileSync(process.argv[2] || 'fleshout.exe');
const ranges = [
  ['REWRITE_MISSING', 19395490, 19395589],
  ['SUMMARY_SYSTEM', 19395589, 19395892],
  ['JSON_WARN_1', 19395892, 19396207],
  ['SUMMARY_USER', 19396207, 19397517],
  ['IDENTIFY_MISSING', 19397517, 19397617],
  ['JSON_WARN_2', 19397617, 19397922],
  ['IDENTIFY_USER', 19397922, 19399632],
  ['REWRITE_DEFAULT', 19399632, 19399632 + 4000],
];
for (const [name, a, b] of ranges) {
  const s = buf.slice(a, b).toString('utf8');
  console.log('========== ' + name + ' [' + (b - a) + '] ==========');
  console.log(JSON.stringify(s));
  console.log('');
}
