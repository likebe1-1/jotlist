# 提醒链路验证记录

一次手动端到端验证，用来确认「到点提醒」这条核心承诺真的成立。自动化单测覆盖了
`shiftOutsideDoNotDisturb`（免打扰平移）和 `resetIfDue`（重复任务复位），但闹钟调度、
系统通知、开机恢复这一段只能在设备上验证。

设备：Pixel_8_Pro 模拟器（Android 14，Google APIs），debug 包。

## 已验证

| # | 场景 | 操作 | 结果 |
|---|---|---|---|
| 1 | 排定 | 新建任务 → 选「今天 18:00」→ 保存 | `dumpsys alarm` 出现 `RTC_WAKEUP … com.manst.jotlist`，`origWhen` 与 18:00 一致 ✅ |
| 2 | 到点触发 | 把设备时间拨到 17:58 后重启 App（冷启动会重排提醒），等待自然走到 18:00 | 通知出现在通知栏：标题「待办事项到期」、正文任务名、三个操作「完成 / 10分钟后 / 明天」 ✅ |
| 3 | 冷启动重排 | `am force-stop` 后重新打开 App | 未完成任务的提醒会重新排定，闹钟在到点前重新出现 ✅ |
| 4 | 稍后提醒 | 点通知上的「10分钟后」 | 通知被撤销，新闹钟排在点击后 10 分钟 ✅ |
| 5 | 开机恢复 | `adb reboot` 重启模拟器 | 开机后 `RTC_WAKEUP` 闹钟恢复（`origWhen` 不变），App 进程被 `BOOT_COMPLETED` 拉起 ✅ |

## 未在设备上点过的部分

- 通知「完成」「明天」两个操作（与「10分钟后」共用同一套 `SnoozeReminderReceiver` 分支，逻辑同源）
- 免打扰时段内提醒顺延（纯逻辑有 5 个单测覆盖，见 `TodoLogicTest`）
- 精准提醒权限被拒时的降级路径（代码里 `canScheduleExactAlarms` 为假时走 `setAndAllowWhileIdle`）

## 一个系统行为（不是 App bug）

用 `adb shell date` 把设备时钟往前拨，**不会**触发已排定的 `RTC_WAKEUP` 闹钟——
AlarmManager 以 elapsed time 记录，时钟跳变不保证补发。因此验证「到点触发」必须
先把时钟拨到目标前 1–2 分钟，再让真实时间自然走到目标点（本记录的场景 2）。

## 复现命令

```bash
# 排定后查看本应用的闹钟
adb shell dumpsys alarm | grep -A1 jotlist

# 到点后查看通知
adb shell dumpsys notification --noredact | grep -A6 'pkg=com.manst.jotlist'

# 把时钟拨到目标前两分钟（需要 root）
adb root
adb shell su 0 date 111017582026.00   # MMDDhhmmYYYY.ss
```
