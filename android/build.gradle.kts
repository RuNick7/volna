// AGP 9 компилирует Kotlin сам (built-in Kotlin), поэтому плагин kotlin-android не подключаем.
// Версию Kotlin задаём явно: AGP по умолчанию тянет более старый компилятор.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
