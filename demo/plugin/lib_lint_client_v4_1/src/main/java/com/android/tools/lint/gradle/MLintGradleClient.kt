/*
 * MIT License
 *
 * Copyright (c) 2021 tianwailaike61
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.android.tools.lint.gradle

import com.android.builder.model.AndroidProject
import com.android.repository.Revision
import com.android.tools.lint.LintCliClient
import com.android.tools.lint.LintCliFlags
import com.android.tools.lint.Warning
import com.android.tools.lint.client.api.*
import com.android.tools.lint.detector.api.*
import com.android.tools.lint.gradle.api.VariantInputs
import com.android.tools.lint.model.LintModelSeverity
import com.android.utils.XmlUtils
import com.google.common.io.Files
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.w3c.dom.Document
import java.io.File
import java.io.IOException
import java.net.URL
import java.net.URLConnection

open class MLintGradleClient(private val version: String,
                             registry: IssueRegistry,
                             flags: LintCliFlags,
                             private val gradleProject: Project,
                             private val sdkHome: File?,
                             variantName: String?,
                             private val variantInputs: VariantInputs,
                             buildToolInfoRevision: Revision?,
                             resolver: KotlinSourceFoldersResolver,
                             isAndroid: Boolean,
                             override val baselineVariantName: String?): LintCliClient(flags, CLIENT_GRADLE) {
    /** Variant to run the client on, if any  */
    private val variantName: String?
    private val buildToolInfoRevision: Revision?
    private val isAndroid: Boolean
    private val resolver: KotlinSourceFoldersResolver

    fun getKotlinSourceFolders(project: Project, variantName: String): List<File> {
        return resolver.getKotlinSourceFolders(variantName, project)
    }

    override fun getClientRevision(): String? = version

    override fun getConfiguration(
            project: com.android.tools.lint.detector.api.Project,
            driver: LintDriver?
    ): Configuration {
        val overrideConfiguration = overrideConfiguration
        if (overrideConfiguration != null) {
            return overrideConfiguration
        }

        // Look up local lint configuration for this project, either via Gradle lintOptions
        // or via local lint.xml
        val buildModel = project.buildModule
                ?: return super.getConfiguration(project, driver)
        val lintOptions = buildModel.lintOptions
        val lintXml = lintOptions.lintConfig
                ?: File(
                        project.dir,
                        DefaultConfiguration.CONFIG_FILE_NAME
                )
        val overrides = lintOptions.severityOverrides
        if (overrides == null || overrides.isEmpty()) {
            return super.getConfiguration(project, driver)
        }
        return object : CliConfiguration(lintXml, configuration, project, flags.isFatalOnly) {
            override fun getSeverity(issue: Issue): Severity {
                if (issue.suppressNames != null) {
                    return getDefaultSeverity(issue)
                }
                val severity: Severity = overrides[issue.id]?.toLintSeverity()
                        ?: return super.getSeverity(issue)
                return if (flags.isFatalOnly && severity !== Severity.FATAL)
                    Severity.IGNORE
                else
                    severity
            }
        }
    }

    private fun LintModelSeverity.toLintSeverity(): Severity {
        return when (this) {
            LintModelSeverity.FATAL -> Severity.FATAL
            LintModelSeverity.ERROR -> Severity.ERROR
            LintModelSeverity.WARNING -> Severity.WARNING
            LintModelSeverity.INFORMATIONAL -> Severity.INFORMATIONAL
            LintModelSeverity.IGNORE -> Severity.IGNORE
            LintModelSeverity.DEFAULT_ENABLED -> Severity.WARNING
        }
    }

    override fun findResource(relativePath: String): File? {
        return if (!isAndroid) {
            // Don't attempt to look up resources from an $ANDROID_HOME; may not
            // exist and those checks shouldn't apply in non-Android contexts
            null
        } else {
            super.findResource(relativePath)
        }
    }

    private fun isOffline(): Boolean {
        return gradleProject.gradle.startParameter.isOffline
    }

    override fun openConnection(url: URL): URLConnection? {
        if (isOffline()) {
            return null
        }
        return super.openConnection(url)
    }

    override fun openConnection(url: URL, timeout: Int): URLConnection? {
        if (isOffline()) {
            return null
        }
        return super.openConnection(url, timeout)
    }

    override fun closeConnection(connection: URLConnection) {
        if (isOffline()) {
            return
        }
        super.closeConnection(connection)
    }

    override fun findRuleJars(project: com.android.tools.lint.detector.api.Project): Iterable<File> {
        return variantInputs.ruleJars.asFileTree.filter { obj: File -> obj.isFile }.files
    }

    override fun createProject(
            dir: File,
            referenceDir: File
    ): com.android.tools.lint.detector.api.Project {
        // Should not be called by lint since we supply an explicit set of projects
        // to the LintRequest
        throw IllegalStateException()
    }

    override fun getSdkHome(): File? = sdkHome ?: super.getSdkHome()

    override fun getCacheDir(name: String?, create: Boolean): File? {
        var relative = AndroidProject.FD_INTERMEDIATES + File.separator + "lint-cache"
        if (name != null) {
            relative += File.separator + name
        }
        val dir = File(gradleProject.rootProject.buildDir, relative)
        return if (dir.exists() || create && dir.mkdirs()) {
            dir
        } else {
            super.getCacheDir(name, create)
        }
    }

    override fun getGradleVisitor(): GradleVisitor = GroovyGradleVisitor()

    override fun configureLintRequest(lintRequest: LintRequest) {
        val search = MProjectSearch()
        val project = search.getProject(this, gradleProject, variantName)
                ?: run {
                    lintRequest.setProjects(emptyList())
                    return
                }

        val buildModel = project.buildModule

        // If an app project has dynamic feature modules, it doesn't depend on those
        // modules; instead, the feature modules depend on the app. However, when analyzing
        // the app we should consider the feature modules too; it's not easy to run lint
        // on the set of all of them, so just make :gradlew :app:lintDebug imply including
        // the feature modules themselves, similar to how app installation will also "depend"
        // on them. We don't want to add them as dependent projects from the app project since
        // that would be a circular dependency.
        //
        // One possibility here is to pass in the feature modules as additional roots
        // in the lint request. However, that does not have the desired effect; each root
        // is treated as an independent project, with its own set of detector instances.
        //
        // Another thing I tried was creating a "join" project; a non-reporting project
        // which just depends on everything (the feature modules and the app module). But
        // that still doesn't work quite right.
        //
        // Turns out the simplest thing to do is to just merge the source sets from
        // the feature modules into the app project. This doesn't quite capture the right
        // override semantics, but is a step in the right direction for reducing
        // false positives around dynamic features.
        if (buildModel != null && !buildModel.dynamicFeatures.isEmpty()) {
            for (feature in buildModel.dynamicFeatures) {
                val rootProject = gradleProject.rootProject
                val featureProject = rootProject.findProject(feature)
                if (featureProject != null) {
                    search.getProject(this, featureProject, variantName)?.let {
                        project.mergeFolders(it)
                    }
                }
            }
        }

        lintRequest.setProjects(listOf(project))
        registerProject(project.dir, project)
        for (dependency in project.allLibraries) {
            registerProject(dependency.dir, dependency)
        }
    }

    override fun createDriver(
            registry: IssueRegistry,
            request: LintRequest
    ): LintDriver {
        val driver = super.createDriver(registry, request)
        driver.platforms = if (isAndroid) Platform.ANDROID_SET else Platform.JDK_SET
        return driver
    }

    /**
     * Run lint with the given registry, optionally fix any warnings found and return the resulting
     * warnings
     */
   fun run(registry: IssueRegistry): Pair<List<Warning>, LintBaseline?> {
        val exitCode = run(registry, emptyList())
        if (exitCode == LintCliFlags.ERRNO_CREATED_BASELINE) {
            if (continueAfterBaseLineCreated()) {
                return Pair(emptyList(), driver.baseline)
            }
            throw GradleException("Aborting build since new baseline file was created")
        }
        if (exitCode == LintCliFlags.ERRNO_APPLIED_SUGGESTIONS) {
            throw GradleException(
                    "Aborting build since sources were modified to apply quickfixes after compilation"
            )
        }
        return Pair(warnings, driver.baseline)
    }

    override fun addProgressPrinter() {
        // No progress printing from the Gradle lint task; gradle tasks
        // do not really do that, even for long-running jobs.
    }

    override fun getBuildToolsRevision(project: com.android.tools.lint.detector.api.Project): Revision? {
        return buildToolInfoRevision
    }

    override fun report(
            context: Context,
            issue: Issue,
            severity: Severity,
            location: Location,
            message: String,
            format: TextFormat,
            fix: LintFix?
    ) {
        if (issue === IssueRegistry.LINT_ERROR &&
                message.startsWith("No `.class` files were found in project")
        ) {
            // In Gradle, .class files are always generated when needed, so no need
            // to flag this (and it's erroneous on library projects)
            return
        }
        super.report(context, issue, severity, location, message, format, fix)
    }

    val mergedManifest: File?
        get() = variantInputs.mergedManifest

    override fun getMergedManifest(project: com.android.tools.lint.detector.api.Project): Document? {
        val manifest = variantInputs.mergedManifest ?: return null
        try {
            val xml = Files.asCharSource(manifest, Charsets.UTF_8).read()
            val document = XmlUtils.parseDocumentSilently(xml, true)
            if (document != null) {
                // Note for later that we'll need to resolve locations from
                // the merged manifest
                val manifestMergeReport = variantInputs.manifestMergeReport
                manifestMergeReport?.let { resolveMergeManifestSources(document, it) }
                return document
            }
        } catch (ioe: IOException) {
            log(ioe, "Could not read %1\$s", manifest)
        }
        return super.getMergedManifest(project)
    }

    init {
        this.registry = registry
        this.buildToolInfoRevision = buildToolInfoRevision
        this.resolver = resolver
        this.variantName = variantName
        this.isAndroid = isAndroid
    }
}