import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// base URL инстанса приходит из local.properties (файл в .gitignore), чтобы адрес живого
// сервера не попадал в репозиторий. В чистом клоне подставляется placeholder из ТЗ §5.4;
// поле редактируется на экране входа, поэтому сборка без local.properties тоже рабочая.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val placeholderBaseUrl = "https://example.com:8444"
val baseUrl: String = localProperties
    .getProperty("tm.base.url")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: placeholderBaseUrl
val baseUrlLiteral: String = baseUrl.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "io.github.bigsprut.taskman"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.bigsprut.taskman"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TM_BASE_URL", "\"$baseUrlLiteral\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // Локальный стенд разработки (mock на фикстуре / свой инстанс) обычно живёт на
            // http://localhost или http://10.0.2.2 — cleartext разрешён только debug-сборке
            // отдельным манифестом src/debug. Release остаётся https-only.
            applicationIdSuffix = null
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets {
        getByName("test") {
            // Фикстура API — эталон парсера и тестов (ТЗ §2), лежит в корне репозитория.
            resources.directories.add(rootProject.file("fixtures").absolutePath)
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
    }
}

ksp {
    // Схемы Room коммитим — они нужны для миграций и ревью изменений БД.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // repeatOnLifecycle: автоповтор на экране ожидания устройства живёт только пока экран
    // виден (ТЗ §5.4 — не долбить сервер в фоне).
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
