# Migration from Moodify

## Decision

`CANON_CHANGE = YES`

Sonviaf 是新的对外产品身份和独立产品区域。Moodify 不再作为 Sonviaf 的产品容器；它保留为历史、
证据与兼容实现来源。

## Why

- Moodify 域名不可用。
- 在 Moodify 仓库继续扩建 Sonviaf 会制造父子产品错觉和双重品牌权威。
- Sonviaf 需要围绕 AI 音乐管理、分轨与演唱关系建立独立的信息架构和发布生命周期。

## Initial migration

- 已迁移：Sonviaf 产品网页与交互样机。
- 已迁移：Android Listener Interface（2026-10-31，来源仓库 `huliye24/moodify-ai` 本地分支 `codex/rongjing-music-android`，基线提交 `b1e4b3fdf70294ac2365f7bf169d0bd41dca4f44` 加同日未发布工作树）。
- 已建立：独立 Canon、仓库约束、产品边界与本地启动方式。
- 暂不迁移：Moodify Core、Desktop、协议、历史研究与运行数据。

### Android migration contract

- **复用范围：** `apps/android/` 的 Gradle 工程、Media3 播放、个人本地资料、曲库客户端、测试和资源。
- **兼容标识：** 暂时保留 `applicationId` / namespace / Kotlin package `com.moodify.app`、既有 SharedPreferences 键与 API 兼容名称；禁止用全仓替换迁移这些标识。
- **产品身份：** launcher label、启动器图标及当前产品表面使用 Sonviaf；荣景音乐保留为音乐品牌和曲库署名；历史 Moodify 资料不重写。
- **声音权威：** App 只负责播放与审听，不新增 DSP、Judge 或处理状态机。
- **验证：** `testDebugUnitTest` 共 51 项通过，`assembleDebug` 通过，Android 真机覆盖安装、冷启动、播放状态恢复通过。
- **数据迁移：** 无 schema 迁移；保持原包名以继承设备本地喜欢、收藏、歌单、历史和当前曲目。
- **回滚：** 回退 Android 迁移提交并重新安装上一兼容 APK；不得卸载或清除应用数据。

## Future migration gate

每个迁移包必须记录：来源路径、当前权威、依赖、契约、测试、数据迁移、兼容期和回滚方法。

禁止：

- 全仓将 `Moodify` 替换为 `Sonviaf`；
- 复制 Core 后各自演进；
- 修改历史证据中的旧品牌；
- 在没有真实运行回执时宣布分轨、处理、同步或发布已上线。

## Rollback

网页可以回退，品牌兼容映射可以延长；任何回滚均不得删除或重写 Work、Performance、Take、来源证明
及历史证据。

