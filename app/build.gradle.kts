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

val syncPetAssets = tasks.register<Exec>("syncPetAssets") {
    group = "pet"
    description = "构建 shared-core.js，并同步渲染壳、配置与素材"
    workingDir = rootDir
    commandLine("node", "tools/sync-pet-assets.mjs")
    val upstream = rootProject.file("../dsh-pet/dsh-pet")
    inputs.file(rootProject.file("tools/sync-pet-assets.mjs"))
    inputs.dir(file("src/main/pet-web-android"))
    inputs.file(upstream.resolve("assets/config.jsonc"))
    inputs.dir(upstream.resolve("assets/webm"))
    inputs.dir(upstream.resolve("assets/fonts"))
    inputs.dir(upstream.resolve("assets/pic"))
    inputs.file(upstream.resolve("scripts/build-desktop-core.mjs"))
    inputs.dir(upstream.resolve("src/shared"))
    inputs.files(
        fileTree(upstream.resolve("runtime/electron-helper")) {
            include("*.js", "*.html")
            exclude("shared-core.js")
        },
    )
    outputs.dir(file("src/main/assets/pet-web"))
    outputs.dir(file("src/main/assets/pet"))
}
tasks.named("preBuild").configure { dependsOn(syncPetAssets) }