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

设备执行结果完成后在此记录。设备测试未标记通过前，不应把仅构建成功描述为完整运行验收。

## 安装包

`app/build/outputs/apk/debug/app-debug.apk` 为构建输出，忽略于 Git。交付副本为 `boc-sms-ledger-0.1.0-debug.apk`。

SHA-256：`d47e57a97626e97b2f5589c652d69b3ba6046a8c4e79faa93d1a77c7477bf6c8`。

使用忽略的 `private/debug.keystore` 调试签名。未发布 release、未合并 main、未推送远端，未读取真实手机短信或向手机安装。

## 尚未验证或实现

- 未在用户实际手机验证安装、短信权限授予/拒绝/撤销、真实发送方、模板变体、双 SIM、长短信库拼接、后台限制、强行停止/重启等行为。
- 新短信的系统 Inbox 入库延迟只能通过有界重试和下次启动补扫恢复，不能承诺厂商后台永久即时处理。
- 购汇还款与独立借记卡扣款短信的证据关联未实现，不把两个通知声称为已经去重的确认现金流；默认待确认的借记卡扣款不计消费。
- 不自动配对消费与退款/撤销，不提供备份/导出、银行账单重建、云服务或银行登录。
- 模板是严格整条匹配，不宣称适配全部中国银行通知。原文保留，未知格式可见，可手动修正。

真机验收步骤见 [development.md](development.md)。
