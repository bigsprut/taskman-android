// Корневой build-скрипт.
//
// AGP 9 включает built-in Kotlin: отдельный плагин kotlin-android не применяется, а версия
// компилятора берётся с buildscript-классpath. KGP здесь пинуется явно, чтобы версии
// compiler-плагинов (Compose, kotlinx.serialization) и KSP были зафиксированы и совпадали —
// см. gradle/libs.versions.toml.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
