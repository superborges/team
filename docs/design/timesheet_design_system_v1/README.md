# 工时与项目成本 · 企业蓝 Design System v1.0

面向当前 MVP 的视觉规范与 Codex 落地包。风格取自已确认的蓝白稿，业务仍以 PRD v1.3 与已确认实现为准；不是企业微信官方 UI 库。

## 文件

| 文件 | 用途 |
|---|---|
| `DESIGN_SYSTEM.md` | 完整设计规范：颜色、布局、组件、状态、移动端、业务边界和验收 |
| `CODEX_HANDOFF.md` | 可直接交给 Codex 的实施说明 |
| `tokens.json` | 项目自定义 Token 数据，不宣称符合某个外部交换标准 |
| `styles/tokens.css` | 可复用 CSS 变量，统一 `--ts-` 前缀 |
| `styles/components.css` | `.ts-app` 作用域的基础组件参考样式 |
| `preview.html` | 可离线打开的页面与组件预览；字体、图标、样式均不依赖网络 |
| `references/approved-visual.png` | 用户确认的视觉方向，仅作样式参考 |
| `references/original-mvp.png` | 原始移动端 MVP |
| `references/PRD_v1.3_source.md` | 原上传 PRD 的原文副本，正文版本 v1.3 |
| `qa/QUALITY_CHECK.md` | 本设计包实际执行的检查与未覆盖范围 |

## 使用

解压后直接打开 `preview.html`。它有页面示例与组件规范，编辑、增删、保存和预览按钮仅用于演示；数据不连接业务接口，也不产生真实工时或审批。关闭页面即丢失演示输入。

把整个目录放进仓库的文档或设计目录，将以下文字发给 Codex：

> 阅读本设计包的 CODEX_HANDOFF.md 和 DESIGN_SYSTEM.md，检查当前仓库并执行企业蓝主题美化。沿用现有技术栈和组件库，保留移动端填报能力、业务校验、权限、周提交与现场日独立送审。不要把设计图的样例数字和按钮文案当成新业务需求。完成后给出修改文件、桌面/手机截图、已执行测试和剩余问题。

现有项目有主题系统时，映射 Token 即可，不需要整份覆盖参考 CSS。预览 HTML 为独立样例，不建议直接替换生产页面。
