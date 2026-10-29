# MVP 验证记录

日期：2026-10-29。输入仅使用仓库合成短信，不含手机真实记录。此记录区分构建、自动测试和设备验收，不能替代实际手机验收。

## 已完成的构建与检查

运行环境：Linux x86_64，Temurin JDK 21.0.13+9，Gradle 8.11.1，Kotlin 2.1.20，AGP 8.9.2，Android SDK Platform 35 / Build Tools 35.0.0。

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```

- 以上四项任务均 BUILD SUCCESSFUL。
- `:core:test`：11 个 JUnit 用例，0 失败、0 错误、0 跳过。包含九条完整结构与七条边界输入的逐条断言，以及额外金额、年份、误匹配、部分/超额撤销、撤销取消关联、未确认来源、不跨币种相加等检查。
- `:app:lintDebug`：No issues found（0 错误、0 警告）。旧系统测试 runner 的弃用编译提示不属于 lint 失败。
- `:app:assembleDebugAndroidTest`：设备测试 APK 已构建，涵盖 SQLite 幂等/独立同正文记录/重开持久化、手动覆盖重解析、关联与审计、未知模板和未确认来源；另有不授权短信的演示界面 smoke test。
- `apksigner verify --verbose`：调试包签名验证通过（APK Signature Scheme v2，适用于 minSdk 26）。
- `aapt dump badging`：包名 `local.boc.ledger`，版本 0.1.0 / 1，minSdk 26、targetSdk 35；只声明 READ_SMS、RECEIVE_SMS，无 INTERNET 权限。
- Gradle distribution SHA-256 已与官方发布校验和核对，并固定在 wrapper 配置。
- `git diff --check` 通过；仓库不包含 APK、构建缓存、签名密钥或实际数据库。

## 设备测试

Android 8.0 / API 26 AOSP x86_64 模拟器（云环境无 KVM，以软件模式启动）：

```sh
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r local.boc.ledger.test/android.test.InstrumentationTestRunner
```

结果：`OK (5 tests)`，0 失败/错误，18.113 秒。具体包括：

1. 无短信权限打开演示，切回手机账本再打开，仍为 16 条，演示与手机账本隔离。
2. 同一来源幂等，不同来源的同正文各保存；关闭重开数据库仍存在。
3. 候选发送方边界和提供方来源身份稳定。
4. 关联/解除后金额变化、关联记录修正保护、人工覆盖在重解析后保留、恢复原解析和操作历史。
5. 未识别原文保留，陌生来源不统计；确认来源、排除/恢复统计及手动补全可追溯。

初次最低版本测试实际发现 SQLiteOpenHelper 在 API 26 不实现 compileSdk 35 暗示的 AutoCloseable 接口，现由 LedgerDb 显式实现 java.io.Closeable 修复；上述 5 项为修复后的完整重跑。也已人工查看 API 26 首屏，权限未授予时按钮、筛选和提示可见，无启动崩溃。

Android 15 / API 35 模拟器结果待最终执行完成后记录。

## 安装包

`app/build/outputs/apk/debug/app-debug.apk` 为构建输出，忽略于 Git。交付副本为 `boc-sms-ledger-0.1.0-debug.apk`。

SHA-256：`dc9611dacfc393f977d7d29c9d01bd5d11ed22479680010a64170ae79755f9da`。

文件大小：969,990 字节。使用忽略的 `private/debug.keystore` 调试签名。未发布 release、未合并 main、未推送远端，未读取真实手机短信或向手机安装。

## 尚未验证或实现

- 未在用户实际手机验证安装、短信权限授予/拒绝/撤销、真实发送方、模板变体、双 SIM、长短信库拼接、后台限制、强行停止/重启等行为。
- 新短信的系统 Inbox 入库延迟只能通过有界重试和下次启动补扫恢复，不能承诺厂商后台永久即时处理。
- 购汇还款与独立借记卡扣款短信的证据关联未实现，不把两个通知声称为已经去重的确认现金流；默认待确认的借记卡扣款不计消费。
- 不自动配对消费与退款/撤销，不提供备份/导出、银行账单重建、云服务或银行登录。
- 模板是严格整条匹配，不宣称适配全部中国银行通知。原文保留，未知格式可见，可手动修正。

真机验收步骤见 [development.md](development.md)。
