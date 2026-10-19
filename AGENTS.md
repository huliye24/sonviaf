# AGENTS.md — Sonviaf Repository Authority

## Product identity

**Sonviaf 是 AI 音乐管理软件。** 它服务于 AI 音乐生成之后的作品管理、声音分解和真人演唱关系。

产品主链：

```text
Upload → Work → Separate → Sing → Performance → Verify → Publish
```

`Generated is the beginning.` 生成产生源素材，不自动等于完成作品。

## Data relationship

```text
Work 1 → N Performance 1 → N Vocal Take
```

- Work 保存原作、伴奏、歌词、来源与权利元数据。
- Performance 表示某个用户对 Work 的一次演唱版本。
- Vocal Take 是用户原始资产，不得被修音、混音或渲染结果覆盖。
- 公开索引不取得用户资产所有权。

## Technical constitution

- 一个 Core，多个 Interface；不得在 Web、Studio 或 App 中创建第二套 DSP、Judge 或声音状态机。
- Separation、tuning、mixing、rendering 与 verification 必须来自同一 Core。
- 未运行的能力必须显示 `Preview`、`Pending`、`Unavailable` 或其他诚实状态。
- 不得把演示波形、静态 Stem 或前端动画声称为真实分析结果。
- 原作和所有原始 Take 必须可追溯、可导出、可删除且不被派生结果覆盖。

## Product boundary

当前授权建设：个人作品库、上传入口、分轨工作流、演唱入口、Performance/Take 管理与审听。

当前未授权：开放 UGC、公共社交、评论、排行、关注流、Marketplace、token、DAO、第二套 Core。

## Moodify migration

Moodify 是历史与兼容来源，不是 Sonviaf 的父产品。迁移必须：

1. 先记录来源、契约、测试与回滚方式；
2. 优先复用，不复制声音权威；
3. 保留历史材料中的 Moodify 名称；
4. 兼容标识逐项迁移，禁止全仓字符串替换；
5. 涉及协议、schema、数据权威或公开兼容性的变化必须先记录 Canon Change，并在需要时走 MIP。

