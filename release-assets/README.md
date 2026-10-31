# Jotlist 发布素材

生成日期：2026-10-30 · 对应版本 5.0（versionCode 5）

## 文件清单

| 文件 | 规格 | 用途 |
|---|---|---|
| `icon-512.png` | 512×512 PNG | Play / 国内商店的应用图标 |
| `feature-1024x500.png` | 1024×500 PNG | Play 特色图片（Feature graphic） |
| `screenshots/01-待办.png` | 1344×2688（2:1） | 商店截图 1 |
| `screenshots/02-任务编辑.png` | 1344×2688 | 商店截图 2 |
| `screenshots/03-完成统计.png` | 1344×2688 | 商店截图 3 |
| `screenshots/04-笔记列表.png` | 1344×2688 | 商店截图 4 |
| `screenshots/05-笔记详情.png` | 1344×2688 | 商店截图 5 |
| `screenshots/06-设置.png` | 1344×2688 | 商店截图 6 |
| `privacy-policy.md` | 中英双语 | 隐私政策源文本 |
| `privacy-policy.html` | 单页，自适应深色模式 | 直接托管后作为隐私政策 URL |
| `store-listing.md` | 中英双语 | 商店标题、简短说明、完整说明、更新说明、关键词 |

## 截图规格说明

- Play 要求：最短边 ≥ 320px，最长边 ≤ 3840px，**宽高比不超过 2:1**。
- 原始截图是 1344×2992（比例 2.23:1，会被拒），已裁成 **1344×2688（正好 2:1）**，去掉了状态栏和底部系统导航。

## 隐私政策怎么变成 URL

Play 必须填一个可公开访问的链接。三种免费做法：

1. **GitHub Pages**：把 `privacy-policy.html` 改名 `index.html`，推到仓库，Settings → Pages 开启，得到 `https://<用户名>.github.io/<仓库>/`。
2. **Gist + htmlpreview**：上传到 gist，用 `https://htmlpreview.github.io/?<gist raw 链接>`。
3. **Notion / 语雀**：直接把 `privacy-policy.md` 粘进去，发布为网页。

## 上架填写对照表（Play Console）

| 表单字段 | 填什么 |
|---|---|
| 应用名称 | Jotlist |
| 简短说明（≤80 字符） | 极简待办与笔记。重复任务每天自动重置，所有数据只存在你的手机里。 |
| 完整说明 | `store-listing.md` 的「完整说明」段落 |
| 应用图标 | `icon-512.png` |
| 特色图片 | `feature-1024x500.png` |
| 手机截图 | `screenshots/` 里的 6 张（顺序 01→06） |
| 隐私政策 | 托管后的 URL |
| 数据安全 → 数据收集 | 选择「不收集任何数据」 |
| 数据安全 → 数据传输 | 否 |
| 广告 | 不含广告 |
| 内容分级 | 问卷全部选「否」，会得到「所有人」 |
| 目标受众 | 13 岁以上（应用有任务管理类内容，但无敏感内容） |
| 应用类别 | 效率（Productivity） |
| 定价 | 免费 |
| 支持系统 | minSdk 26（Android 8.0） |

## 还剩什么要你本人做

- **软件著作权登记**（国内大厂商店必需，Play 不需要）
- 开发者账号注册与实名（Play $25 / 国内各商店免费但需认证）
- 支付/税务信息
