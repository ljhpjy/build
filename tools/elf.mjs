import { spawnSync } from 'node:child_process';

/** 用 readelf 读出 ELF 的类型 / 架构 / NEEDED / SONAME。 */
export function readElf(file) {
  const res = spawnSync('readelf', ['-h', '-d', file], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    env: { ...process.env, LC_ALL: 'C' },
  });
  if (res.error) throw res.error;
  if (res.status !== 0) {
    throw new Error(`readelf 失败（${file}）：\n${res.stderr || res.stdout}`);
  }
  const out = res.stdout;
  return {
    type: (out.match(/Type:\s+(\S+)/) || [])[1] || '',
    machine: ((out.match(/Machine:\s+(.+)/) || [])[1] || '').trim(),
    needed: [...out.matchAll(/\(NEEDED\)[^\[]*\[(.+?)\]/g)].map((m) => m[1]),
    soname: (out.match(/\(SONAME\)[^\[]*\[(.+?)\]/) || [])[1] || '',
  };
}

export function assertMachine(file, expected, label) {
  const info = readElf(file);
  if (!expected.test(info.machine)) {
    throw new Error(
      `${label || file} 的架构不对：期望 ${expected}，实际是 “${info.machine}”（ABI 不匹配）`,
    );
  }
  return info;
}

export function describeElf(file) {
  const info = readElf(file);
  return `${info.type || '?'} ${info.machine || '?'}`;
}

/** 返回所有 LOAD 段的 p_align（16KB 页设备要求 >= 0x4000）。 */
export function loadAligns(file) {
  const res = spawnSync('readelf', ['-lW', file], {
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    env: { ...process.env, LC_ALL: 'C' },
  });
  if (res.error) throw res.error;
  if (res.status !== 0) {
    throw new Error(`readelf -lW 失败（${file}）：\n${res.stderr || res.stdout}`);
  }
  const aligns = [];
  for (const line of res.stdout.split('\n')) {
    if (!/^\s*LOAD\s+/.test(line)) continue;
    const fields = line.trim().split(/\s+/);
    const last = fields[fields.length - 1];
    if (/^0x[0-9a-fA-F]+$/.test(last)) aligns.push(Number.parseInt(last, 16));
  }
  return aligns;
}
