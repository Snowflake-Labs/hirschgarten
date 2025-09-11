package org.jetbrains.bazel.magicmetamodel.impl.workspacemodel.impl.updaters.transformers

import org.jetbrains.bazel.label.Label
import org.jetbrains.bazel.magicmetamodel.ProjectDetails
import org.jetbrains.bazel.magicmetamodel.impl.workspacemodel.ModuleDetails

class ProjectDetailsToModuleDetailsTransformer(private val projectDetails: ProjectDetails, private val libraryGraph: LibraryGraph) {
  private val targetsIndex = projectDetails.targets.associateBy { it.id }
  private val javacOptionsIndex = projectDetails.javacOptions.associateBy { it.target }
  private val jvmBinaryJarsIndex = projectDetails.jvmBinaryJars.groupBy { it.target }

  fun moduleDetailsForTargetId(targetId: Label): ModuleDetails {
    val target = targetsIndex[targetId] ?: error("Cannot find target for target id: $targetId.")
    val allDependencies = libraryGraph.calculateAllDependencies(target)
    val sourceDependencies = projectDetails.targetSourceDependencies[targetId] ?: emptySet()
    // Use println for transformer since it doesn't have access to logger infrastructure
    println("DEBUG TRANSFORMER: Target $targetId has ${sourceDependencies.size} source dependencies")
    sourceDependencies.forEach { println("DEBUG TRANSFORMER: Source dependency: $it") }
    return ModuleDetails(
      target = target,
      javacOptions = javacOptionsIndex[targetId],
      libraryDependencies = allDependencies.libraryDependencies.takeIf { projectDetails.libraries != null }?.toList(),
      moduleDependencies = allDependencies.moduleDependencies.toList(),
      defaultJdkName = projectDetails.defaultJdkName,
      jvmBinaryJars = jvmBinaryJarsIndex[targetId].orEmpty(),
      sourceDependencies = sourceDependencies,
    )
  }
}
