plugins {
    // P2-19：AGP 8.10 需 Gradle 8.11+，但 CI workflow 文件需 workflow scope 才能更新，
    // 暂保持 8.5.2（与 compileSdk 36 非官方推荐组合，但可构建）。待 workflow 更新后升级。
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
