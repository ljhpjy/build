import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { spawnSync } from 'node:child_process';

import { assertMachine, readElf } from './elf.mjs';

/**
 * 从 Termux 官方仓库拉 Node.js 运行时（Android/bionic 原生二进制），解包并裁剪成
 * APK 里的 runtime.zip：
 *
 *   runtime/
 *     bin/node             ← Termux 的 PIE 可执行文件
 *     lib/*.so             ← 它需要的全部动态库（按 DT_NEEDED 闭包裁剪）
 *     etc/tls/cert.pem     ← CA 证书（Android 系统里没有 Termux 的证书路径）
 *
 * 为什么不自己交叉编译：Node 带完整 ICU，交叉编译又慢又容易挂；Termux 的包就是
 * 给 Android/bionic 编译的，直接拿来用最稳。APK 的 targetSdk 固定 28（和 Termux 一样），
 * 这样应用私有目录里的可执行文件才能在 Android 10+ 上 exec。
 */

const REPOS = [
  'https://packages-cf.termux.dev/apt/termux-main',
  'https://packages.termux.dev/apt/termux-main',
  'https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main',
  'https://mirrors.ustc.edu.cn/termux/apt/termux-main',
];

export const ARCH = {
  'arm64-v8a': { termux: 'aarch64', machine: /AArch64/i, goarch: 'arm64' },
  x86_64: { termux: 'x86_64', machine: /X86-64|Advanced Micro Devices/i, goarch: 'amd64' },
};

const TERMUX_PREFIX = '/data/data/com.termux/files/usr/';

/** Android 系统自带、不需要（也不应该）往 APK 里塞的库 */
const SYSTEM_LIBS = new Set([
  'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libstdc++.so',
  'libGLESv2.so', 'libEGL.so', 'libnativewindow.so', 'libsync.so', 'libvulkan.so',
  'libbinder_ndk.so', 'libjnigraphics.so', 'libmediandk.so', 'libaaudio.so',
  'libOpenSLES.so', 'libcamera2ndk.so', 'libnativehelper.so', 'libnetd_client.so',
  'libsqlite.so', 'libhardware.so', 'libutils.so', 'libcutils.so', 'libbase.so',
  'libhidlbase.so', 'libhidltransport.so', 'libhwbinder.so', 'libprocessgroup.so',
  'libion.so', 'libdmabufheap.so', 'libui.so', 'libgui.so', 'libfmq.so', 'libdrm.so',
  'libgrallocusage.so', 'libpagemap.so', 'libmeminfo.so', 'libprocinfo.so',
  'libstatspull.so', 'libstatssocket.so', 'libsysutils.so', 'libqti-perfd-client.so',
  'libqdMetaData.so', 'ld-android.so',
]);

const RUNTIME_FORMAT = 2;

