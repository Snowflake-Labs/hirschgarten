package org.jetbrains.bazel.target.sync

import org.jetbrains.bazel.label.Label
import org.jetbrains.bazel.sync.ProjectSyncHook
import org.jetbrains.bazel.target.sync.projectStructure.targetUtilsDiff
import org.jetbrains.bazel.workspace.TESTLIB_SUFFIXES
import org.jetbrains.bsp.protocol.BuildTarget
import org.jetbrains.bsp.protocol.RawBuildTarget
import java.nio.file.Path

private class TargetUtilsSyncHook : ProjectSyncHook {
  override suspend fun onSync(environment: ProjectSyncHook.ProjectSyncHookEnvironment) {
    val bspTargets =
      environment.resolver
        .getOrFetchResolvedWorkspace()
        .targets
        .getTargets()
        .toList()
    val targetUtilsDiff = environment.diff.targetUtilsDiff
    targetUtilsDiff.bspTargets = bspTargets
    targetUtilsDiff.fileToTarget = calculateFileToTarget(bspTargets, withLowPrioritySharedSources = true)
    targetUtilsDiff.fileToTargetWithoutLowPrioritySharedSources =
      calculateFileToTarget(bspTargets, withLowPrioritySharedSources = false)
  }

  private fun calculateFileToTarget(targets: List<BuildTarget>, withLowPrioritySharedSources: Boolean): Map<Path, List<Label>> {
    val resultMap = HashMap<Path, MutableList<Label>>()
    val labelToTarget = targets.associateBy { it.id }
    val testlibToOwner = buildTestlibToOwnerMap(targets, labelToTarget)

    for (target in targets) {
      target as RawBuildTarget
      val sources =
        if (withLowPrioritySharedSources) {
          target.sources + target.lowPrioritySharedSources
        } else {
          target.sources
        }

      // Map sources to owner test target for testlibs, or to the target itself otherwise
      val targetLabel = testlibToOwner[target.id] ?: target.id
      for (source in sources) {
        val path = source.path
        resultMap.computeIfAbsent(path) { ArrayList() }.add(targetLabel)
      }
    }
    return resultMap
  }

  private fun buildTestlibToOwnerMap(targets: List<BuildTarget>, labelToTarget: Map<Label, BuildTarget>): Map<Label, Label> {
    val testlibToOwner = HashMap<Label, Label>()
    for (target in targets) {
      target as RawBuildTarget
      if (target.kind.ruleType == org.jetbrains.bazel.commons.RuleType.TEST && target.sources.isEmpty()) {
        for (suffix in TESTLIB_SUFFIXES) {
          val testlibLabel = try {
            Label.parse("${target.id}$suffix")
          } catch (_: Exception) {
            null
          }
          if (testlibLabel != null && labelToTarget.containsKey(testlibLabel)) {
            testlibToOwner[testlibLabel] = target.id
            break
          }
        }
      }
    }
    return testlibToOwner
  }
}
