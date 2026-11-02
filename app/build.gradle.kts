import java.util.Properties

plugins { id("com.android.application") }
val releaseSigningFile = rootProject.file(".private/release-signing.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
android {
    namespace = "com.dalong.dataguard"
    compileSdk { version = release(37) }
    defaultConfig { applicationId = "com.dalong.dataguard"; minSdk = 34; targetSdk = 35; versionCode = 42; versionName = "0.9.5" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*") }
    signingConfigs {
        if (releaseSigningFile.isFile) create("release") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
tasks.matching { it.name == "packageRelease" }.configureEach {
    doFirst {
        check(releaseSigningFile.isFile) {
            "生产签名未配置：请提供 .private/release-signing.properties，不允许退回调试签名。"
        }
    }
}
dependencies {
    implementation(project(":core"))
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
