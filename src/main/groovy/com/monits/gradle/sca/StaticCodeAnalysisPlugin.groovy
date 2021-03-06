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
package com.monits.gradle.sca

import com.monits.gradle.sca.config.AnalysisConfigurator
import com.monits.gradle.sca.config.AndroidLintConfigurator
import com.monits.gradle.sca.config.CheckstyleConfigurator
import com.monits.gradle.sca.config.CpdConfigurator
import com.monits.gradle.sca.config.FindbugsConfigurator
import com.monits.gradle.sca.config.PmdConfigurator
import groovy.transform.CompileStatic
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.internal.IConventionAware
import org.gradle.util.GradleVersion

/**
 * Static code analysis plugin for Android and Java projects
*/
@CompileStatic
class StaticCodeAnalysisPlugin implements Plugin<Project> {
    private final static String EXTENSION_NAME = 'staticCodeAnalysis'
    private final static String DEFAULTS_LOCATION =
        'https://raw.githubusercontent.com/Monits/static-code-analysis-plugin/staging/defaults/'
    private final static String CHECKSTYLE_DEFAULT_RULES = DEFAULTS_LOCATION + 'checkstyle/checkstyle.xml'
    private final static String CHECKSTYLE_CACHE_RULES = DEFAULTS_LOCATION + 'checkstyle/checkstyle-cache.xml'
    private final static String CHECKSTYLE_BACKWARDS_RULES = DEFAULTS_LOCATION + 'checkstyle/checkstyle-6.7.xml'
    private final static String PMD_DEFAULT_RULES = DEFAULTS_LOCATION + 'pmd/pmd-6.xml'
    private final static String PMD_DEFAULT_ANDROID_RULES = DEFAULTS_LOCATION + 'pmd/pmd-android-6.xml'
    private final static String PMD_BACKWARDS_ANDROID_RULES = DEFAULTS_LOCATION + 'pmd/pmd-android.xml'
    private final static String PMD_BACKWARDS_RULES = DEFAULTS_LOCATION + 'pmd/pmd-5.1.3.xml'
    private final static String FINDBUGS_DEFAULT_SUPPRESSION_FILTER =
        DEFAULTS_LOCATION + 'findbugs/findbugs-exclusions.xml'
    private final static String FINDBUGS_DEFAULT_ANDROID_SUPPRESSION_FILTER =
        DEFAULTS_LOCATION + 'findbugs/findbugs-exclusions-android.xml'
    private final static String ANDROID_DEFAULT_RULES = DEFAULTS_LOCATION + 'android/android-lint.xml'

    private final static String CONF_COMPILE_ONLY = 'compileOnly'
    private final static String CONF_COMPILE = 'compile'
    private final static String CONF_SCACONFIG = 'scaconfig'
    private final static String CONF_SCACONFIG_MODULES = 'scaconfigModules'
    private final static String CONF_ANDROID_LINT = 'androidLint'

    private final static GradleVersion GRADLE_3_2 = GradleVersion.version('3.2')
    private final static String JAVA_PLUGIN_ID = 'java'

    private final static String TARGET_CONFIGURATION_PROPERTY = GradleVersion.current() >= GRADLE_3_2 ?
        'targetConfiguration' : 'configuration'

    private StaticCodeAnalysisExtension extension
    private Project project

    @Override
    void apply(Project project) {
        this.project = project
        extension = project.extensions.create(EXTENSION_NAME, StaticCodeAnalysisExtension)

        defineConfigurations()
        addFindbugsAnnotationDependencies()
        configureExtensionRule()

        project.afterEvaluate {
            addDepsToScaconfig CONF_COMPILE
            addDepsToScaconfig 'testCompile'
            addDepsToScaconfig 'api'
            addDepsToScaconfig 'implementation'
            addDepsToScaconfig 'testImplementation'

            // Apply Android Lint configuration
            // must be done in `afterEvaluate` for compatibility with android plugin [1.0, 1.3)
            withAndroidPlugins AndroidLintConfigurator

            if (extension.findbugs) {
                withAndroidPlugins FindbugsConfigurator
                withPlugin(JAVA_PLUGIN_ID, FindbugsConfigurator)
            }

            if (extension.checkstyle) {
                withAndroidPlugins CheckstyleConfigurator
                withPlugin(JAVA_PLUGIN_ID, CheckstyleConfigurator)
            }

            if (extension.pmd) {
                withAndroidPlugins PmdConfigurator
                withPlugin(JAVA_PLUGIN_ID, PmdConfigurator)
            }

            if (extension.cpd) {
                withAndroidPlugins CpdConfigurator
                withPlugin(JAVA_PLUGIN_ID, CpdConfigurator)
            }
        }
    }

    private void defineConfigurations() {
        // Wait until the default configuration is available
        project.configurations.matching { Configuration config -> config.name == Dependency.DEFAULT_CONFIGURATION }
            .all { Configuration config ->
                if (project.configurations.findByName(CONF_COMPILE_ONLY) == null) {
                    project.configurations.with { ConfigurationContainer cc ->
                        Configuration compileOnly = cc.create(CONF_COMPILE_ONLY) { Configuration conf ->
                            conf.description = 'Compile only dependencies'
                            conf.dependencies.all { Dependency dep ->
                                project.configurations.getByName('default').exclude group:dep.group, module:dep.name
                            }
                        }
                        cc.findByName(CONF_COMPILE).extendsFrom compileOnly
                    }
                }
            }

        project.configurations.with { ConfigurationContainer cc ->
            cc.create(CONF_SCACONFIG) { Configuration conf -> // Custom configuration for static code analysis
                conf.description = 'Configuration used for Static Code Analysis'
            }
            cc.create(CONF_SCACONFIG_MODULES) { Configuration conf -> // Custom configuration for static code analysis
                conf.description = 'Configuration used for Static Code Analysis containing only module dependencies'
            }
            cc.create(CONF_ANDROID_LINT) { Configuration conf -> // Configuration used for android linters
                conf.transitive = false
                conf.description = 'Extra Android lint rules to be used'
            }
        }
    }

