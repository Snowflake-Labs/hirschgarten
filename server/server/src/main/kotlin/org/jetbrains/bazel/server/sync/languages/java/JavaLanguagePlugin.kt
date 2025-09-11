package org.jetbrains.bazel.server.sync.languages.java

import org.jetbrains.bazel.info.BspTargetInfo.JvmTargetInfo
import org.jetbrains.bazel.info.BspTargetInfo.TargetInfo
import org.jetbrains.bazel.server.dependencygraph.DependencyGraph
import org.jetbrains.bazel.server.label.label
import org.jetbrains.bazel.server.paths.BazelPathsResolver
import org.jetbrains.bazel.server.sync.languages.JVMLanguagePluginParser
import org.jetbrains.bazel.server.sync.languages.LanguagePlugin
import org.jetbrains.bazel.workspacecontext.WorkspaceContext
import org.jetbrains.bsp.protocol.JvmBuildTarget
import org.jetbrains.bsp.protocol.RawBuildTarget
import java.nio.file.Path
import kotlin.io.path.exists

class JavaLanguagePlugin(
  private val bazelPathsResolver: BazelPathsResolver,
  private val jdkResolver: JdkResolver,
) : LanguagePlugin<JavaModule>() {
  private var jdk: Jdk? = null

  override fun prepareSync(targets: Sequence<TargetInfo>, workspaceContext: WorkspaceContext) {
    val ideJavaHomeOverride = workspaceContext.ideJavaHomeOverrideSpec.value
    jdk = ideJavaHomeOverride?.let { Jdk(version = "ideJavaHomeOverride", javaHome = it) } ?: jdkResolver.resolve(targets)
  }

  override fun resolveModule(targetInfo: TargetInfo): JavaModule? =
    targetInfo.takeIf(TargetInfo::hasJvmTargetInfo)?.jvmTargetInfo?.run {
      if (jarsCount == 0) return@run null
      val mainOutput = bazelPathsResolver.resolve(getJars(0).getBinaryJars(0))
      val binaryOutputs = jarsList.flatMap { it.binaryJarsList }.map(bazelPathsResolver::resolve)
      val mainClass = getMainClass(this)
      val runtimeJdk = jdkResolver.resolveJdk(targetInfo)

      JavaModule(
        jdk,
        runtimeJdk,
        javacOptsList,
        jvmFlagsList,
        mainOutput,
        binaryOutputs,
        mainClass,
        argsList,
      )
    }

  override fun calculateJvmPackagePrefix(source: Path): String? = JVMLanguagePluginParser.calculateJVMSourceRootAndAdditionalData(source)

  private fun getMainClass(jvmTargetInfo: JvmTargetInfo): String? = jvmTargetInfo.mainClass.takeUnless { jvmTargetInfo.mainClass.isBlank() }

  override fun dependencySources(targetInfo: TargetInfo, dependencyGraph: DependencyGraph): Set<Path> {
    // For sharded Java libraries, include sources from reverse dependencies (umbrella targets)
    // that contain all the source code from the shards
    println("DEBUG: dependencySources called for target: ${targetInfo.id}")
    val umbrellaTargets = dependencyGraph.getSourcesFromReverseDependencies(targetInfo.label())
    println("DEBUG: Found ${umbrellaTargets.size} umbrella targets for ${targetInfo.id}")

    val sources = umbrellaTargets
      .flatMap { umbrellaTarget ->
        println("DEBUG: Processing umbrella target: ${umbrellaTarget.id}")
        // Include sources from umbrella targets that depend on this shard
        umbrellaTarget.sourcesList.map { bazelPathsResolver.resolve(it) } +
        umbrellaTarget.generatedSourcesList
          .filter { !it.relativePath.endsWith(".srcjar") }
          .map { bazelPathsResolver.resolve(it) }
      }
      .filter { it.exists() && (it.toString().endsWith(".java") || it.toString().endsWith(".kt")) }
      .toSet()

    println("DEBUG: Returning ${sources.size} sources for ${targetInfo.id}")
    sources.forEach { println("DEBUG: Source: $it") }
    return sources
  }

  override fun applyModuleData(moduleData: JavaModule, buildTarget: RawBuildTarget) {
    val jvmBuildTarget = toJvmBuildTarget(moduleData)
    buildTarget.data = jvmBuildTarget
  }

  private fun javaVersionFromJavacOpts(javacOpts: List<String>): String? =
    javacOpts.firstNotNullOfOrNull {
      val flagName = it.substringBefore(' ')
      val argument = it.substringAfter(' ')
      if (flagName == "-target" || flagName == "--target" || flagName == "--release") argument else null
    }

  fun toJvmBuildTarget(javaModule: JavaModule): JvmBuildTarget? {
    val jdk = javaModule.jdk ?: return null
    val javaHome = jdk.javaHome ?: return null
    return JvmBuildTarget(
      javaVersion = javaVersionFromJavacOpts(javaModule.javacOpts) ?: jdk.version,
      javaHome = javaHome,
    )
  }
}
