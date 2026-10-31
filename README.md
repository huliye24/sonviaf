# Sonviaf

Sonviaf 是面向 AI 音乐时代的音乐管理软件。

它把生成后的音频组织为可追溯的作品关系：

```text
Upload → Work → Separate → Performance → Vocal Take
```

生成是源头，不是完成。一个 Work 可以关联多个 Performance；每个 Performance 可以保留多个原始
Vocal Take。演唱版本不复制或覆盖原作、伴奏、权利来源与原始录音。

## 当前实现

- `apps/web/`：Sonviaf 产品网页与交互样机。
- `apps/android/`：可安装的 Sonviaf Android 播放与个人曲库 App，支持波形时间轴、手势切歌、顺序/随机/单曲循环、搜索历史、喜欢、收藏、歌单及播放状态恢复。
- 上传只读取浏览器本地文件名，不会上传云端。
- 分轨、录音、处理、同步与发布尚未接入，界面必须保持 `Preview` 或 `Pending`。

## 本地预览

```powershell
npm run dev
```

然后访问 `http://127.0.0.1:4173/`。

## Android 构建

需要 JDK 17 与 Android SDK：

```powershell
cd apps/android
.\gradlew.bat testDebugUnitTest assembleDebug
```

APK 输出到 `apps/android/app/build/outputs/apk/debug/app-debug.apk`。正式下载包通过 GitHub Releases 发布，构建产物不进入 Git 历史。

## 与 Moodify 的关系

Sonviaf 是新的对外产品身份，不是 Moodify 下属子产品。Moodify 仓库作为历史与兼容来源保留；
仍有效的 Core、契约和测试只允许按迁移清单逐项引入，禁止复制出第二套声音权威。

Android 当前继续使用 `com.moodify.app` 作为升级兼容标识，以保留既有设备上的喜欢、收藏、歌单和播放历史；这不是对外产品名称。

