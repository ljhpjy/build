#!/usr/bin/env node
/**
 * 准备 APK 需要的资源：
 *
 *   1. 下载/复用 Termux Node 运行时（build/runtime/<abi>）
 *   2. 交叉编译 Go PoW 求解器（runtime/bin/ds-pow）
 *   3. 打成 android/app/src/<flavor>/assets/{runtime.zip,fuckds.zip,default.env}
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

import { ensureRuntime } from './termux-runtime.mjs';
import { buildPow } from './build-pow.mjs';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = parseArgs(process.argv.slice(2));
const abi = args.abi || 'arm64-v8a';
const goarch = args.goarch || (abi === 'x86_64' ? 'amd64' : 'arm64');
const flavor = abi === 'x86_64' ? 'x64' : 'arm64';

const runtimeDir = path.join(ROOT, 'build', 'runtime', abi);
const assetsDir = path.join(ROOT, 'android', 'app', 'src', flavor, 'assets');

console.log(`\n=== fuckds-apk 资源准备：${abi} ===`);

const runtimeInfo = await ensureRuntime({
  abi,
  runtimeDir,
  force: Boolean(args.force),
});

const runtimeBin = path.join(runtimeDir, 'bin');
const pow = buildPow({
  projectRoot: ROOT,
  goarch,
  outFile: path.join(runtimeBin, 'ds-pow'),
});

// 额外准备两个备用版本：Node 侧在 ds-pow 崩溃时会自动切换。
// -static：Go 内部链接的静态 PIE（没有 cgo/NDK 依赖）
// -exe：静态 ET_EXEC（最后兜底）
const powVariants = [];
for (const [suffix, strategy] of [
  ['-static', 'internal'],
  ['-exe', 'exe'],
]) {
  const out = path.join(runtimeBin, `ds-pow${suffix}`);
  try {
    const variant = buildPow({
      projectRoot: ROOT,
      goarch,
      outFile: out,
      strategies: [strategy],
    });
    powVariants.push({ name: `ds-pow${suffix}`, type: variant.type, machine: variant.machine });
  } catch (err) {
    console.warn(`⚠️  PoW 备用版本 ${suffix} 构建失败，跳过：${err.message}`);
  }
}

const info = {
  ...runtimeInfo,
  pow: {
    type: pow.type,
    machine: pow.machine,
    builtAt: new Date().toISOString(),
    variants: powVariants,
  },
};
fs.writeFileSync(path.join(runtimeDir, 'runtime-info.json'), `${JSON.stringify(info, null, 2)}\n`);

fs.rmSync(assetsDir, { recursive: true, force: true });
fs.mkdirSync(assetsDir, { recursive: true });

const stage = path.join(ROOT, 'build', 'app-stage', abi);
fs.rmSync(stage, { recursive: true, force: true });
fs.mkdirSync(path.join(stage, 'web'), { recursive: true });
copyDir(path.join(ROOT, 'app', 'src'), path.join(stage, 'src'));
copyDir(path.join(ROOT, 'app', 'web', 'dist'), path.join(stage, 'web', 'dist'));
// 内置控制台的「文档」页里写死了旧默认端口，构建时同步成 8765
const webDist = path.join(stage, 'web', 'dist');
replaceInTree(webDist, '8787', '8765');
replaceInTree(webDist, '8465', '8765');
for (const file of ['package.json', 'LICENSE', 'ANDROID.md']) {
  fs.copyFileSync(path.join(ROOT, 'app', file), path.join(stage, file));
}

zipDir(runtimeDir, path.join(assetsDir, 'runtime.zip'));
zipDir(stage, path.join(assetsDir, 'fuckds.zip'));
fs.copyFileSync(path.join(ROOT, 'app', '.env.android'), path.join(assetsDir, 'default.env'));

const buildInfo = {
  abi,
  flavor,
  nodeVersion: info.nodeVersion,
  packages: info.packages,
  commit: process.env.GITHUB_SHA || '',
  builtAt: new Date().toISOString(),
};
fs.writeFileSync(path.join(assetsDir, 'build-info.json'), `${JSON.stringify(buildInfo, null, 2)}\n`);

console.log('\n📁 资源已生成：');
for (const name of fs.readdirSync(assetsDir)) {
  const full = path.join(assetsDir, name);
  console.log(`   ${name.padEnd(18)} ${(fs.statSync(full).size / 1024 / 1024).toFixed(2)} MB`);
}
console.log(`\n下一步：gradle -p android :app:assemble${flavor === 'x64' ? 'X64' : 'Arm64'}Release\n`);

/* ------------------------------------------------------------------ */

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const item = argv[i];
    if (!item.startsWith('--')) continue;
    const eq = item.indexOf('=');
    if (eq > 0) {
      out[item.slice(2, eq)] = item.slice(eq + 1);
    } else {
      const key = item.slice(2);
      const next = argv[i + 1];
      if (next && !next.startsWith('--')) {
        out[key] = next;
        i += 1;
      } else {
        out[key] = true;
      }
    }
  }
  return out;
}

function copyDir(src, dest) {
  if (!fs.existsSync(src)) throw new Error(`缺少目录：${src}`);
  fs.cpSync(src, dest, { recursive: true, force: true });
}

function replaceInTree(dir, from, to) {
  if (!fs.existsSync(dir)) return;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      replaceInTree(full, from, to);
      continue;
    }
    if (!/\.(js|css|html|json|txt)$/i.test(entry.name)) continue;
    const text = fs.readFileSync(full, 'utf8');
    if (!text.includes(from)) continue;
    fs.writeFileSync(full, text.split(from).join(to));
  }
}

function zipDir(dir, out) {
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.rmSync(out, { force: true });
  const check = spawnSync('zip', ['-v'], { stdio: 'ignore' });
  if (check.error) throw new Error('找不到 zip 命令，请先安装 zip（Ubuntu: apt install zip）');
  const res = spawnSync('zip', ['-q', '-r', '-9', '-X', out, '.'], {
    cwd: dir,
    stdio: 'inherit',
  });
  if (res.error) throw res.error;
  if (res.status !== 0) throw new Error(`zip 失败（退出码 ${res.status}）：${out}`);
}
