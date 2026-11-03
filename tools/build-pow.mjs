import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

import { readElf } from './elf.mjs';

/**
 * 把 Go 版 PoW 求解器（cmd/powd）交叉编译成 Android 的 PIE 可执行文件。
 *
 * 输出会被放进 runtime.zip 的 bin/ds-pow，Node 侧通过 POW_BIN 指向它。
 */
export function buildPow({ projectRoot, goarch, outFile, log = console.log }) {
  const srcDir = path.join(projectRoot, 'app', 'pow-src');
  if (!fs.existsSync(path.join(srcDir, 'go.mod'))) {
    throw new Error(`找不到 Go 源码：${srcDir}/go.mod`);
  }
  const go = findGo();
  fs.mkdirSync(path.dirname(outFile), { recursive: true });
  fs.rmSync(outFile, { force: true });

  const env = {
    ...process.env,
    GOOS: 'android',
    GOARCH: goarch,
    CGO_ENABLED: '0',
    GOFLAGS: '-trimpath',
    GOTOOLCHAIN: 'auto',
  };
  const ldflags = '-s -w';
  const pieArgs = ['build', '-trimpath', '-buildmode=pie', `-ldflags=${ldflags}`, '-o', outFile, './cmd/powd'];
  const plainArgs = ['build', '-trimpath', `-ldflags=${ldflags}`, '-o', outFile, './cmd/powd'];

  log(`🔨 编译 PoW 求解器：GOOS=android GOARCH=${goarch}（PIE）`);
  let res = spawnSync(go, pieArgs, { cwd: srcDir, env, stdio: 'inherit' });
  if (res.error) throw res.error;
  if (res.status !== 0) {
    log('⚠️  -buildmode=pie 失败，改用默认构建模式重试…');
    res = spawnSync(go, plainArgs, { cwd: srcDir, env, stdio: 'inherit' });
  }
  if (res.status !== 0 || !fs.existsSync(outFile)) {
    throw new Error('Go PoW 求解器编译失败');
  }
  fs.chmodSync(outFile, 0o755);

  const info = readElf(outFile);
  const expected = goarch === 'arm64' ? /AArch64/i : /X86-64|Advanced Micro Devices/i;
  if (!expected.test(info.machine)) {
    throw new Error(`ds-pow 架构不对：期望 ${goarch}，实际 ${info.machine}`);
  }
  if (info.type !== 'DYN') {
    log(`⚠️  ds-pow 不是 PIE（Type=${info.type}）。Android 5+ 可能拒绝执行，请检查 Go 版本。`);
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
