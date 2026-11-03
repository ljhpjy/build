import fs from 'node:fs';
import path from 'node:path';

import { loadConfig } from './config.mjs';
import { createLogger } from './logger.mjs';
import { createEngine } from './engine.mjs';
import { createServer } from './server.mjs';

const config = loadConfig();
const logger = createLogger(config.logLevel);

// Android 外壳靠这个文件清理「应用进程被杀、Node 变成孤儿」的情况。
const pidFile = path.join(config.dataDir, 'server.pid');
try {
  fs.writeFileSync(pidFile, `${process.pid}\n`);
} catch (err) {
  logger.warn('写入 server.pid 失败:', err.message);
}

const web = await createEngine({ config, logger });
const server = createServer({ config, logger, web });

const shutdown = async (signal) => {
  logger.info(`收到 ${signal}，正在关闭…`);
  server.close();
  await web.close().catch(() => {});
  try {
    fs.rmSync(pidFile, { force: true });
  } catch {
    /* ignore */
  }
  process.exit(0);
};

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('unhandledRejection', (err) => logger.error('未处理的 Promise 拒绝:', err));

if (config.runtime.warmup) {
  web.warmUp()
    .then(() => logger.info('预热完成'))
    .catch((err) => logger.warn('预热失败（首次请求会重新启动）:', err.message));
}

server.listen(config.server.port, config.server.host, () => {
  const base = `http://${config.server.host}:${config.server.port}`;
  logger.info('服务已启动');
  logger.info(`  接口       ${base}/v1/chat/completions`);
  logger.info(`  模型列表   ${base}/v1/models`);
  logger.info(`  健康检查   ${base}/healthz`);
  logger.info(
    `  登录态     ${
      config.deepseek.tokens.length
        ? `已配置 ✅ 号池 ${config.deepseek.tokens.length} 个`
        : process.env.FUCKDS_ANDROID === '1'
          ? '未配置 ❌（打开控制台 → 账号页填入 userToken）'
          : '未配置 ❌（npm run auth -- <userToken>）'
    }`,
  );
  logger.info(`  引擎       ${web.status().engine || 'browser'}`);
  logger.info(`  并发       ${config.runtime.concurrency}，单次超时 ${Math.round(config.runtime.requestTimeoutMs / 1000)}s`);
});

server.on('error', (err) => {
  logger.error('HTTP 服务启动失败:', err.message);
  process.exit(1);
});
