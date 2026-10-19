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
- 已建立：独立 Canon、仓库约束、产品边界与本地启动方式。
- 暂不迁移：Moodify Core、Desktop、Android、协议、历史研究与运行数据。

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

