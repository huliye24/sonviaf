# Sonviaf Android

Sonviaf 的原生 Android 播放与个人曲库界面。对外产品名称为 Sonviaf；历史包名 `com.moodify.app` 暂时保留为升级兼容标识，避免覆盖安装时丢失本地资料。

## Local build

```powershell
cd apps\android
.\gradlew.bat :app:assembleDebug
```

当前运行能力包括 Media3 播放、公开曲库、可拖动波形时间轴、上下滑动切歌、播放模式、搜索历史、喜欢、收藏、歌单及播放状态恢复。分轨、修音、混音、验证等声音处理能力必须来自共享 Core；本 App 不实现第二套 DSP。

