/*
 * Copyright 2010-2017 Monits S.A.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.monits.gradle.sca.config

import com.monits.gradle.sca.AndroidHelper
import com.monits.gradle.sca.StaticCodeAnalysisExtension
import com.monits.gradle.sca.task.CleanupAndroidLintTask
import com.monits.gradle.sca.task.ResolveAndroidLintTask
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import org.gradle.api.DomainObjectSet
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.specs.Specs
import org.gradle.util.GradleVersion

/**
 * A configurator for Android Lint tasks.
*/
@CompileStatic
class AndroidLintConfigurator implements AnalysisConfigurator {
    private static final GradleVersion CACHEABLE_TASK_GRADLE_VERSION = GradleVersion.version('3.0')
    private static final String USE_JACK_PROPERTY_NAME = 'useJack'
    private static final String JACK_OPTIONS_PROPERTY_NAME = 'jackOptions'

    private final RemoteConfigLocator configLocator = new RemoteConfigLocator('android')

    @Override
    void applyConfig(final Project project, final StaticCodeAnalysisExtension extension) {
        // nothing to do for non-android projects
    }

    @Override
    void applyAndroidConfig(final Project project, final StaticCodeAnalysisExtension extension) {
        project.tasks.matching { Task it -> it.name == 'lint' } .all { Task t ->
            setupTasks(t, project, extension)

            configureLintTask(project, extension, t)
        }
    }

    private static void setupTasks(final Task lintTask, final Project project,
                                   final StaticCodeAnalysisExtension extension) {
        Task resolveTask = project.tasks.create('resolveAndroidLint', ResolveAndroidLintTask)
        Task cleanupTask = project.tasks.create('cleanupAndroidLint', CleanupAndroidLintTask)

        lintTask.dependsOn resolveTask
        lintTask.finalizedBy cleanupTask

        // Tasks should be skipped if disabled by extension
        lintTask.onlyIf { extension.androidLint }
        resolveTask.onlyIf { extension.androidLint }
        cleanupTask.onlyIf { extension.androidLint }
    }

    @SuppressWarnings(['NoDef', 'VariableTypeRequired']) // can't specify a type without depending on Android
    private static void configureLintOptions(final Project project, final StaticCodeAnalysisExtension extension,
                                             final File configSource, final Task lintTask) {
        def lintOptions = project['android']['lintOptions']

        lintOptions.with { it ->
            // TODO : This won't fail on warnings, just like Checkstyle.
            // See https://issues.gradle.org/browse/GRADLE-2888
            it['abortOnError'] = !extension.ignoreErrors

            // Change output location for consistency with other plugins
            it['xmlOutput'] = project.file("${project.buildDir}/reports/android/lint-results.xml")

            // Update global config
            it['lintConfig'] = configSource
        }

        // Make sure the task has the updated global config
        lintTask['lintOptions'] = lintOptions
    }

    @SuppressWarnings('CatchThrowable') // yes, we REALLY want to be that generic
    private void configureLintTask(final Project project, final StaticCodeAnalysisExtension extension,
                                    final Task lintTask) {
        File config = obtainLintRules(project, extension, lintTask)

        configureLintOptions(project, extension, config, lintTask)

        try {
            configureLintInputsAndOutputs(project, lintTask)

            // Allow to cache task result on Gradle 3+!
            if (GradleVersion.current() >= CACHEABLE_TASK_GRADLE_VERSION) {
                lintTask.outputs.cacheIf(Specs.SATISFIES_ALL)
            }
        } catch (Throwable e) {
            // Something went wrong!
            project.logger.warn('Encountered an error trying to set inputs and outputs for Android Lint ' +
                    'tasks, it will be disabled. Please, report this incident in ' +
                    'https://github.com/monits/static-code-analysis-plugin/issues', e)

            // disable up-to-date caching
            lintTask.outputs.upToDateWhen {
                false
            }
        }
    }

    private File obtainLintRules(final Project project, final StaticCodeAnalysisExtension config,
                                    final Task lintTask) {
        boolean remoteLocation = RemoteConfigLocator.isRemoteLocation(config.androidLintConfig)
        File configSource

        if (remoteLocation) {
            String downloadTaskName = 'downloadAndroidLintConfig'
            configSource = configLocator.makeDownloadFileTask(project, config.androidLintConfig,
                String.format('android-lint-%s.xml', project.name), downloadTaskName)

            lintTask.dependsOn project.tasks.findByName(downloadTaskName)
        } else {
            configSource = new File(config.androidLintConfig)
        }

        configSource
    }

