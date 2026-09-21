# 黑耳钉（heiErDing）

基于 Hchat 源码派生的独立 LSPosed 模块，包名 h.heiErDing，应用名「黑耳钉」。

仅保留两个插件功能，均以模块内原生 Feature 开关形式实现（不依赖 BeanShell 脚本运行时）：

1. 预设骰子 / 猜拳顺序（插件源：F2）— 按 F2 插件原逻辑实现
2. 主页负一屏（插件源：F3）— 按 F3 插件原逻辑实现

设置页菜单结构保留。

## 一、环境要求

| 组件 | 版本 |
| --- | --- |
| JDK | 21（必需） |
| Android SDK Platform | android-37（不可用则回退 android-36） |
| Android Build Tools | 37.0.0（不可用则回退 36.0.0） |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.20 |

minSdk 27 / targetSdk 37 / compileSdk 37；仅支持 arm64-v8a、armeabi-v7a。

## 二、本地编译

    cp local.properties.example local.properties
    # 编辑 local.properties 填入 sdk.dir
    ./gradlew assembleRelease

产物：dist/heiErDing-release.apk（由 copyToDist 任务自动复制改名）。

## 三、可选环境变量

| 变量 | 作用 |
| --- | --- |
| HED_VERSION_CODE | versionCode，默认 1 |
| HED_VERSION_NAME | versionName，默认 1.0.0 |
| HED_APK_NAME | 产物文件名，默认 heiErDing-release.apk |
| HED_STORE_PASSWORD | release keystore 密码 |
| HED_KEY_ALIAS | release 密钥别名 |
| HED_KEY_PASSWORD | release 密钥密码 |

## 四、签名

若 keystore/release.jks 存在且三个 HED_ 签名变量均已设置，则用 release 签名；
否则自动回退 debug 签名（仍可安装、可用于调试验证）。

## 五、GitHub Actions 自动编译

工作流文件：.github/workflows/build.yml
推送代码或在 Actions 页手动触发 workflow_dispatch 即可自动编译，
产物以 Artifact「heiErDing-release」上传（取 dist/*.apk）。

## 六、使用

1. 安装 APK；
2. 在 LSPosed 中启用本模块，勾选作用域微信（com.tencent.mm）；
3. 重启微信；
4. 在模块设置页按需开启两个功能开关。
