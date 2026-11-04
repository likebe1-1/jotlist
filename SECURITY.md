# 安全策略

## 报告漏洞

Jotlist 是一个完全离线的 Android 应用，但组件导出、数据存储和构建/发布流程仍可能存在问题。
如果你发现安全漏洞，请**不要**公开提交 issue，优先使用 GitHub 的私密漏洞报告：

- <https://github.com/likebe1-1/jotlist/security/advisories/new>

请在报告中包含：受影响版本（versionCode/versionName）、复现步骤、影响说明，以及（如适用）
最小复现代码或 PoC。一般会在 7 天内确认，并在修复发布后致谢（如你愿意署名）。

## 支持范围

| 版本 | 安全更新 |
|---|---|
| 最新 release（`main` 分支构建） | ✅ |
| 更早的 release | ❌（请先升级） |

## 安全相关设计

- 不申请 `INTERNET` 权限，数据只保存在本机应用私有存储（`SharedPreferences`）。
- 已关闭 Android 自动备份（`allowBackup=false`），待办/笔记不会进入云备份或设备迁移。
- 小组件 Provider 与所有写操作接收器均为 `exported=false`；`PendingIntent` 全部显式 + `IMMUTABLE`。
- 安全审计与修复记录见 [docs/security-audit-2026-11.md](docs/security-audit-2026-11.md)。
