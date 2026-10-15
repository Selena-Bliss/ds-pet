'use strict';
// window.petBridge 的安卓实现：接口与 runtime/electron-helper/preload.js 一一对应，
// 原生对象 AndroidPet 由 PetJsBridge 注入；Kotlin → JS 的事件经 __androidBridge.emit 派发。
(() => {
  const native = window.AndroidPet;
  const listeners = {};
  const on = (name, cb) => {
    (listeners[name] ||= []).push(cb);
  };
  window.__androidBridge = {
    on,
    emit(name, payload) {
      for (const cb of listeners[name] || []) {
        try {
          cb(payload);
        } catch (e) {
          console.error('[android-bridge]', name, e);
        }
      }
    },
  };
  window.petBridge = {
    setBounds(x, y, width, height, boxX, boxY, size, bottomPad, vx, vy) {
      native.setBounds(JSON.stringify({ x, y, width, height, boxX, boxY, size, bottomPad, vx, vy }));
    },
    // 安卓窗口整窗可触摸（见方案 1.4 第 2 条），以下三项没有对应操作
    setInteractive() {},
    setInputBusy() {},
    setInputRegions() {},
    openDshSite() {},
    reportFlight(state) {
      native.reportFlight(JSON.stringify(state));
    },
    onFlightStates(cb) {
      on('flightStates', cb);
    },
    reportCollide(targetId, vx, vy) {
      native.reportCollide(String(targetId), Number(vx), Number(vy));
    },
    onPetHit(cb) {
      on('petHit', cb);
    },
    onDisplays(cb) {
      on('displays', cb);
    },
  };
})();
