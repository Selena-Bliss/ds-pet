'use strict';
// 安卓平台差异：触屏坐标、窗口余量、长按菜单、灭屏暂停、位置持久化、
// 气泡按需撑高、左右透明边裁剪、折叠/旋转时的密度、摇一摇甩飞。
(() => {
  const native = window.AndroidPet;
  const params = new URLSearchParams(location.search);
  // 紧凑窗口：左右各裁掉这么多倍宠物宽。身体从画布 31% 处才开始，0.15 切不到身体。
  const SIDE_CROP = Math.min(0.15, Math.max(0, Number(params.get('sideCrop') || '0') || 0));
  const LONG_PRESS_MS = 500;
  const LONG_PRESS_SLOP_DP = 8;
  let mediaPaused = false;
  // 低电量时由 Kotlin 下发的静止毫秒数。0 表示按原速度接上下一段。
  let powerHoldMs = 0;

  const style = document.createElement('style');
  style.textContent = [
    '.pet-hit{touch-action:none;-webkit-touch-callout:none;-webkit-user-select:none;user-select:none}',
    // 快速换动画时，还没出帧或刚被 pause 的 video 会盖上系统播放键（灰底椭圆三角）。
    'video.pet-video{background:transparent!important}',
    'video.pet-video::-webkit-media-controls,video.pet-video::-webkit-media-controls-enclosure,',
    'video.pet-video::-webkit-media-controls-panel,video.pet-video::-webkit-media-controls-play-button,',
    'video.pet-video::-webkit-media-controls-start-playback-button,',
    'video.pet-video::-webkit-media-controls-overlay-play-button,',
    'video.pet-video::-webkit-media-controls-overlay-enclosure{display:none!important;-webkit-appearance:none;opacity:0!important;pointer-events:none!important}',
  ].join('');
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
      for (const v of [this.videoA, this.videoB]) {
        v.controls = false;
        v.disablePictureInPicture = true;
        v.setAttribute('playsinline', '');
        v.setAttribute('webkit-playsinline', '');
      }
      this.layoutReady = false;
      this.disposed = false;
      // 先把桌面那圈 0.5 倍余量收掉。头顶等气泡出现再长，左右只在紧凑窗口时向内裁。
      this.fitBubbleMargin();

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
      this.layoutReady = true;
      this.fitBubbleMargin();
      this.position();

      this.bubbleObserver = null;
      if (typeof ResizeObserver === 'function') {
        this.bubbleObserver = new ResizeObserver(() => this.fitBubbleMargin());
        this.bubbleObserver.observe(this.bubble);
      }

      this.longPress = null;
      const signal = this.ac.signal;
      this.hit.addEventListener('pointerdown', (e) => this.armLongPress(e), { signal });
      this.hit.addEventListener('pointermove', (e) => this.checkLongPress(e), { signal });
      window.addEventListener('pointerup', () => this.cancelLongPress(), { signal });
      window.addEventListener('pointercancel', () => this.cancelLongPress(), { signal });
    }

    dispose() {
      this.cancelPowerHold();
      this.disposed = true;
      this.bubbleObserver?.disconnect();
      this.bubbleObserver = null;
      this.cancelLongPress();
      super.dispose();
    }

    // 电量低于 20% 时，这段播完先停在最后一帧，再进下一段。拖拽、甩抛、走路途中不插这段空白。
    handleEnded() {
      if (this.disposed) return;
      if (this.dragState.active || this.throwRef !== null || this.moveRef !== null || powerHoldMs <= 0) {
        super.handleEnded();
        return;
      }
      this.armPowerHold();
    }

    playOnce(name) {
      this.cancelPowerHold();
      super.playOnce(name);
    }

    switchTo(next, nextOnce) {
      this.cancelPowerHold();
      super.switchTo(next, nextOnce);
    }

    armPowerHold() {
      this.cancelPowerHold();
      const wait = powerHoldMs;
      if (wait <= 0) {
        super.handleEnded();
        return;
      }
      this.powerHoldTimer = setTimeout(() => {
        this.powerHoldTimer = null;
        if (this.disposed) return;
        if (mediaPaused || this.dragState.active || this.throwRef !== null) {
          this.powerHoldPending = true;
          return;
        }
        super.handleEnded();
      }, wait);
    }

    cancelPowerHold() {
      if (this.powerHoldTimer) clearTimeout(this.powerHoldTimer);
      this.powerHoldTimer = null;
      this.powerHoldPending = false;
    }

    freezePowerHold() {
      if (!this.powerHoldTimer) return;
      clearTimeout(this.powerHoldTimer);
      this.powerHoldTimer = null;
      this.powerHoldPending = true;
    }

    releasePowerHold() {
      if (this.powerHoldTimer) {
        clearTimeout(this.powerHoldTimer);
        this.powerHoldTimer = null;
        this.powerHoldPending = true;
      }
      if (!this.powerHoldPending || this.disposed) return;
      if (mediaPaused || this.dragState.active || this.throwRef !== null) return;
      this.powerHoldPending = false;
      super.handleEnded();
    }

    // 没有气泡时头顶余量为 0，窗口贴住宠物，上面的触摸落到下层应用。
    // 气泡出现后按它实际高出宠物的部分把窗口向上长出来。
    renderBubble() {
      super.renderBubble();
      this.fitBubbleMargin();
    }

    fitBubbleMargin() {
      if (this.disposed) return;
      const side = -SIDE_CROP * this.size;
      let top = 0;
      const show = !!(this.bubbleOn || this.whisperOn || this.workOn);
      if (show && this.bubble) {
        const bubble = this.bubble.getBoundingClientRect();
        const host = this.el.getBoundingClientRect();
        const above = host.top - bubble.top;
        if (Number.isFinite(above) && above > 0.5) {
          // 阴影在布局盒外面，补一点避免被窗口切掉；再高也不超过约一只宠物宽。
          top = Math.min(Math.ceil(above + this.size * 0.04), this.size * 0.85);
        }
      }
      const changed = this.margin.t !== top || this.margin.l !== side || this.margin.r !== side;
      this.margin.t = top;
      this.margin.b = 0;
      this.margin.l = side;
      this.margin.r = side;
      this.el.style.left = side + 'px';
      this.el.style.top = top + 'px';
      if (changed && this.layoutReady) this.sendBounds(this.pos.x, this.pos.y);
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
      const tools = [];
      // 没填 API Key 时配置里的 balanceEnabled 是 false，这三项都不出现。
      if (this.pet.balanceEnabled) {
        tools.push(
          { label: '查看余额', action: 'show-balance' },
          { label: '碎碎念', action: 'whisper' },
          { label: '对话', action: 'chat' },
        );
      }
      tools.push(
        { label: '回到初始位置', action: 'home' },
        { label: '重载配置', action: 'reload' },
      );
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

    // 旋转只改工作区的宽高。上游 relayout 会拿旋转前的绝对坐标去夹新视口：
    // 竖屏下半部落在横屏外面，夹完就把比例写成贴边，再转回去也回不到原来的位置。
    relayout() {
      this.space = null;
      const keep = this._orientRatio;
      this._orientRatio = null;
      if (this.dragState.active || this.throwRef !== null) return;
      this.stopMove();
      if (keep && Number.isFinite(keep.rx) && Number.isFinite(keep.ry)) {
        this.customPos = { rx: keep.rx, ry: keep.ry };
      }
      this.position();
    }

    // 手机工作区窄，默认又朝左：只按朝向走时，贴在左边 planMove 会失败，菜单里就变成原地播。
    // 当前方向放不下整段位移时掉头再试一次，朝向和镜像跟着换。
    tryMove(preferredName) {
      const before = this.facing;
      const moved = super.tryMove(preferredName);
      if (moved !== false) return moved;
      this.facing = before === 'left' ? 'right' : 'left';
      const retry = super.tryMove(preferredName);
      if (retry === false) this.facing = before;
      return retry;
    }
  };

  window.__androidBridge.on('menuAction', (leaf) => {
    // 悬浮窗不获焦，输不了字。对话改开一个能弹出输入法的透明页面。
    if (leaf && leaf.action === 'chat') {
      try {
        const pet = typeof sprites !== 'undefined' && sprites[0] && sprites[0].pet;
        native.openChat(pet && pet.id ? String(pet.id) : '');
      } catch (e) {
        console.error('[android-overrides] chat', e);
      }
      return;
    }
    for (const s of sprites) s.onMenuAction(leaf);
  });

  // 比 renderer.js 的 onDisplays 先注册：这时 VIEW 还是旋转前的。先记下中心点比例，再换密度。
  // applyDeskGeometry 随后改视口，relayout 用这份比例在新视口里重放。
  window.__androidBridge.on('displays', (geo) => {
    const hull = geo && geo.hull;
    if (!hull || !(hull.width > 0) || !(hull.height > 0)) return;
    const w = VIEW.w;
    const h = VIEW.h;
    if (w > 0 && h > 0 && typeof sprites !== 'undefined') {
      for (const s of sprites) {
        if (!s || s.dragState.active || s.throwRef !== null) continue;
        const rx = (s.pos.x + s.halfW) / w;
        const ry = (s.pos.y + s.halfH) / h;
        if (Number.isFinite(rx) && Number.isFinite(ry)) s._orientRatio = { rx, ry };
      }
    }
    const scale = Number(geo.scale);
    if (Number.isFinite(scale) && scale > 0) CONFIG.scale = scale;
  });

  window.__androidBridge.on('powerHold', (ms) => {
    powerHoldMs = Math.max(0, Number(ms) || 0);
    if (powerHoldMs > 0 || typeof sprites === 'undefined' || !sprites) return;
    for (const s of sprites) s.releasePowerHold?.();
  });

  // 速度已经是 CSS 像素/秒，直接交给 onDeskHit，不要再除一次 scale。
  window.__androidBridge.on('shakeFling', (payload) => {
    if (mediaPaused || typeof sprites === 'undefined' || !sprites) return;
    const vx = Number(payload && payload.vx);
    const vy = Number(payload && payload.vy);
    if (!Number.isFinite(vx) || !Number.isFinite(vy)) return;
    for (const s of sprites) {
      if (!s || s.disposed || (s.dragState && s.dragState.active)) continue;
      s.cancelLongPress?.();
      s.closeMenu?.();
      s.onDeskHit(vx, vy);
    }
  });

  window.__androidBridge.on('paused', (paused) => {
    mediaPaused = !!paused;
    if (typeof sprites === 'undefined' || !sprites) return;
    for (const s of sprites) {
      if (mediaPaused) {
        s.freezePowerHold?.();
        for (const v of [s.videoA, s.videoB]) v.pause();
      } else if (s.powerHoldPending) {
        s.releasePowerHold?.();
      } else {
        for (const v of [s.videoA, s.videoB]) {
          if (v.classList.contains('is-front')) v.play().catch(() => {});
        }
      }
    }
  });
})();
