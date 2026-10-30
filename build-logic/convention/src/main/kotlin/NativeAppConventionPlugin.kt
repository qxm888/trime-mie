/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

@Suppress("unused")
class NativeAppConventionPlugin : NativeBaseConventionPlugin() {
    /** Prebuilt builds skip the submodules entirely, so git describe cannot run. */
    private val Project.hasPrebuilt: Boolean
        get() = file("prebuilt").exists() && providers.gradleProperty("usePrebuiltNative").orNull != "false"

    /**
     * Prebuilt builds skip the submodules, so git describe cannot run. The shipped
     * librime_jni.so carries its own version string (verified: 1.17.0) and librime
     * 1.17.0 pins deps/opencc to BYVoid/OpenCC commit 556ed224 = tag ver.1.1.9.
     */
    private val Project.prebuiltLibrimeVersion: String
        get() = providers.gradleProperty("prebuiltLibrimeVersion").orNull ?: "1.17.0"

    private val Project.prebuiltOpenccVersion: String
        get() = providers.gradleProperty("prebuiltOpenccVersion").orNull ?: "ver.1.1.9"

    private val Project.librimeVersion: String
        get() =
            if (hasPrebuilt) {
                prebuiltLibrimeVersion
            } else {
                runCmd(
                    "git -C app/src/main/jni/librime describe " +
                        "--tags --long --always --exclude=latest",
                )
            }

    private val Project.openccVersion: String
        get() =
            if (hasPrebuilt) {
                prebuiltOpenccVersion
            } else {
                runCmd(
                    "git -C app/src/main/jni/librime/deps/opencc describe --tags --long --always",
                )
            }

    override fun apply(target: Project) {
        super.apply(target)

        target.pluginManager.apply("com.android.application")

        target.extensions.configure<ApplicationExtension> {
            packaging {
                jniLibs {
                    useLegacyPackaging = true
                }
            }
            defaultConfig {
                buildConfigField("String", "LIBRIME_VERSION", "\"${target.librimeVersion}\"")
                buildConfigField("String", "OPENCC_VERSION", "\"${target.openccVersion}\"")
            }
        }
    }
}
