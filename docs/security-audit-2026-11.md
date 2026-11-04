# Jotlist 安全审计报告（2026-11）

- **审计对象**：Jotlist 5.0（`com.manst.jotlist`），基线提交 `079220b`（`main`）
- **审计日期**：2026-11-04
- **仓库**：<https://github.com/likebe1-1/jotlist>（公开）
- **方式**：三路并行静态审计（UI / 平台层 / 供应链）+ 人工复核，修复后以本地构建、单元测试、
  发布 manifest 断言和 git 历史扫描验证。审计过程只读，修复在独立分支完成。
- **结论**：**未发现 P0 / P1 级漏洞**。确认并修复 2 个 P2、9 个 P3；另有 5 项属于需要仓库所有者
  在 GitHub 设置或本机环境处理的加固建议（见「残留风险与建议」）。

## 严重级别定义

| 级别 | 含义（本应用为完全离线应用，攻击面 = 本机应用 / 系统备份 / 供应链） |
|---|---|
| P0 | 远程未授权入侵、签名密钥或账号接管、可直接利用的关键 CVE |
| P1 | 现实场景下本地攻击者可获取敏感数据或账号控制权 |
| P2 | 本地数据篡改、隐私泄露、可实际利用的加固缺口 |
| P3 | 纵深防御、最佳实践、低影响问题 |

## 修复清单（按严重级别顺序）

| ID | 级别 | 问题 | 位置 | 状态 |
|---|---|---|---|---|
| SEC-1 | P2 | 桌面小组件 Provider `exported=true`，任意本机应用可伪造 `COMPLETE_WIDGET_TODO` 广播把待办标记完成、撤销提醒、污染完成统计；伪造 `APPWIDGET_UPDATE` 大量 ids 可在主线程造成 ANR | `AndroidManifest.xml`、`TodoWidgetProvider.kt` | ✅ 已修复 |
| SEC-2 | P2 | `allowBackup=true` 且备份规则为空：待办/笔记/完成记录会进入 Android 云备份、设备迁移和 adb backup，与「数据只存在本机」定位矛盾 | `AndroidManifest.xml`、`res/xml/*.xml` | ✅ 已修复 |
| SEC-3 | P2 | CI 无最小权限 `permissions`，action 用可变 `@v4` tag，checkout 凭据留存 | `.github/workflows/android.yml` | ✅ 已修复 |
| SEC-4 | P2 | 发布构建仅在本机手工完成、无 CI 溯源 / 签名分离 | 发布流程 | ⚠️ 建议项 |
| SEC-5 | P2 | release lint 被关闭（`checkReleaseBuilds=false`），CI 也从不运行 lint；`proguard-rules.pro` 引用缺失 | `app/build.gradle.kts`、CI | ✅ 已部分修复（CI lint + 规则文件），建议项见下 |
| SEC-6 | P2 | Gradle daemon 被固定到 JDK 25 并从 `api.foojay.io` 自动下载，CI 却安装 JDK 17（也是 lint 被关掉的根因） | `gradle/gradle-daemon-jvm.properties` | ✅ 已修复 |
| SEC-7 | P2 | 生产签名私钥 `jotlist-release.jks` 与密码明文存放在本机（已 gitignore、未提交、未泄露） | 本机 | ⚠️ 建议项 |
| SEC-8 | P3 | 提醒通知 / 汇总通知未做锁屏脱敏，系统选择「显示所有通知内容」时锁屏会显示待办正文 | `Reminders.kt` | ✅ 已修复 |
| SEC-9 | P3 | 本地 JSON 解析只捕获 `JSONException`：损坏/被篡改的备份可触发 `ClassCastException`、OOM、极端时间戳死循环 | `TodoStorage.kt`、`TodoLogic.kt` | ✅ 已修复 |
| SEC-10 | P3 | 缺少安全回归测试（导出面、allowBackup、PendingIntent、存储健壮性） | 测试 / CI | ✅ 已修复 |
| SEC-11 | P3 | 导出的 `MainActivity` 会执行外部传入的导航 extras（仅改变 UI 状态，无数据读写） | `MainActivity.kt` | ⚠️ 接受（影响仅为 UI 诱导） |
| SEC-12 | P3 | 设置页跳转使用隐式 Intent，本地应用可注册同名 action 参与解析，存在仿冒设置页的钓鱼可能 | `TodoScreen.kt` | ⚠️ 接受（兼容性权衡，建议后续用显式系统 resolver） |
| SEC-13 | P3 | Gradle wrapper jar/脚本为旧版本生成物（9.0/9.1 时代），与声明的 9.6.0 存在版本偏差 | `gradle/wrapper/*`、`gradlew*` | ✅ 已修复（重新生成，校验和与官方一致） |
| SEC-14 | P3 | 无 `SECURITY.md`、无 Dependabot、无依赖更新跟踪 | `.github/` | ✅ 已修复 |
| SEC-15 | P3 | 隐私政策缺少可访问的在线 URL；`docs/index.html` 与 `release-assets/privacy-policy.html` 为重复副本 | 文档 / 仓库设置 | ⚠️ 部分修复（内容已同步），建议项 |
| SEC-16 | P3 | `org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21` 存在构建期 CVE-2026-53914（构建缓存反序列化，修复版 2.4.20-Beta1） | `gradle/libs.versions.toml` | ⚠️ 接受（构建缓存未启用，Dependabot 跟踪） |
| SEC-17 | P3 | `.gitignore` 历史注释为 GBK 字节（非法 UTF-8），缺少 `*.p12/*.pem/*.key` 等私钥模式 | `.gitignore` | ✅ 已修复 |

