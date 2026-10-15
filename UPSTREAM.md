# 上游同步记录

来源：[PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)（`origin` = `https://github.com/PC2005-cloud/dsh-pet.git`，分支 `main`）

当前检出是浅克隆（`git fetch --depth 1`）。之后按移植方案第 13 节更新即可；若要完整历史，再执行 `git fetch --unshallow`。

| 日期 | 提交 | 说明 |
| --- | --- | --- |
| 2026-10-15 | `7f507e2`（`7f507e20e5b4f64cc9d51a44454ba13b13e789cd`） | 阶段 0：本地副本就地转为上游 `main`。提交说明：feat(display): 右键菜单支持切换宠物显示位置。插件版本 0.3.7。已用 Node 22.23.2 构建 `dsh-pet/runtime/electron-helper/shared-core.js`。 |
| 2026-10-15 | `7f507e2` | 阶段 2：同一检出同步渲染壳、`config.json` 与 webm/字体/图片。`sprite.js` 的同步副本把 `class PetSprite` 改成可赋值的 `var`，便于 `android-overrides.js` 换上安卓子类；上游检出本身不改。 |