    // This should be done when actually configuring Findbugs, but can't be inside an afterEvaluate
    // See: https://code.google.com/p/android/issues/detail?id=208474
    private void addFindbugsAnnotationDependencies() {
        // Wait until the configurations are available
        project.configurations.matching { Configuration config ->
            config.name in [CONF_COMPILE_ONLY, 'testCompileOnly', 'androidTestCompileOnly']
        }.all { Configuration config ->
            project.dependencies { DependencyHandler dh ->
                dh.add(config.name,
                        'com.google.code.findbugs:annotations:' + ToolVersions.findbugsVersion) { ModuleDependency d ->
                    /*
                     * This jar both includes and depends on jcip and jsr-305. One is enough
                     * See https://github.com/findbugsproject/findbugs/issues/94
                     */
                    d.transitive = false
                }
            }
        }
    }

    @SuppressWarnings('DuplicateStringLiteral')
    private void configureExtensionRule() {
        ((IConventionAware) extension).conventionMapping.with {
            map('ignoreErrors') { true }
            map('findbugs') { true }
            map('pmd') { true }
            map('checkstyle') { true }
            map('cpd') { true }
            map('androidLint') { true }
            map('checkstyleRules') {
                if (ToolVersions.isLatestCheckstyleVersion()) {
                    return CHECKSTYLE_DEFAULT_RULES
                }

                if (ToolVersions.checkstyleCacheSupported) {
                    return CHECKSTYLE_CACHE_RULES
                }

                CHECKSTYLE_BACKWARDS_RULES
            }
            map('pmdRules') {
                if (ToolVersions.latestPmdVersion) {
                    return [PMD_DEFAULT_RULES]
                }

                [PMD_BACKWARDS_RULES]
            }
            map('androidLintConfig') { ANDROID_DEFAULT_RULES }
        }

        // default suppression filter for findbugs for Java - order is important, Android plugin applies Java
        withPlugin(JAVA_PLUGIN_ID) {
            ((IConventionAware) extension).conventionMapping.with {
                map('findbugsExclude') { FINDBUGS_DEFAULT_SUPPRESSION_FILTER }
            }
        }

        // default suppression filter for findbugs for Android + PMD android rules
        withAndroidPlugins {
            ((IConventionAware) extension).conventionMapping.with {
                map('findbugsExclude') { FINDBUGS_DEFAULT_ANDROID_SUPPRESSION_FILTER }
                map('pmdRules') {
                    if (ToolVersions.latestPmdVersion) {
                        return [PMD_DEFAULT_RULES, PMD_DEFAULT_ANDROID_RULES]
                    }

                    [PMD_BACKWARDS_RULES, PMD_BACKWARDS_ANDROID_RULES]
                }
            }
        }

        extension.sourceSetConfig = project.container(RulesConfig) { String name ->
            new RulesConfig(name, extension)
        }
    }

    /**
     * Adds all dependencies except modules from given config to scaconfig.
     *
     * Modules are added to scaconfigModules, but transient dependencies are added.
     * The configuration is passed by name, and may or may not exist.
     * When created, all it's dependencies will be processed.
     *
     * @param config The config whose dependencies are to be added to scaconfig / scaconfigModules
     */
    private void addDepsToScaconfig(final String configName) {
        project.configurations.matching { Configuration config -> config.name == configName }
            .all { Configuration config -> addDepsToScaconfig(config) }
    }

    /**
     * Adds all dependencies except modules from given config to scaconfig.
     *
     * Modules are added to scaconfigModules, but transient dependencies are added.
     *
     * @param config The config whose dependencies are to be added to scaconfig / scaconfigModules
     */
    private void addDepsToScaconfig(final Configuration config) {
        // support lazy dependency configuration
        config.allDependencies.all {
            if (it in ProjectDependency) {
                project.dependencies.add(CONF_SCACONFIG_MODULES, it)

                // support lazy configuration creation
                (it as ProjectDependency).dependencyProject.configurations.all { Configuration c ->
                    // Deal with changing APIs from Gradle...
                    String targetConfiguration = it[TARGET_CONFIGURATION_PROPERTY] ?: Dependency.DEFAULT_CONFIGURATION

                    // take transitive dependencies
                    if (c.name == targetConfiguration || c.name == Dependency.ARCHIVES_CONFIGURATION) {
                        addDepsToScaconfig(c)
                    }
                }
            } else {
                project.dependencies.add(CONF_SCACONFIG, it)
            }
        }
    }

    private void withAndroidPlugins(final Class<? extends AnalysisConfigurator> configClass) {
        AnalysisConfigurator configurator = configClass.newInstance(new Object[0])
        Action<? extends Plugin> configureAction = { configurator.applyAndroidConfig(project, extension) }

        withAndroidPlugins configureAction
    }

    private void withPlugin(final String pluginId,
                            final Class<? extends AnalysisConfigurator> configClass) {
        AnalysisConfigurator  configurator = configClass.newInstance(new Object[0])
        Action<? extends Plugin> configureAction = { configurator.applyConfig(project, extension) }

        withPlugin(pluginId, configureAction)
    }

    private void withPlugin(final String pluginId, final Action<? extends Plugin> configureAction) {
        project.plugins.withId(pluginId, configureAction)
    }

    private void withAndroidPlugins(final Action<? extends Plugin> configureAction) {
        withPlugin('com.android.application', configureAction)
        withPlugin('com.android.library', configureAction)
    }
}
