# Jotlist 隐私政策

**应用名称**：Jotlist
**包名**：com.manst.jotlist
**生效日期**：2026 年 10 月 30 日

## 一句话概括

Jotlist 不收集、不上传、不共享任何个人数据。所有待办、笔记和设置都只保存在你自己的设备上。

## 我们收集哪些数据

**不收集任何数据。**

- 应用没有申请网络权限（`INTERNET`），从系统层面就无法向外发送数据。
- 待办、子任务、笔记、完成记录、免打扰设置等，全部保存在设备本地的应用私有存储中（Android `SharedPreferences`）。
- 没有账号系统，不需要登录，也不会上传通讯录、位置、设备标识等信息。
- 没有接入任何第三方统计、广告或数据分析 SDK。

## 申请的权限及用途

| 权限 | 用途 | 是否必须 |
|---|---|---|
| `POST_NOTIFICATIONS` | 在任务到期时发送提醒通知 | 可选，拒绝后应用仍可正常使用，只是不会收到提醒 |
| `SCHEDULE_EXACT_ALARM` | 让提醒准点触发（Android 12 及以上） | 可选，未授权时系统可能延迟通知 |
| `RECEIVE_BOOT_COMPLETED` | 设备重启后恢复已设置的提醒 | 可选 |

以上权限都不会用于收集数据，仅用于在本机实现提醒功能。

## 数据如何删除

- **删除单个任务或笔记**：在应用内左滑/长按删除。
- **清空已完成**：设置 → 清空已完成。
- **删除全部数据**：卸载应用，所有本地数据随应用一起被系统删除。

我们无法远程删除你的数据，因为我们的服务器上从未有过这些数据。

## 系统备份

如果你在 Android 系统中开启了"应用数据备份"，系统可能把本应用的数据纳入备份并保存到你自己的云端账户。这份备份由 Google 或你的设备厂商管理，不经过我们，我们也无法访问。

## 儿童隐私

本应用不面向 13 岁以下的儿童，也不会有意收集儿童信息（事实上不收集任何人的信息）。

## 政策变更

如果未来版本引入需要联网的功能（例如云同步），我们会在应用内和商店页面明确说明，并在更新本政策后才生效。

## 联系方式

如有疑问，可通过应用商店页面的开发者联系方式与我们联络。

---

# Jotlist Privacy Policy

**App**: Jotlist
**Package**: com.manst.jotlist
**Effective date**: October 30, 2026

## Summary

Jotlist does not collect, transmit, or share any personal data. Everything you create stays on your own device.

## What we collect

**Nothing.**

- The app does not request the `INTERNET` permission, so it cannot send data anywhere.
- Tasks, subtasks, notes, completion history and settings are stored locally in the app's private storage (`SharedPreferences`).
- There is no account system, no login, and no upload of contacts, location, or device identifiers.
- No third-party analytics, advertising, or tracking SDKs are included.

## Permissions and why they are needed

| Permission | Purpose | Required |
|---|---|---|
| `POST_NOTIFICATIONS` | Show a reminder when a task is due | Optional — the app still works without it |
| `SCHEDULE_EXACT_ALARM` | Deliver reminders on time (Android 12+) | Optional — reminders may be delayed without it |
| `RECEIVE_BOOT_COMPLETED` | Restore scheduled reminders after a reboot | Optional |

None of these permissions are used to collect data.

## Deleting your data

- Delete individual tasks or notes inside the app.
- Settings → Clear completed.
- Uninstall the app — the system removes all locally stored data.

We cannot delete your data remotely because we never receive it.

## System backup

If you have Android's app data backup enabled, the system may include this app's data in a backup stored in your own cloud account. That backup is managed by Google or your device manufacturer, not by us.

## Children's privacy

The app is not directed to children under 13 and does not knowingly collect information from anyone.

## Changes

If a future version adds features that require network access (for example cloud sync), we will describe them in the app and on the store listing, and update this policy before they take effect.

## Contact

Please use the developer contact information listed on the app's store page.
