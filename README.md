# DS-pet

[dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 的安卓移植。桌宠画在其他应用上面，可以待机、点击、拖拽、甩抛、摇一摇、长按点播，也能同时放多只。填入自己的 DeepSeek API Key 之后，才可以查看余额、听碎碎念和对话。

本应用免费、无广告、不收费。需要 Android 11（API 30）及以上。

## 许可

代码采用 MIT 许可，版权归原作者 PC2005-cloud（2026）。全文在 [LICENSE](LICENSE)。

动画、提示词和源视频允许开源使用，禁止商用。要收费或加广告，必须先取得原作者书面授权。介绍和分发页请保留原作者地址：<https://github.com/PC2005-cloud/dsh-pet>

## 构建

宠物网页和素材不入库。Gradle 在编译前运行 `tools/sync-pet-assets.mjs`，从旁边的上游检出复制渲染壳、配置和 webm。上游目录必须是本仓库的 `../dsh-pet/dsh-pet`。

当前对照的上游提交记在 [UPSTREAM.md](UPSTREAM.md)（`7f507e2`，插件 0.3.7）。

```text
parent/
├─ DS-pet/                 本仓库
└─ dsh-pet/                https://github.com/PC2005-cloud/dsh-pet
   └─ dsh-pet/             插件目录，内有 package.json 和 assets/
```

需要 JDK 17 或更高（本工程用 JDK 21 构建）、Android SDK，以及 Node.js 22.12 或更高。同步脚本要用来构建 `shared-core.js` 的 `rolldown`，所以要先在插件目录安装依赖。

```sh
git clone https://github.com/PC2005-cloud/dsh-pet.git
cd dsh-pet
git checkout 7f507e20e5b4f64cc9d51a44454ba13b13e789cd
cd dsh-pet
npm install --ignore-scripts
```

回到本仓库：

```sh
# Windows
gradlew.bat :app:assembleDebug

# macOS / Linux
./gradlew :app:assembleDebug
```

调试包在 `app/build/outputs/apk/debug/app-debug.apk`。装到手机后，在应用里打开悬浮窗和通知权限，桌宠才会留在屏幕上。

单元测试：

```sh
gradlew.bat :app:testDebugUnitTest
```

## 自己签名的发布包

发布密钥只留在本机。复制 `keystore.properties.example` 为 `keystore.properties`，按里面的说明填写密钥库路径和口令。这两份都已忽略，不要提交。

没有 `keystore.properties` 时，`assembleRelease` 会直接失败，避免打出未签名的包。

```sh
gradlew.bat :app:assembleRelease
```

产物在 `app/build/outputs/apk/release/app-release.apk`。密钥库丢失后，就不能用同一把钥匙更新已经装出去的包。

## 手机上会碰到的权限

悬浮窗用来把桌宠画在别的应用上。通知用来保持前台服务，通知里可以暂停、隐藏和退出。灭屏时动画暂停，亮屏继续。电量低于 20% 且「低电量放慢」开着时，每段动画播完会多停一会儿。

日历是可选项，只在本机读取中国节假日，用来区分余额档位的高峰和空闲，内容不会上传。

联网也是可选项。只有保存了 DeepSeek API Key 之后，请求才会发往 `https://api.deepseek.com`。密钥用 Android Keystore 加密后放在本机。

隐私说明见 [PRIVACY.md](PRIVACY.md)。

## 这只移植没有做的事

手机上没有 DeepSeek Harness，所以不做它的工作状态动画、会话通知，也没有「打开 DeepSeek Harness」。这些能力留在上游插件里。
