plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jianyu.ds_pet"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.jianyu.ds_pet"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += listOf("webm", "ttf", "png")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// 阶段 1 只需要验证页和一段透明 webm。产物在 gitignore 的 assets 目录里。
tasks.register("syncSpikeAssets") {
    group = "pet"
    description = "拷贝 spike.html 和待机呼吸休闲.webm 到 assets"
    val webm = rootProject.file("../dsh-pet/dsh-pet/assets/webm/待机呼吸休闲.webm")
    val spike = file("src/main/pet-web-android/spike.html")
    val webmOut = file("src/main/assets/pet/webm/待机呼吸休闲.webm")
    val spikeOut = file("src/main/assets/pet-web/spike.html")
    inputs.file(webm)
    inputs.file(spike)
    outputs.files(webmOut, spikeOut)
    doLast {
        if (!webm.isFile) error("找不到上游素材：${webm.absolutePath}")
        webmOut.parentFile.mkdirs()
        spikeOut.parentFile.mkdirs()
        webmOut.parentFile.listFiles()?.filter { it.extension.equals("webm", true) }?.forEach { it.delete() }
        webm.copyTo(webmOut, overwrite = true)
        spike.copyTo(spikeOut, overwrite = true)
    }
}
tasks.named("preBuild").configure { dependsOn("syncSpikeAssets") }