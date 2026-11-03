import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';

/**
 * Go 版 PoW 求解器（bin/ds-pow）的进程封装。
 *
 * 为什么用 Go：DeepSeekHashV1 要在 [0, difficulty)（常见 14.4 万）里线性搜索，
 * Go 实测 ~20-30ms，Node 里跑同样逻辑要慢一个数量级。
 *
 * Android 上可能遇到 CPU/内核/加载器差异导致某个二进制 SIGSEGV/SIGILL，
 * 所以构建时额外产出：
 *   ds-pow-static  Go 内部链接的静态 PIE
 *   ds-pow-exe     静态 ET_EXEC
 * 当前二进制异常退出时自动切到下一个，并记录 signal/stderr。
 */
export class PowSolver {
  constructor({ bin, logger }) {
    this.bin = bin;
    this.log = logger;
    this.proc = null;
    this.pending = new Map();
    this.seq = 0;
    this.closing = false;

    const candidates = [bin, `${bin}-static`, `${bin}-exe`];
    this.bins = candidates.filter((candidate) => {
      try {
        return fs.existsSync(candidate);
      } catch {
        return false;
      }
    });
    if (!this.bins.length) this.bins = [bin];
    this.binIndex = 0;
    if (this.bins.length > 1) {
      this.log?.info?.(`PoW 求解器候选：${this.bins.map((item) => path.basename(item)).join(' → ')}`);
    }
  }

  get currentBin() {
    return this.bins[Math.min(this.binIndex, this.bins.length - 1)];
  }

  #advanceFallback() {
    if (this.binIndex + 1 >= this.bins.length) return false;
    this.binIndex += 1;
    this.log?.warn(`PoW 求解器改用备用版本：${path.basename(this.bins[this.binIndex])}`);
    return true;
  }

  #ensureProcess() {
    if (this.proc && !this.proc.killed) return;

    const bin = this.currentBin;
    if (!fs.existsSync(bin)) {
      throw new Error(
        `找不到 PoW 求解器 ${bin}，先执行 \`npm run pow:build\`（需要 Go 工具链）`,
      );
    }

    this.closing = false;
    const child = spawn(bin, [], { stdio: ['pipe', 'pipe', 'pipe'] });
    this.proc = child;

    const failPending = (err) => {
      for (const { reject } of this.pending.values()) reject(err);
      this.pending.clear();
    };

    child.on('exit', (code, signal) => {
      if (this.proc !== child) return; // error handler 已经处理过
      this.log?.warn(
        `PoW 求解器退出（code ${code}, signal ${signal ?? 'none'}，${path.basename(bin)}）`,
      );
      this.proc = null;
      failPending(new Error('PoW 求解器已退出'));
      if (!this.closing) this.#advanceFallback();
    });

    // spawn 失败（ENOEXEC/EACCES 等）只发 error，不发 exit；不接住会把 Node 进程直接干掉
    child.on('error', (err) => {
      if (this.proc !== child) return;
      this.log?.warn(`PoW 求解器启动失败（${path.basename(bin)}）：${err.message}`);
      this.proc = null;
      failPending(err);
      if (!this.closing) this.#advanceFallback();
    });

    child.stderr?.on('data', (chunk) => {
      const text = String(chunk).trim();
      // ds-pow 的 fatal error / SIGSEGV traceback 都在 stderr，debug 级别会看不到
      if (text) this.log?.warn(`[ds-pow] ${text}`);
    });

    if (!child.stdout) return;
    const rl = readline.createInterface({ input: child.stdout });
    rl.on('line', (line) => {
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        return;
      }
      const waiter = this.pending.get(msg.id);
      if (!waiter) return;
      this.pending.delete(msg.id);
      if (msg.error) waiter.reject(new Error(`PoW: ${msg.error}`));
      else waiter.resolve({ answer: msg.answer, ms: msg.ms });
    });
  }

  solve({ challenge, salt, expireAt, difficulty }) {
    this.#ensureProcess();
    const id = (this.seq += 1);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('PoW 求解超时'));
      }, 15_000);

      this.pending.set(id, {
        resolve: (result) => {
          clearTimeout(timer);
          resolve(result);
        },
        reject: (err) => {
          clearTimeout(timer);
          reject(err);
        },
      });

      const proc = this.proc;
      if (!proc || !proc.stdin) {
        const waiter = this.pending.get(id);
        this.pending.delete(id);
        waiter?.reject(new Error('PoW 求解器未启动'));
        return;
      }
      proc.stdin.write(
        `${JSON.stringify({ id, challenge, salt, expireAt, difficulty })}\n`,
        (err) => {
          if (!err) return;
          const waiter = this.pending.get(id);
          this.pending.delete(id);
          waiter?.reject(err);
        },
      );
    });
  }

  close() {
    this.closing = true;
    const proc = this.proc;
    this.proc = null;
    if (proc && !proc.killed) proc.kill();
    // 先 reject 掉在途请求：它们的 15s 超时定时器只有走 resolve/reject 包装才会被清掉，
    // 否则进程退出（或测试结束）会被这些定时器拖住十几秒。
    for (const { reject } of this.pending.values()) reject(new Error('PoW 求解器已关闭'));
    this.pending.clear();
  }
}
