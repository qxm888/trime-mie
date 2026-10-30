/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
@file:Suppress("UnstableApiUsage")

plugins {
    id("com.osfans.trime.app-convention")
    id("com.osfans.trime.native-app-convention")
    id("com.osfans.trime.data-checksums")
    id("com.osfans.trime.native-cache-hash")
    id("com.osfans.trime.opencc-data")
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.ksp)
}

// Explicit variant tags retain the existing experimental package for independent development.
val variantTag = providers.gradleProperty("variantTag").orNull?.trim().orEmpty()
require(variantTag.isEmpty() || variantTag.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) {
    "variantTag must start with a letter and contain only letters, numbers or underscores"
}

android {
    namespace = "com.osfans.trime"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = if (variantTag.isEmpty()) "com.osfans.trime.mie" else "com.osfans.trime"
        minSdk = 21
        targetSdk = 37
        versionCode = 20261206
        versionName = "V3.0.8-1"

        multiDexEnabled = true
        buildConfigField("String", "BUILDER", "\"${project.builder}\"")
        buildConfigField("long", "BUILD_TIMESTAMP", project.buildTimestamp)
        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${project.buildCommitHash}\"")
        buildConfigField("String", "BUILD_GIT_REPO", "\"${project.buildGitRepo}\"")
        buildConfigField("String", "BUILD_VERSION_NAME", "\"${project.buildVersionName}\"")
    }

    base {
        // https://www.norio.be/blog/archivesBaseName-removed-from-gradle9.html
        archivesName = if (variantTag.isEmpty()) "trime-mie-${android.defaultConfig.versionName}" else "trime-mie-$variantTag-${android.defaultConfig.versionName}"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        resValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig =
                project.signKeyFile?.let {
                    signingConfigs.create("release") {
                        storeFile = it
                        storePassword = project.signKeyStorePwd
                        keyAlias = project.signKeyAlias
                        keyPassword = project.signKeyPwd
                    }
                }

            resValue("string", "trime_app_name", "@string/app_name_release")
        }
        debug {
            // Named debug variants keep their original identity; release has no debug suffix.
            applicationIdSuffix = if (variantTag.isNotEmpty()) ".debug.$variantTag" else ".debug"
            resValue(
                "string", "trime_app_name",
                if (variantTag.isNotEmpty()) "@string/app_name_debug_variant" else "@string/app_name_debug",
            )
        }
        all {
            // remove META-INF/version-control-info.textproto
            @Suppress("UnstableApiUsage")
            vcsInfo.include = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Self-hosted device regressions are compiled only into development APKs.
    sourceSets.getByName("debug").java.srcDir("../script/floating-smoke")

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes +=
                setOf(
                    "/META-INF/*.version",
                    "/META-INF/*.kotlin_module", // cannot be excluded actually
                    "/META-INF/androidx/**",
                    "/DebugProbesKt.bin",
                    "/kotlin-tooling-metadata.json",
                )
        }
    }
}

aboutLibraries {
    collect {
        configPath.set(file("licenses").takeIf { it.exists() })
        fetchRemoteLicense.set(false)
        fetchRemoteFunding.set(false)
        includePlatform.set(false)
    }
    export {
        excludeFields.set(
            setOf("generated", "developers", "organization", "scm", "funding", "content"),
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val extraAbi = if (abi != null && abi != "arm64-v8a") "-$abi" else ""
            val development = if (variant.buildType == "debug") "-debug${variantTag.takeIf { it.isNotEmpty() }?.let { "-$it" }.orEmpty()}" else ""
            output.outputFileName.set("trime-mie-${android.defaultConfig.versionName!!.lowercase()}$extraAbi$development.apk")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // 内置语音识别（移植自说点啥/BiBi-Keyboard，Apache-2.0）：网络引擎与本地 sherpa-onnx
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    ksp(project(":codegen"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.material)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging.runtime.ktx)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.flexbox)
    implementation(libs.bravh)
    implementation(libs.timber)
    implementation(libs.xxpermissions)
    implementation(libs.kodein.di)
    implementation(libs.kaml)
    implementation(libs.storageaccess)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.systemservices)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    implementation(libs.iconics.core)
    implementation(libs.community.material.typeface) {
        artifact { type = "aar" }
    }

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    androidTestImplementation(libs.junit)
}
