#!/usr/bin/env node
// 用法（在 DS-pet 目录）：node tools/sync-pet-assets.mjs [上游插件目录，默认 ../dsh-pet/dsh-pet]
import { execSync } from 'node:child_process';
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const UP = resolve(process.argv[2] ?? join(ROOT, '..', 'dsh-pet', 'dsh-pet'));
const HELPER = join(UP, 'runtime', 'electron-helper');
const ASSETS = join(ROOT, 'app', 'src', 'main', 'assets');
const WEB = join(ASSETS, 'pet-web');
const PET = join(ASSETS, 'pet');
const ANDROID_JS = join(ROOT, 'app', 'src', 'main', 'pet-web-android');

function stripJsonc(src) {
  let out = '';
  let inStr = false;
  for (let i = 0; i < src.length; ) {
    const c = src[i];
    const n = src[i + 1];
    if (inStr) {
      out += c;
      if (c === '\\') {
        out += n;
        i += 2;
        continue;
      }
      if (c === '"') inStr = false;
      i++;
    } else if (c === '"') {
      inStr = true;
      out += c;
      i++;
    } else if (c === '/' && n === '/') {
      while (i < src.length && src[i] !== '\n') i++;
    } else if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 2;
    } else {
      out += c;
      i++;
    }
  }
  return out.replace(/,(\s*[}\]])/g, '$1');
}

// 1. 构建共享纯逻辑
execSync('node scripts/build-desktop-core.mjs', { cwd: UP, stdio: 'inherit' });

// 2. 渲染壳（上游文件原样拷贝，只在 index.html 里注入两个安卓脚本）
rmSync(WEB, { recursive: true, force: true });
mkdirSync(WEB, { recursive: true });
for (const f of ['shared-core.js', 'constants.js', 'sprite.js', 'events.js', 'renderer.js']) {
  cpSync(join(HELPER, f), join(WEB, f));
}
cpSync(ANDROID_JS, WEB, { recursive: true });
let html = readFileSync(join(HELPER, 'index.html'), 'utf8');
html = html
  .replace('<script src="./shared-core.js"></script>', '<script src="./android-bridge.js"></script>\n    <script src="./shared-core.js"></script>')
  .replace('<script src="./events.js"></script>', '<script src="./android-overrides.js"></script>\n    <script src="./events.js"></script>');
if (!html.includes('android-bridge.js') || !html.includes('android-overrides.js')) {
  throw new Error('上游 index.html 的 script 结构变了，安卓脚本注入失败');
}
writeFileSync(join(WEB, 'index.html'), html);

// 3. 覆盖层依赖的上游标识：任何一个消失都说明 sprite.js 重构过，需要人工核对 android-overrides.js
const spritePath = join(WEB, 'sprite.js');
const sprite = readFileSync(spritePath, 'utf8');
const constants = readFileSync(join(WEB, 'constants.js'), 'utf8');
const required = [
  [sprite, 'class PetSprite'], [sprite, 'onContextMenu(e)'], [sprite, 'onMenuAction(leaf)'],
  [sprite, 'reloadDesktop()'], [sprite, 'setTip(on)'], [sprite, 'position()'],
  [sprite, 'dispose()'],
  [sprite, 'this.customPos'], [sprite, 'this.pressScoreFired'], [sprite, 'this.dragState'],
  [sprite, 'this.margin'], [sprite, 'this.hit'], [sprite, 'this.videoA'],
  [constants, 'let sprites'], [constants, 'function toScreen'],
];
for (const [text, id] of required) {
  if (!text.includes(id)) throw new Error('上游标识缺失：' + id);
}

// class 声明是不可赋值绑定，覆盖层无法替换 PetSprite。只改同步产物，不改上游检出。
const marker = 'class PetSprite {';
if (sprite.split(marker).length - 1 !== 1) {
  throw new Error('PetSprite 声明不唯一，无法改成可替换绑定');
}
writeFileSync(spritePath, sprite.replace(marker, 'var PetSprite = class PetSprite {'));

// 4. 素材与配置
rmSync(PET, { recursive: true, force: true });
cpSync(join(UP, 'assets', 'webm'), join(PET, 'webm'), { recursive: true });
cpSync(join(UP, 'assets', 'fonts'), join(PET, 'fonts'), { recursive: true });
cpSync(join(UP, 'assets', 'pic'), join(PET, 'pic'), { recursive: true });
const cfg = JSON.parse(stripJsonc(readFileSync(join(UP, 'assets', 'config.jsonc'), 'utf8')));
writeFileSync(join(PET, 'config.json'), JSON.stringify(cfg, null, 2));

console.log('[sync-pet-assets] 完成：', WEB, PET);
