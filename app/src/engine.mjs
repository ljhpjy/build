import fs from 'node:fs';

import { DirectEngine } from './direct/client.mjs';

/**
 * 选择引擎（默认 direct）：
 *   ENGINE=direct  只直连（需要 bin/ds-pow）；条件不满足直接抛错
 *   ENGINE=browser 只走 Playwright（驱动真实网页，不依赖 PoW 求解器）
 *   ENGINE=auto    有 Go 求解器就直连，否则回退浏览器
 *
 * Android APK 说明：
 * - APK 内不包含 Chromium / Playwright，browser 引擎不可用；
 * - 所以这里改成 async，并且只在真正需要 browser 时才动态 import，
 *   这样 direct 模式完全不会去加载 playwright；
 * - 首次启动时还没有 token，也必须把服务拉起来（否则控制台打不开、没法配 token），
 *   因此 Android 上直接返回 DirectEngine，由它在真正请求时报告「未配置 token」。
 */
export async function createEngine({ config, logger }) {
  const engine = config.engine ?? 'direct';
  const isAndroid = process.env.FUCKDS_ANDROID === '1';

  const binExists = fs.existsSync(config.direct.powBin);
  const hasToken = Boolean(config.deepseek.token);

  if (isAndroid) {
    if (engine === 'browser') {
      logger.warn('Android 版不包含 Chromium/Playwright，browser 引擎不可用，已自动改用 direct 引擎');
    }
    if (!binExists) {
      throw new Error(
        `Android 版缺少 PoW 求解器：${config.direct.powBin}。APK 可能损坏，或与当前 CPU 架构不匹配（${process.arch}）`,
      );
    }
    return new DirectEngine({ config, logger: logger.child('direct') });
  }

  const wantDirect = engine !== 'browser';
  const canDirect = binExists && hasToken;

  if (wantDirect && canDirect) {
    return new DirectEngine({ config, logger: logger.child('direct') });
  }

  if (engine === 'direct') {
    throw new Error(
      `ENGINE=direct 但条件不满足：powBin=${config.direct.powBin}（存在=${binExists}），token=${hasToken}。先执行 npm run pow:build`,
    );
  }
  if (wantDirect) {
    logger.warn('没有可用的 Go PoW 求解器（npm run pow:build），回退到 Playwright 引擎');
  }

  // 只有真正要用浏览器引擎时才加载 driver；playwright 在 Android 上根本没装。
  try {
    const { DeepSeekWeb } = await import('./browser/driver.mjs');
    return new DeepSeekWeb({ config, logger: logger.child('web') });
  } catch (err) {
    throw new Error(`browser 引擎需要 playwright + Chromium，当前环境加载失败：${err.message}`);
  }
}
