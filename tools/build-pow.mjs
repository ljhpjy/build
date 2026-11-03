import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

import { readElf } from './elf.mjs';

/**
 * 把 Go 版 PoW 求解器（cmd/powd）交叉编译成 Android 的 PIE 可执行文件。
 *
 * 注意：Go 的 -buildmode=pie 在 android/amd64 上必须走 cgo 外部链接；
 * arm64 通常可以内部链接。这里按顺序尝试：
 *   1. 内部链接 PIE（不需要 NDK）
 *   2. 如果找到 Android NDK，用 NDK clang 做 cgo 外部链接 PIE
 *   3. 最后兜底静态 exe（非 PIE，极少数环境可能拒绝执行）
 */
export function buildPow({ projectRoot, goarch, outFile, log = console.log }) {
  const srcDir = path.join(projectRoot, 'app', 'pow-src');
  if (!fs.existsSync(path.join(srcDir, 'go.mod'))) {
    throw new Error(`找不到 Go 源码：${srcDir}/go.mod`);
  }
  const go = findGo();
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.rmSync(outFile, { force: true });

  const baseEnv = {
    ...process.env,
    GOOS: 'android',
    GOARCH: goarch,
    GOTOOLCHAIN: 'auto',
  };
  const ldflags = '-s -w';
  const pieArgs = ['build', '-trimpath', '-buildmode=pie', `-ldflags=${ldflags}`, '-o', outFile, './cmd/powd'];
  const exeArgs = ['build', '-trimpath', '-buildmode=exe', `-ldflags=${ldflags}`, '-o', outFile, './cmd/powd'];

  const attempts = [{ label: 'PIE（Go 内部链接）', env: { CGO_ENABLED: '0' }, args: pieArgs }];

  const ndk = findNdk();
  if (ndk) {
    const clang = findClang(ndk, goarch);
    if (clang) {
      const cxx = clang.replace(/-clang$/, '-clang++');
      const env = { CGO_ENABLED: '1', CC: clang };
      if (fs.existsSync(cxx)) env.CXX = cxx;
      attempts.push({
        label: `PIE（NDK ${path.basename(ndk)} 外部链接）`,
        env,
        args: pieArgs,
      });
    } else {
      log(`⚠️  找到 NDK ${ndk}，但没有 ${goarch} 对应的 clang`);
    }
  } else {
    log('⚠️  没有找到 Android NDK；x86_64 的 PIE 需要它（会尝试静态 exe 兜底）');
  }

  attempts.push({ label: '静态 exe（非 PIE 兜底）', env: { CGO_ENABLED: '0' }, args: exeArgs });

  let built = false;
  for (const attempt of attempts) {
    fs.rmSync(outFile, { force: true });
    log(`🔨 编译 PoW：GOARCH=${goarch} · ${attempt.label}`);
    const res = spawnSync(go, attempt.args, {
      cwd: srcDir,
      env: { ...baseEnv, ...attempt.env },
      stdio: 'inherit',
    });
    if (res.error) {
      log(`⚠️  ${attempt.label} 启动失败：${res.error.message}`);
      continue;
    }
    if (res.status !== 0 || !fs.existsSync(outFile)) {
      log(`⚠️  ${attempt.label} 失败（退出码 ${res.status ?? 'null'}）`);
      continue;
    }
    built = true;
    break;
  }

  if (!built) {
    throw new Error('Go PoW 求解器编译失败：内部 PIE / NDK PIE / 静态 exe 都失败了');
  }

  fs.chmodSync(outFile, 0o755);

  const info = readElf(outFile);
  const expected = goarch === 'arm64' ? /AArch64/i : /X86-64|Advanced Micro Devices/i;
  if (!expected.test(info.machine)) {
    throw new Error(`ds-pow 架构不对：期望 ${goarch}，实际 ${info.machine}`);
  }
  if (info.type !== 'DYN') {
    log(`⚠️  ds-pow 不是 PIE（Type=${info.type}）。Android 5+ 可能拒绝执行，请检查 NDK/Go 工具链。`);
  }
  log(`✅ ds-pow：${info.type} ${info.machine}（${(fs.statSync(outFile).size / 1024 / 1024).toFixed(1)} MB）`);
  return info;
}

function findGo() {
  const candidates = [
    process.env.GO_BIN,
    'go',
    path.join(process.env.HOME || '', '.local', 'go', 'bin', 'go'),
    '/usr/local/go/bin/go',
    '/usr/lib/go/bin/go',
  ].filter(Boolean);
  for (const bin of candidates) {
    try {
      const res = spawnSync(bin, ['version'], { stdio: 'ignore' });
      if (!res.error && res.status === 0) return bin;
    } catch {
      /* 继续找 */
    }
  }
  throw new Error('找不到 Go 工具链（需要 Go 1.23+）：请安装 Go 或设置 GO_BIN');
}

/** 找 Android NDK：支持 ANDROID_NDK_HOME/ROOT，也支持 $ANDROID_HOME/ndk/<version>。 */
function findNdk() {
  const roots = [
    process.env.ANDROID_NDK_HOME,
    process.env.ANDROID_NDK_ROOT,
    process.env.ANDROID_HOME && path.join(process.env.ANDROID_HOME, 'ndk'),
    process.env.ANDROID_SDK_ROOT && path.join(process.env.ANDROID_SDK_ROOT, 'ndk'),
    '/usr/local/lib/android/sdk/ndk',
    '/usr/local/lib/android/sdk/ndk-bundle',
  ].filter(Boolean);

  const candidates = new Set();
  for (const root of roots) {
    if (!root || !fs.existsSync(root)) continue;
    if (fs.existsSync(path.join(root, 'toolchains', 'llvm'))) {
      candidates.add(root);
      continue;
    }
    for (const entry of fs.readdirSync(root)) {
      const full = path.join(root, entry);
      if (fs.existsSync(path.join(full, 'toolchains', 'llvm'))) candidates.add(full);
    }
  }

  return [...candidates]
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    .reverse()[0] || null;
}

/** 找对应架构的 NDK clang wrapper（优先 API 21，兼容所有 Android 8+ 设备）。 */
function findClang(ndk, goarch) {
  const host = process.platform === 'darwin' ? 'darwin-x86_64' : 'linux-x86_64';
  const binDir = path.join(ndk, 'toolchains', 'llvm', 'prebuilt', host, 'bin');
  if (!fs.existsSync(binDir)) return null;

  const target = goarch === 'arm64' ? 'aarch64-linux-android' : 'x86_64-linux-android';
  const names = fs.readdirSync(binDir).filter((name) => name.startsWith(target) && name.endsWith('-clang'));
  if (!names.length) return null;

  const preferred = names.find((name) => name === `${target}21-clang`);
  if (preferred) return path.join(binDir, preferred);

  names.sort((a, b) => {
    const api = (name) => {
      const match = name.match(new RegExp(`^${target}(\\d+)-clang$`));
      return match ? Number(match[1]) : 0;
    };
    return api(a) - api(b);
  });
  return path.join(binDir, names[0]);
}