    @SuppressWarnings(['NoDef', 'VariableTypeRequired']) // can't specify a type without depending on Android
    @CompileStatic(TypeCheckingMode.SKIP)
    private static void configureLintInputsAndOutputs(final Project project, final Task lintTask) {
        def lintOptions = project.android.lintOptions

        /*
         * Android doesn't define inputs nor outputs for lint tasks, so they will rerun each time.
         * This is an experimental best effort to what I believe it should look like...
         * See: https://code.google.com/p/android/issues/detail?id=209497
         */
        boolean xmlEnabled = lintOptions.xmlReport
        File xmlOutput = lintOptions.xmlOutput

        boolean htmlEnabled = lintOptions.htmlReport
        File htmlOutput = lintOptions.htmlOutput

        DomainObjectSet<?> variants = getVariants(project)

        String defaultReportVariant = null
        variants.all {
            def configuration = it.variantData.variantConfiguration
            String variantName = it.name
            String variantDirName = configuration.dirName

            lintTask.inputs.with {
                dir("${project.buildDir}/intermediates/classes/${variantDirName}/")
                dir("${project.buildDir}/intermediates/assets/${variantDirName}/")
                dir("${project.buildDir}/intermediates/manifests/full/${variantDirName}/")
                dir("${project.buildDir}/intermediates/res/merged/${variantDirName}/")
                dir("${project.buildDir}/intermediates/shaders/${variantDirName}/")
                dir("${project.buildDir}/intermediates/rs/${variantDirName}/")
            }

            if (!defaultReportVariant && configuration.buildType.isDebuggable() && !usesJack(configuration)) {
                defaultReportVariant = variantName

                addReportAsOutput(lintTask, project, xmlEnabled, xmlOutput, defaultReportVariant, 'xml')
                addReportAsOutput(lintTask, project, htmlEnabled, htmlOutput, defaultReportVariant, 'html')
            }
        }
    }

    @SuppressWarnings(['NoDef', 'MethodParameterTypeRequired']) // can't specify a type without depending on Android
    @CompileStatic(TypeCheckingMode.SKIP)
    private static boolean usesJack(final def configuration) {
        // Newer plugin versions have a merged jack config on the config
        if (configuration.hasProperty(JACK_OPTIONS_PROPERTY_NAME) && configuration.jackOptions.enabled != null) {
            return configuration.jackOptions.enabled
        }

        // Any flavors?
        if (configuration.hasFlavors()) {
            for (def pf : configuration.productFlavors) {
                if (pf.hasProperty(JACK_OPTIONS_PROPERTY_NAME) && pf.jackOptions.enabled != null) {
                    return pf.jackOptions.enabled
                }
            }
        }

        // default config?
        if (configuration.defaultConfig.hasProperty(JACK_OPTIONS_PROPERTY_NAME) &&
                configuration.defaultConfig.jackOptions.enabled != null) {
            return configuration.defaultConfig.jackOptions.enabled
        }

        // Fallback for older versions, use old property
        if (configuration.hasProperty(USE_JACK_PROPERTY_NAME) && configuration.useJack != null) {
            return configuration.useJack
        }

        if (configuration.buildType.hasProperty(USE_JACK_PROPERTY_NAME) && configuration.buildType.useJack != null) {
            return configuration.buildType.useJack
        }

        // default is false, plugin is too old or too new to know anything about jack
        false
    }

    private static DomainObjectSet<?> getVariants(final Project project) {
        if (project['android'].hasProperty('libraryVariants')) {
            return project['android']['libraryVariants'] as DomainObjectSet
        }

        project['android']['applicationVariants'] as DomainObjectSet
    }

    /*
     * The signature of TaskInputs.file(Object) changed, we need to skip @CompileStatic for backwards compatibility
     * with Gradle 2.x. Remove it once we drop support for 2.x.
     */
    @SuppressWarnings('ParameterCount')
    @CompileStatic(TypeCheckingMode.SKIP)
    private static void addReportAsOutput(final Task task, final Project project, final boolean isEnabled,
                                          final File output, final String variantName, final String extension) {
        if (isEnabled) {
            File definiteOutput = output
            if (!output) {
                // Convention naming changed along the way
                if (AndroidHelper.lintReportPerVariant(project)) {
                    definiteOutput = project.file(
                            "${project.buildDir}/outputs/lint-results-${variantName}.${extension}")
                } else {
                    definiteOutput = project.file("${project.buildDir}/outputs/lint-results.${extension}")
                }
            }
            task.outputs.file definiteOutput
        }
    }
}
