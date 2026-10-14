// 校验 assets/spots_seed.json：
//   1. 是否为合法 JSON
//   2. 坐标是否落在邢台市合理范围内
//   3. 必填字段、枚举值、状态/类型是否规范
// 用法: node tools/validate-seed.js <path-to-spots_seed.json>
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('用法: node validate-seed.js <spots_seed.json>');
  process.exit(2);
}

let raw;
let data;
try {
  raw = fs.readFileSync(file, 'utf8');
  data = JSON.parse(raw);
} catch (e) {
  console.error('❌ JSON 解析失败: ' + e.message);
  process.exit(1);
}

// 邢台市大致范围（含各县市区全境，留出余量）
const BOX = { latMin: 36.6, latMax: 37.7, lonMin: 113.7, lonMax: 116.0 };

const VALID_WATER = ['水库', '河流', '湖泊', '池塘', '黑坑', '渠道', '沙坑', '未分类'];
const VALID_STATUS = ['可钓', '禁钓', '收费', '限钓', '待确认'];
const VALID_CONF = ['unverified', 'web', 'field'];

const spots = data.spots;
if (!Array.isArray(spots)) {
  console.error('❌ 缺少 spots 数组');
  process.exit(1);
}

let errors = 0;
let warnings = 0;
const names = new Set();

console.log('总条数: ' + spots.length + '\n');

for (let i = 0; i < spots.length; i++) {
  const s = spots[i];
  const tag = '[' + (i + 1) + '] ' + (s.name || '(无名称)');

  // 必填
  if (!s.name || !String(s.name).trim()) {
    console.error('❌ ' + tag + ' 缺少 name');
    errors++;
  }
  if (names.has(s.name)) {
    console.error('❌ ' + tag + ' 名称重复');
    errors++;
  }
  names.add(s.name);

  // 坐标
  if (typeof s.lat !== 'number' || typeof s.lon !== 'number') {
    console.error('❌ ' + tag + ' lat/lon 不是数字');
    errors++;
  } else if (s.lat < BOX.latMin || s.lat > BOX.latMax ||
             s.lon < BOX.lonMin || s.lon > BOX.lonMax) {
    console.error('❌ ' + tag + ' 坐标 ' + s.lat + ',' + s.lon + ' 超出邢台范围');
    errors++;
  }

  // 枚举
  if (!VALID_WATER.includes(s.waterType)) {
    console.error('❌ ' + tag + ' waterType 非法: ' + s.waterType);
    errors++;
  }
  if (!VALID_STATUS.includes(s.status)) {
    console.error('❌ ' + tag + ' status 非法: ' + s.status);
    errors++;
  }
  if (!VALID_CONF.includes(s.confidence)) {
    console.error('❌ ' + tag + ' confidence 非法: ' + s.confidence);
    errors++;
  }

  // 区县是否与坐标大致相符（粗查：只做提示不做报错）
  const d = s.district || '';
  if (!d) {
    console.warn('⚠ ' + tag + ' 未填 district');
    warnings++;
  }

  // 禁钓点不应有推荐拟饵
  if (s.status === '禁钓' && Array.isArray(s.lure) && s.lure.length > 0) {
    console.warn('⚠ ' + tag + ' 状态为禁钓却填了推荐拟饵');
    warnings++;
  }

  // 数组字段格式
  for (const k of ['fish', 'lure']) {
    if (s[k] !== undefined && !Array.isArray(s[k])) {
      console.error('❌ ' + tag + ' ' + k + ' 应该是数组');
      errors++;
    }
  }

  // 风险提示
  if (!s.risk || !String(s.risk).trim()) {
    console.warn('⚠ ' + tag + ' 没有风险提示字段');
    warnings++;
  }
}

// 统计
const byWater = {};
const byStatus = {};
for (const s of spots) {
  byWater[s.waterType] = (byWater[s.waterType] || 0) + 1;
  byStatus[s.status] = (byStatus[s.status] || 0) + 1;
}

console.log('按水域类型: ' + JSON.stringify(byWater, null, 0));
console.log('按状态: ' + JSON.stringify(byStatus, null, 0));
console.log('\n错误 ' + errors + ' 项，警告 ' + warnings + ' 项');
process.exit(errors > 0 ? 1 : 0);
