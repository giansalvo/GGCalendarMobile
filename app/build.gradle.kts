import java.time.LocalDate
import java.util.Properties
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Today's date. Gradle asks a ValueSource again at every build and, when the answer changed, throws the configuration cache away,
// so the date shown in the About card and on the splash is the day of the last build, with or without the cache.
abstract class BuildDate : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = LocalDate.now().toString()
}

// Release signing data lives outside the repository (keystore.properties is git-ignored).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.giansalvo.ggcalendar"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.giansalvo.ggcalendar"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.0.4"
        // A diagnostic build (-Pdiag=true, with --no-configuration-cache) writes log lines tagged GGCalDiag about the accounts and
        // the side menu; a normal build writes none.
        val diag = providers.gradleProperty("diag").orNull == "true"
        buildConfigField("boolean", "DIAG", diag.toString())
        if (diag) versionNameSuffix = "-diag"
        // Shown in the About card and on the splash: the day of the last build.
        manifestPlaceholders["buildDate"] = providers.of(BuildDate::class.java) {}.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { generateLocaleConfig = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:notifications"))
    implementation(project(":feature:calendar"))
    implementation(project(":feature:event"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
