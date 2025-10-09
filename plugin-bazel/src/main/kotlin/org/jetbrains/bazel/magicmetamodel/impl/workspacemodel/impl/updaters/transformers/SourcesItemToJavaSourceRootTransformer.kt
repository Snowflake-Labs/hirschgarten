package org.jetbrains.bazel.magicmetamodel.impl.workspacemodel.impl.updaters.transformers

import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import org.jetbrains.bazel.commons.RuleType
import org.jetbrains.bazel.sdkcompat.workspacemodel.entities.JavaSourceRoot
import org.jetbrains.bsp.protocol.BuildTarget
import org.jetbrains.bsp.protocol.RawBuildTarget

internal val JAVA_SOURCE_ROOT_TYPE = SourceRootTypeId("java-source")
internal val JAVA_TEST_SOURCE_ROOT_TYPE = SourceRootTypeId("java-test")
internal val JAVA_RESOURCE_ROOT_TYPE = SourceRootTypeId("java-resource")
internal val JAVA_TEST_RESOURCE_ROOT_TYPE = SourceRootTypeId("java-test-resource")

internal class SourcesItemToJavaSourceRootTransformer : WorkspaceModelEntityPartitionTransformer<RawBuildTarget, JavaSourceRoot> {
  private val logger = logger<SourcesItemToJavaSourceRootTransformer>()

  override fun transform(inputEntity: RawBuildTarget): List<JavaSourceRoot> {
    val rootType = inferRootType(inputEntity)

    logger.info("Transforming sources for target: ${inputEntity.id}")

    return SourceItemToSourceRootTransformer
      .transform(inputEntity.sources)
      .map { toJavaSourceRoot(it, rootType, inputEntity.id.toString()) }
  }

  private fun inferRootType(buildTarget: BuildTarget): SourceRootTypeId =
    if (buildTarget.kind.ruleType == RuleType.TEST) JAVA_TEST_SOURCE_ROOT_TYPE else JAVA_SOURCE_ROOT_TYPE

  private fun toJavaSourceRoot(sourceRoot: SourceRoot, rootType: SourceRootTypeId, targetId: String): JavaSourceRoot {
    val packagePrefix = sourceRoot.jvmPackagePrefix ?: ""

    logger.info("  Source: ${sourceRoot.sourcePath}")
    logger.info("    -> jvmPackagePrefix from BSP: ${sourceRoot.jvmPackagePrefix}")
    logger.info("    -> Final packagePrefix set: '$packagePrefix'")
    logger.info("    -> Root type: $rootType")
    logger.info("    -> Target: $targetId")

    return JavaSourceRoot(
      sourcePath = sourceRoot.sourcePath,
      generated = sourceRoot.generated,
      packagePrefix = packagePrefix,
      rootType = rootType,
    )
  }
}