### 关键修复说明

**SEC-1 小组件导出面**：两个 `AppWidgetProvider` 改为 `android:exported="false"`（系统广播不受影响，
RemoteViews 的 PendingIntent 以本应用身份发送，点击照常工作，写法与 AOSP platform-samples 一致）；
「完成任务」写操作迁入新的非导出 `WidgetActionReceiver`，PendingIntent 改指向它；`appWidgetIds`
增加 100 个的防御性上限。新增仪器测试断言导出属性，CI 增加 release manifest 断言防止回退。

**SEC-2 自动备份**：`android:allowBackup="false"`，删除两个空的备份规则 XML，同步更新隐私政策
（中/英）、`docs/index.html` 与 README。代价是换机/卸载后数据不再自动恢复，已在隐私政策中说明。

**SEC-3 / SEC-6 / SEC-13 供应链**：CI 顶层 `permissions: contents: read`；checkout 关闭
`persist-credentials`；action 固定到 commit SHA（由 Dependabot 跟进）；新增 Gradle wrapper 校验
步骤；删除把 daemon 固定到 JDK 25 的 `gradle-daemon-jvm.properties`（由 CI 的 JDK 17 与本地
`JAVA_HOME` 决定），并重新生成 wrapper（jar SHA-256 与官方 9.6.0 发布值一致）。

**SEC-8 通知脱敏**：通知渠道与通知设置 `VISIBILITY_PRIVATE`，并提供不含正文的 public version；
锁屏默认只显示「Jotlist / 有一条待办提醒」，点击仍可打开任务。

**SEC-9 存储健壮性**：三个 JSON 加载器增加 8 MiB 上限并捕获 `Exception`（覆盖
`ClassCastException` 等）；可选时间戳限定在 1970–2100；`advanceUntilFuture` 增加推进上限与兜底；
新增极端 `dueAt` 单测。

## 已验证安全（无发现）

- **签名材料未泄露**：`keystore.properties`、`jotlist-release.jks`、`local.properties` 均被
  `.gitignore` 覆盖且未被跟踪；对全部本地/远程 ref、tag、reflog、不可达对象（含 7 个 amend/rebase
  旧提交）和 130 个 blob 做了字节扫描，未发现 JKS 魔数或真实密码。发布 APK 的签名证书与本机 JKS
  一致（它是生产密钥，需要保护，但没有暴露）。
- **依赖 CVE**：APK 实际携带的运行时依赖（Compose 1.7.5 / material3 1.3.1、core 1.13.1、
  activity 1.9.3、lifecycle 2.8.7、coroutines 1.7.3 等）在 OSV 上均无已知漏洞。