export async function ensureRuntime({ abi, runtimeDir, force = false, log = console.log }) {
  const arch = ARCH[abi];
  if (!arch) throw new Error(`不支持的 ABI：${abi}（只支持 ${Object.keys(ARCH).join(' / ')}）`);

  const marker = path.join(runtimeDir, 'runtime-info.json');
  if (!force && fs.existsSync(marker) && fs.existsSync(path.join(runtimeDir, 'bin/node'))) {
    const cached = JSON.parse(fs.readFileSync(marker, 'utf8'));
    if (cached.format === RUNTIME_FORMAT && cached.abi === abi) {
      log(`♻️  复用已下载的 Termux 运行时：${runtimeDir}`);
      return cached;
    }
    log('缓存格式不匹配，重新下载 Termux 运行时');
  }

  fs.rmSync(runtimeDir, { recursive: true, force: true });
  fs.mkdirSync(path.join(runtimeDir, 'bin'), { recursive: true });
  fs.mkdirSync(path.join(runtimeDir, 'lib'), { recursive: true });
  fs.mkdirSync(path.join(runtimeDir, 'etc/tls'), { recursive: true });

  const work = path.join(path.dirname(runtimeDir), '..', '.termux', `${abi}`);
  const absoluteWork = path.resolve(work);
  fs.rmSync(absoluteWork, { recursive: true, force: true });
  fs.mkdirSync(path.join(absoluteWork, 'debs'), { recursive: true });
  fs.mkdirSync(path.join(absoluteWork, 'extract'), { recursive: true });
  fs.mkdirSync(path.join(absoluteWork, 'merged'), { recursive: true });

  log(`📦 解析 Termux ${arch.termux} 仓库…`);
  const index = await loadIndex(arch.termux, log);
  const packages = resolvePackages(index, ['nodejs', 'ca-certificates']);
  log(`  依赖包：${[...packages.values()].map((p) => `${p.name} ${p.version}`).join(', ')}`);

  const debs = await mapLimit([...packages.values()], 4, async (pkg) => {
      const filename = pkg.filename.replace(/^\.?\//, '');
      const out = path.join(absoluteWork, 'debs', `${pkg.name.replace(/[^A-Za-z0-9._+-]/g, '_')}.deb`);
      if (!fs.existsSync(out)) {
        const url = `${index.base}/${filename}`;
        log(`  ⬇️  ${pkg.name} ${pkg.version}`);
        const buf = await fetchBuffer(url, 180000);
        fs.writeFileSync(out, buf);
      }
      return { pkg, deb: out };
    });

  const merged = path.join(absoluteWork, 'merged');
  for (const { pkg, deb } of debs) {
    const dir = path.join(absoluteWork, 'extract', pkg.name.replace(/[^A-Za-z0-9._+-]/g, '_'));
    fs.rmSync(dir, { recursive: true, force: true });
    fs.mkdirSync(dir, { recursive: true });
    const res = spawnSync('dpkg-deb', ['-x', deb, dir], { encoding: 'utf8' });
    if (res.error) throw res.error;
    if (res.status !== 0) throw new Error(`dpkg-deb -x ${deb} 失败：\n${res.stderr || res.stdout}`);
    const prefix = path.join(dir, 'data/data/com.termux/files/usr');
    if (fs.existsSync(prefix)) mergeRuntimeFiles(prefix, merged);
  }

  const nodeExe = realFileUnder(path.join(merged, 'bin/node'), merged);
  if (!nodeExe) {
    throw new Error('Termux 的 nodejs 包里没有找到 bin/node（仓库结构可能变了）');
  }

  const libIndex = indexLibs(merged);
  const sonameIndex = indexSonames(merged);
  const libs = new Map();
  const queue = [nodeExe];
  while (queue.length) {
    const file = queue.pop();
    for (const need of readElf(file).needed) {
      if (SYSTEM_LIBS.has(need)) continue;
      if (libs.has(need)) continue;
      const src = libIndex.get(need) || sonameIndex.get(need);
      if (!src) {
        throw new Error(
          `缺少运行库 ${need}（被 ${path.basename(file)} 依赖）。如果 Termux 包结构变了，请把这条消息贴出来。`,
        );
      }
      libs.set(need, src);
      queue.push(src);
    }
  }

  const nodeInfo = readElf(nodeExe);
  if (nodeInfo.type !== 'DYN' && nodeInfo.type !== 'EXEC') {
    throw new Error(`bin/node 不是可执行 ELF（Type=${nodeInfo.type || '?'}）`);
  }
  assertMachine(nodeExe, arch.machine, 'Termux bin/node');

  for (const [name, src] of libs) {
    assertMachine(src, arch.machine, `Termux lib/${name}`);
    fs.copyFileSync(src, path.join(runtimeDir, 'lib', name));
    fs.chmodSync(path.join(runtimeDir, 'lib', name), 0o644);
  }

  fs.copyFileSync(nodeExe, path.join(runtimeDir, 'bin/node'));
  fs.chmodSync(path.join(runtimeDir, 'bin/node'), 0o755);

  // 校验闭包：runtime/lib 里每个库的 NEEDED 都必须存在
  const available = new Set(libs.keys());
  for (const file of [path.join(runtimeDir, 'bin/node'), ...[...available].map((n) => path.join(runtimeDir, 'lib', n))]) {
    for (const need of readElf(file).needed) {
      if (!available.has(need) && !SYSTEM_LIBS.has(need)) {
        throw new Error(`运行时闭包不完整：${path.basename(file)} 需要 ${need}`);
      }
    }
  }

  // CA 证书：优先用 Termux ca-certificates 自带的（/etc/tls/cert.pem）
  let certSource = 'termux';
  const termuxCert = findFile(merged, 'etc/tls/cert.pem');
  if (termuxCert) {
    fs.copyFileSync(termuxCert, path.join(runtimeDir, 'etc/tls/cert.pem'));
  } else {
    const runnerCert = '/etc/ssl/certs/ca-certificates.crt';
    if (fs.existsSync(runnerCert)) {
      fs.copyFileSync(runnerCert, path.join(runtimeDir, 'etc/tls/cert.pem'));
      certSource = 'runner';
      log('⚠️  Termux 包里没有 cert.pem，使用构建机上的 CA 证书');
    } else {
      log('⚠️  下载 Mozilla CA 包…');
      const pem = await fetchBuffer('https://curl.se/ca/cacert.pem', 60000);
      fs.writeFileSync(path.join(runtimeDir, 'etc/tls/cert.pem'), pem);
      certSource = 'mozilla';
    }
  }

  const info = {
    format: RUNTIME_FORMAT,
    abi,
    termuxArch: arch.termux,
    nodeVersion: packages.get('nodejs')?.version || '',
    packages: [...packages.keys()].sort(),
    libs: [...libs.keys()].sort(),
    builtAt: new Date().toISOString(),
    source: index.url,
    certSource,
  };
  fs.writeFileSync(marker, `${JSON.stringify(info, null, 2)}\n`);
  log(`✅ Node ${info.nodeVersion} 运行时就绪：${libs.size} 个动态库，${(totalSize(runtimeDir) / 1024 / 1024).toFixed(1)} MB`);
  return info;
}

/* ------------------------------------------------------------------ */

async function loadIndex(arch, log) {
  for (const base of REPOS) {
    for (const file of ['Packages.gz', 'Packages.xz', 'Packages']) {
      const url = `${base}/dists/stable/main/binary-${arch}/${file}`;
      try {
        const buf = await fetchBuffer(url, 90000);
        let text;
        if (file === 'Packages.gz') text = zlib.gunzipSync(buf).toString('utf8');
        else if (file === 'Packages.xz') text = unxz(buf);
        else text = buf.toString('utf8');
        if (!/^Package:\s*nodejs\s*$/m.test(text)) throw new Error('索引里没有 nodejs 包');
        const parsed = parsePackages(text);
        log(`  使用仓库：${base}`);
        return { ...parsed, base, url, text };
      } catch (err) {
        log(`  ✗ ${url}: ${err.message}`);
      }
    }
  }
  throw new Error('所有 Termux 镜像都取不到 Packages 索引，请检查网络或仓库地址');
}

function parsePackages(text) {
  const byName = new Map();
  const byProvides = new Map();
  for (const stanza of text.split(/\n\s*\n/)) {
    const fields = {};
    let current = '';
    for (const rawLine of stanza.split('\n')) {
      if (!rawLine.trim()) continue;
      if (/^\s/.test(rawLine) && current) {
        fields[current] += ` ${rawLine.trim()}`;
        continue;
      }
      const idx = rawLine.indexOf(':');
      if (idx <= 0) continue;
      current = rawLine.slice(0, idx).trim();
      fields[current] = rawLine.slice(idx + 1).trim();
    }
    if (!fields.Package || !fields.Filename) continue;
    const pkg = {
      name: fields.Package,
      version: fields.Version || '',
      filename: fields.Filename,
      depends: fields.Depends || '',
      provides: fields.Provides || '',
    };
    pkg.depends = [pkg.depends, fields['Pre-Depends'] || ''].filter(Boolean).join(', ');
    byName.set(pkg.name, pkg);
    for (const token of pkg.provides.split(',')) {
      const name = token.trim().split(/\s+/)[0].replace(/\(.*?\)/g, '').trim();
      if (name && !byProvides.has(name)) byProvides.set(name, pkg);
    }
  }
  if (!byName.has('nodejs')) throw new Error('Termux 索引里没有 nodejs 包');
  return { byName, byProvides };
}

function resolvePackages(index, roots) {
  const resolved = new Map();
  const queue = [...roots];
  while (queue.length) {
    const name = queue.shift();
    if (!name) continue;
    const pkg = index.byName.get(name) || index.byProvides.get(name);
    if (!pkg) throw new Error(`Termux 仓库里找不到依赖包：${name}`);
    if (resolved.has(pkg.name)) continue;
    resolved.set(pkg.name, pkg);
    // 纯数据包：证书文件不需要它声明的那些工具依赖，别把 bash/coreutils 拖进来
    if (pkg.name === 'ca-certificates') continue;
    for (const spec of pkg.depends.split(',')) {
      const trimmed = spec.trim();
      if (!trimmed) continue;
      const alternatives = trimmed.split('|').map((s) => s.trim());
      let found = null;
      for (const alt of alternatives) {
        const depName = alt.replace(/\(.*?\)/g, '').trim().replace(/:[a-z0-9-]+$/i, '');
        found = index.byName.get(depName) || index.byProvides.get(depName) || null;
        if (found) break;
      }
      if (!found) throw new Error(`${pkg.name} 的依赖无法解析：${trimmed}`);
      queue.push(found.name);
    }
  }
  return resolved;
}

function mergeRuntimeFiles(prefix, merged) {
  // node 可执行文件（可能是 bin/node 或指向 bin/node.real 的符号链）
  const binDir = path.join(prefix, 'bin');
  if (fs.existsSync(binDir)) {
    fs.mkdirSync(path.join(merged, 'bin'), { recursive: true });
    for (const name of fs.readdirSync(binDir)) {
      if (name !== 'node' && !name.startsWith('node.')) continue;
      copyEntry(path.join(binDir, name), path.join(merged, 'bin', name));
    }
  }

  const libDir = path.join(prefix, 'lib');
  if (fs.existsSync(libDir)) {
    for (const name of fs.readdirSync(libDir)) {
      if (!name.includes('.so')) continue;
      const src = path.join(libDir, name);
      let st;
      try {
        st = fs.lstatSync(src);
      } catch {
        continue;
      }
      if (st.isDirectory()) continue;
      copyEntry(src, path.join(merged, 'lib', name));
    }
  }
  const etc = path.join(prefix, 'etc');
  if (fs.existsSync(etc)) mergeTree(etc, path.join(merged, 'etc'));
}

function copyEntry(src, dest) {
  const st = fs.lstatSync(src);
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.rmSync(dest, { recursive: true, force: true });
  if (st.isSymbolicLink()) {
    fs.symlinkSync(fs.readlinkSync(src), dest);
  } else if (st.isFile()) {
    fs.copyFileSync(src, dest);
    fs.chmodSync(dest, 0o644);
  }
}

function mergeTree(src, dest) {
  for (const name of fs.readdirSync(src)) {
    const s = path.join(src, name);
    const d = path.join(dest, name);
    const st = fs.lstatSync(s);
    if (st.isDirectory()) {
      fs.mkdirSync(d, { recursive: true });
      mergeTree(s, d);
    } else {
      copyEntry(s, d);
    }
  }
}

function indexSonames(merged) {
  const libDir = path.join(merged, 'lib');
  const map = new Map();
  if (!fs.existsSync(libDir)) return map;
  for (const name of fs.readdirSync(libDir)) {
    if (!name.includes('.so')) continue;
    const real = realFileUnder(path.join(libDir, name), merged);
    if (!real || !isElf(real)) continue;
    try {
      const info = readElf(real);
      if (info.soname && !map.has(info.soname)) map.set(info.soname, real);
    } catch {
      /* 非 ELF 的 .so 跳过 */
    }
  }
  return map;
}

function indexLibs(merged) {
  const libDir = path.join(merged, 'lib');
  const map = new Map();
  if (!fs.existsSync(libDir)) return map;
  for (const name of fs.readdirSync(libDir)) {
    if (!name.includes('.so')) continue;
    const real = realFileUnder(path.join(libDir, name), merged);
    if (!real || !isElf(real)) continue;
    try {
      if (!fs.statSync(real).isFile()) continue;
    } catch {
      continue;
    }
    if (!map.has(name)) map.set(name, real);
  }
  return map;
}

/** 解析 Termux 包里的相对/绝对（$PREFIX）符号链，返回真实文件路径 */
function realFileUnder(file, prefix) {
  let cur = file;
  for (let i = 0; i < 40; i += 1) {
    let st;
    try {
      st = fs.lstatSync(cur);
    } catch {
      return null;
    }
    if (!st.isSymbolicLink()) return st.isFile() ? cur : null;
    let target = fs.readlinkSync(cur);
    if (path.isAbsolute(target)) {
      if (!target.startsWith(TERMUX_PREFIX)) return null;
      target = path.join(prefix, target.slice(TERMUX_PREFIX.length));
    } else {
      target = path.resolve(path.dirname(cur), target);
    }
    cur = target;
  }
  return null;
}

function isElf(file) {
  try {
    const fd = fs.openSync(file, 'r');
    try {
      const magic = Buffer.alloc(4);
      fs.readSync(fd, magic, 0, 4, 0);
      return magic.toString('latin1') === '\x7fELF';
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    return false;
  }
}

function findFile(merged, relPath) {
  return realFileUnder(path.join(merged, relPath), merged);
}

function unxz(buf) {
  const res = spawnSync('xz', ['-dc'], { input: buf, maxBuffer: 512 * 1024 * 1024 });
  if (res.error) throw res.error;
  if (res.status !== 0) throw new Error(`xz 解压失败：${res.stderr?.toString() || ''}`);
  return res.stdout.toString('utf8');
}

async function fetchBuffer(url, timeoutMs) {
  const res = await fetch(url, {
    redirect: 'follow',
    signal: AbortSignal.timeout(timeoutMs),
    headers: {
      'User-Agent': 'fuckds-apk-build/1.0 (+https://github.com/xingling66666/fuckds)',
      Accept: '*/*',
    },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} ${res.statusText}`);
  return Buffer.from(await res.arrayBuffer());
}

async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let cursor = 0;
  async function worker() {
    for (;;) {
      const index = cursor;
      cursor += 1;
      if (index >= items.length) return;
      results[index] = await fn(items[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

function totalSize(dir) {
  let sum = 0;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) sum += totalSize(full);
    else if (entry.isFile()) sum += fs.statSync(full).size;
  }
  return sum;
}
