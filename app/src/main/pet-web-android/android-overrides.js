'use strict';
// 安卓平台差异：触屏坐标、窗口余量、长按菜单、灭屏暂停、位置持久化。
(() => {
  const native = window.AndroidPet;
  const params = new URLSearchParams(location.search);
  const TOP_MARGIN_RATIO = Number(params.get('topMarginRatio') || '0.3');
  const LONG_PRESS_MS = 500;
  const LONG_PRESS_SLOP_DP = 8;
  let mediaPaused = false;

  const style = document.createElement('style');
  style.textContent =
    '.pet-hit{touch-action:none;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none}';
  document.head.appendChild(style);

  // 灭屏时 WebView 可能先收到暂停、后才创建视频。拦住 play()，避免亮屏前又把解码拉起来。
  const nativePlay = HTMLMediaElement.prototype.play;
  HTMLMediaElement.prototype.play = function play() {
    if (mediaPaused) return Promise.resolve();
    return nativePlay.call(this);
  };

  // 窗口随手指移动时 WebView 内坐标不可靠：screenX/Y 改用 MotionEvent.rawX/rawY（物理像素），
  // sprite.js 里的 toLocal(e.screenX) 换算照常成立。
  for (const type of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel']) {
    window.addEventListener(
      type,
      (e) => {
        if (e.pointerType !== 'touch') return;
        const [x, y] = native.rawPointer().split(',').map(Number);
        if (!Number.isFinite(x) || !Number.isFinite(y)) return;
        try {
          Object.defineProperty(e, 'screenX', { configurable: true, value: x });
          Object.defineProperty(e, 'screenY', { configurable: true, value: y });
        } catch (err) {
          console.warn('[android-overrides] screenX', err);
        }
      },
      { capture: true },
    );
  }

  const Base = PetSprite;
  PetSprite = class AndroidPetSprite extends Base {
    constructor(pet) {
      super(pet);
      // 只在头顶留气泡空间，左右下不留：安卓窗口内的触摸不会穿透到下层应用
      const top = this.size * TOP_MARGIN_RATIO;
      this.margin = { t: top, r: 0, b: 0, l: 0 };
      this.el.style.left = '0px';
      this.el.style.top = top + 'px';

      let customPos = null;
      try {
        customPos = JSON.parse(native.loadPos(this.pet.id) || 'null');
      } catch {
        customPos = null;
      }
      if (
        !customPos ||
        typeof customPos.rx !== 'number' ||
        typeof customPos.ry !== 'number' ||
        !Number.isFinite(customPos.rx) ||
        !Number.isFinite(customPos.ry)
      ) {
        customPos = null;
      }
      Object.defineProperty(this, 'customPos', {
        configurable: true,
        get: () => customPos,
        set: (v) => {
          customPos = v;
          native.savePos(this.pet.id, JSON.stringify(v ?? null));
        },
      });
      this.position();

      this.longPress = null;
      const signal = this.ac.signal;
      this.hit.addEventListener('pointerdown', (e) => this.armLongPress(e), { signal });
      this.hit.addEventListener('pointermove', (e) => this.checkLongPress(e), { signal });
      window.addEventListener('pointerup', () => this.cancelLongPress(), { signal });
      window.addEventListener('pointercancel', () => this.cancelLongPress(), { signal });
    }

    dispose() {
      this.cancelLongPress();
      super.dispose();
    }

    // 触屏长按 500 ms 开菜单：与 src/client/pet.ts 第 1211–1238 行同一判定
    armLongPress(e) {
      if (e.pointerType !== 'touch') return;
      this.cancelLongPress();
      const pointerId = e.pointerId;
      this.longPress = {
        x: e.screenX,
        y: e.screenY,
        timer: setTimeout(() => this.fireLongPress(pointerId), LONG_PRESS_MS),
      };
    }

    checkLongPress(e) {
      const lp = this.longPress;
      if (lp && Math.hypot(e.screenX - lp.x, e.screenY - lp.y) > LONG_PRESS_SLOP_DP * CONFIG.scale) {
        this.cancelLongPress();
      }
    }

    cancelLongPress() {
      if (!this.longPress) return;
      clearTimeout(this.longPress.timer);
      this.longPress = null;
    }

    fireLongPress(pointerId) {
      this.longPress = null;
      const d = this.dragState;
      if (!d.active || d.dragging) return;
      d.active = false;
      this.pressScoreFired = true;
      try {
        this.hit.releasePointerCapture(pointerId);
      } catch {
        /* 已释放 */
      }
      this.openNativeMenu();
    }

    openNativeMenu() {
      this.stopThrow();
      this.stopMove();
      const tools = [
        { label: '回到初始位置', action: 'home' },
        { label: '重载配置', action: 'reload' },
      ];
      const tree = S.buildMenuTree(this.animations);
      // PopupMenu 只支持一层子菜单：去掉「动作」这一级，分类直接放在根上
      const groups = tree.length ? tree[0].children : [];
      native.showMenu(JSON.stringify(tools.concat(groups)));
    }

    // WebView 自带的长按 contextmenu 不再走桌面菜单，统一由 armLongPress 打开原生菜单
    onContextMenu(e) {
      e.preventDefault();
    }

    // 触屏没有悬停，名字提示不显示
    setTip() {
      super.setTip(false);
    }

    reloadDesktop() {
      native.reload();
    }
  };

  window.__androidBridge.on('menuAction', (leaf) => {
    for (const s of sprites) s.onMenuAction(leaf);
  });

  window.__androidBridge.on('paused', (paused) => {
    mediaPaused = !!paused;
    for (const s of sprites) {
      for (const v of [s.videoA, s.videoB]) {
        if (mediaPaused) v.pause();
        else if (v.classList.contains('is-front')) v.play().catch(() => {});
      }
    }
  });
})();