- **PendingIntent**：全部 11 处显式组件 + `FLAG_IMMUTABLE`，请求码/data URI 唯一，无劫持或填充风险。
- **无网络能力**：manifest 与发布 APK 均无 `INTERNET` 权限，无 WebView / JS / 第三方统计 SDK；
  `docs/index.html` 完全自包含（无脚本、表单、http 链接）。
- **其他**：debug 专用 `WidgetPreviewActivity` 不进 release；release 无 `android:debuggable`；
  内部刷新广播为包内广播 + `RECEIVER_NOT_EXPORTED`；DND/闹钟计算有边界测试；无敏感内容写入日志。

## 残留风险与建议（需要仓库所有者操作）

1. **发布密钥（P2）**：为 release keystore 设置独立于 storePassword 的 keyPassword，离线/加密保存；
   条件允许时使用 CI secrets 或硬件密钥在 CI 构建签名；不要在多台机器留存明文。
2. **发布溯源（P2）**：release APK/AAB 在 CI 用受保护的 secrets 构建，并开启 GitHub artifact
   attestation，避免本机手工打包。
3. **lint（P2）**：CI 现在在 JDK 17 上运行 `:app:lintDebug`；`checkReleaseBuilds=false` 仍保留
   （本机 JDK 25 无法跑 AGP lint），建议后续把 lint 结果纳入 release 门禁（`lintRelease`）。
4. **设置页隐式 Intent（P3）**：可改为先 `queryIntentActivities` 过滤系统应用再显式跳转，避免
   第三方应用参与解析；因不同 OEM Settings 包名差异，本次未强行修改以免破坏兼容性。
5. **导出 Activity 的导航 extras（P3）**：当前仅影响 UI 状态、不读写数据；若未来基于 extras 做数据
   写入，必须改为「非导出 trampoline + 私有 pending command」模式。
6. **在线隐私政策（P3）**：开启 GitHub Pages（或在商店后台托管）指向 `docs/index.html`，并在
   商店文案中使用绝对 URL；同时建议把 `docs/index.html` 改为由 `release-assets/privacy-policy.html`
   生成的单一来源，避免双份内容漂移。
7. **Kotlin 2.0.21 构建期 CVE（P3）**：未启用 Gradle build cache，暂不可利用；由已加入的
   Dependabot 跟踪升级。
8. **本地 git 清理（P3）**：本机 `.git` 存在 39 个不可达旧对象（amend/rebase 产物，含密钥扫描已
   确认无敏感内容），可执行 `git reflog expire --expire=now --all && git gc --prune=now` 清理；
   不影响远程仓库。
9. **仓库设置（P3）**：检查 GitHub 的 branch/tag protection、secret scanning / push protection、
   Actions 默认 token 权限（应为只读）；社区资料里的 docs URL 仍指向 `master`，应改为 `main`。

## 验证记录

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:processReleaseManifest
  :app:assembleRelease`（JDK 17，CI 同款环境，含 R8/资源压缩）— BUILD SUCCESSFUL。
- `./gradlew :app:lintDebug`（JDK 17）— BUILD SUCCESSFUL（无 error）。
- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin`
  （JDK 25 本地）— BUILD SUCCESSFUL。
- `./gradlew :app:processReleaseManifest` + `python3 scripts/verify_release_manifest.py` —
  `allowBackup=false`、三个接收器 `exported=false` 断言通过。
- `gradle-wrapper.jar` SHA-256 = `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`
  （与 gradle.org 公布的 9.6.0 wrapper 校验和一致）。
- 修复提交按 P2 → P3 顺序排列，详见 `git log --oneline`。

## 审计分工

| 范围 | 内容 |
|---|---|
| UI 层 | Compose 页面、Intent extras、Markdown 渲染、搜索、日志/剪贴板、PendingIntent 使用 |
| 平台层 | Manifest/导出组件、小组件、通知、闹钟、SharedPreferences、备份规则、release manifest |
| 供应链 | 密钥与 git 历史、依赖 CVE（OSV）、CI/action 固定、wrapper 完整性、文档隐私声明一致性 |
