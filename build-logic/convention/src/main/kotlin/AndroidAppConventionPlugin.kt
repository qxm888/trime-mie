/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

@Suppress("unused")
class AndroidAppConventionPlugin : AndroidBaseConventionPlugin() {
    override fun apply(target: Project) {
        target.pluginManager.apply("com.android.application")

        super.apply(target)

        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            // Add dependency relationships for data checksums task
            onVariants { v ->
                val variantName = v.name.replaceFirstChar { it.uppercase() }
                // Evaluation should be delayed as we need be able to see other tasks
                target.afterEvaluate {
                    tasks.findByName(DataChecksumsPlugin.TASK)?.also {
                        tasks.findByName("merge${variantName}Assets")?.dependsOn(it)
                        tasks.findByName("lintAnalyze$variantName")?.dependsOn(it)
                        tasks.findByName("lintVitalAnalyze$variantName")?.dependsOn(it)
                        tasks.findByName("generate${variantName}LintReportModel")?.dependsOn(it)
                        tasks.findByName("generate${variantName}LintVitalReportModel")?.dependsOn(it)
                    }
                }
            }
        }
    }
}
