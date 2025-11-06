@file:Suppress("UnstableApiUsage")

package org.jetbrains.bazel.workspace

import com.google.devtools.build.lib.query2.proto.proto2api.Build
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.toVirtualFileUrl
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.SequentialProgressReporter
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.platform.workspace.jps.entities.ContentRootEntity
import com.intellij.platform.workspace.jps.entities.DependencyScope
import com.intellij.platform.workspace.jps.entities.ModuleDependency
import com.intellij.platform.workspace.jps.entities.ModuleDependencyItem
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.platform.workspace.jps.entities.SdkDependency
import com.intellij.platform.workspace.jps.entities.SdkId
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.jps.entities.SourceRootTypeId
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.SymbolicEntityId
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.util.containers.Interner
import com.intellij.workspaceModel.ide.toPath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.bazel.commons.Language
import org.jetbrains.bazel.commons.LanguageClass
import org.jetbrains.bazel.commons.RuleType
import org.jetbrains.bazel.commons.Tag
import org.jetbrains.bazel.commons.TargetKind
import org.jetbrains.bazel.commons.phased.kind
import org.jetbrains.bazel.commons.phased.srcs
import org.jetbrains.bazel.config.BazelPluginBundle
import org.jetbrains.bazel.config.defaultJdkName
import org.jetbrains.bazel.config.isBazelProject
import org.jetbrains.bazel.config.rootDir
import org.jetbrains.bazel.config.rootDir
import org.jetbrains.bazel.coroutines.BazelCoroutineService
import org.jetbrains.bazel.info.BspTargetInfo.TargetInfo
import org.jetbrains.bazel.label.Label
import org.jetbrains.bazel.magicmetamodel.formatAsModuleName
import org.jetbrains.bazel.sdkcompat.workspacemodel.entities.BazelDummyEntitySource
import org.jetbrains.bazel.sdkcompat.workspacemodel.entities.BazelModuleEntitySource
import org.jetbrains.bazel.server.connection.connection
import org.jetbrains.bazel.sync.fromRuleName
import org.jetbrains.bazel.sync.status.SyncStatusService
import org.jetbrains.bazel.sync.workspace.BazelWorkspaceResolveService
import org.jetbrains.bazel.sync.workspace.languages.LanguagePlugin
import org.jetbrains.bazel.sync.workspace.languages.java.JavaLanguagePlugin
import org.jetbrains.bazel.sync.workspace.mapper.normal.TargetTagsResolver
import org.jetbrains.bazel.target.TargetUtils
import org.jetbrains.bazel.target.addLibraryModulePrefix
import org.jetbrains.bazel.target.moduleEntity
import org.jetbrains.bazel.target.targetUtils
import org.jetbrains.bazel.utils.SourceType
import org.jetbrains.bazel.utils.isSourceFile
import org.jetbrains.bazel.workspacecontext.WorkspaceContext
import org.jetbrains.bsp.protocol.InverseSourcesParams
import org.jetbrains.bsp.protocol.InverseSourcesResult
import org.jetbrains.bsp.protocol.RawBuildTarget
import org.jetbrains.bsp.protocol.SourceItem
import org.jetbrains.bsp.protocol.TextDocumentIdentifier
import org.jetbrains.bsp.protocol.WorkspaceBuildTargetParams
import org.jetbrains.bsp.protocol.WorkspaceBuildTargetSelector
import org.jetbrains.bsp.protocol.utils.extractJvmBuildTarget
import java.nio.file.Path

// Interners for deduplicating ModuleId and ModuleDependency objects, matching pattern in ModuleEntityUpdater
private val moduleIdInterner: Interner<SymbolicEntityId<*>> = Interner.createWeakInterner()
private val moduleDependencyInterner: Interner<ModuleDependencyItem> = Interner.createWeakInterner()

internal class AssignFileToModuleListener : BulkFileListener {
  override fun after(events: MutableList<out VFileEvent>) {
    // if the list has multiple events, a resync is required
    val event = events.singleOrNull()
    if (event != null && !ApplicationManager.getApplication().isUnitTestMode) {
      afterSingleFileEvent(event)
    }
  }

  // the returned map is for testing purposes; Project location hash is used instead of Project since it's safer to store
  @VisibleForTesting
  fun afterSingleFileEvent(event: VFileEvent): Map<String, Job> {
    val file = getAffectedFile(event) ?: return emptyMap()
    val isSource =
      if (event is VFileDeleteEvent) {
        file.extension?.let { SourceType.fromExtension(it) } != null
      } else {
        file.isSourceFile()
      }
    return if (isSource) {
      getRelatedProjects(file).associateProjectIdWithJob { processWithDelay(it, event) }
    } else {
      emptyMap()
    }
  }

  private fun processWithDelay(project: Project, event: VFileEvent): Job? {
    val controller = Controller.getInstance(project)
    if (controller.isAnotherProcessingInProgress()) return null
    val eventIsFirstInQueue = controller.addEvent(event)

    // only the first event in the queue should trigger the delayed processing
    return if (eventIsFirstInQueue) {
      BazelCoroutineService.getInstance(project).start {
        delay(PROCESSING_DELAY)

        val workspaceModel = project.serviceAsync<WorkspaceModel>()
        val event = Controller.getInstance(project).getSingleEventOrNullAndClear()
        if (event != null) {
          controller.processWithLock {
            processFileEvent(event = event, project = project, workspaceModel = workspaceModel)
          }
        }
      }
    } else {
      null
    }
  }

  @Service(Service.Level.PROJECT)
  private class Controller {
    private var moreThanOneEvent = false
    private var eventWaiting: VFileEvent? = null

    private val processingLock = Mutex()

    /** @return `true` if this event was the first to be reported in the batch, `false` otherwise */
    fun addEvent(event: VFileEvent): Boolean =
      synchronized(this) {
        if (eventWaiting == null) {
          eventWaiting = event
          true
        } else {
          moreThanOneEvent = true
          false
        }
      }

    fun getSingleEventOrNullAndClear(): VFileEvent? {
      synchronized(this) {
        val singleEvent =
          when {
            moreThanOneEvent -> null
            else -> eventWaiting
          }
        moreThanOneEvent = false
        eventWaiting = null
        return singleEvent
      }
    }

    suspend fun processWithLock(action: suspend () -> Unit) {
      try {
        processingLock.withLock(this) {
          action()
        }
      } catch (_: IllegalStateException) {
        // it means that Mutex::withLock was called with Mutex already locked - in that case, we just want not to start the processing
      }
    }

    fun isAnotherProcessingInProgress(): Boolean = processingLock.isLocked

    companion object {
      @JvmStatic
      fun getInstance(project: Project): Controller = project.service()
    }
  }
}

private fun getNewFile(event: VFileEvent): VirtualFile? = if (event !is VFileDeleteEvent) getAffectedFile(event) else null

private fun getAffectedFile(event: VFileEvent): VirtualFile? {
  val file =
    when (event) {
      is VFileCreateEvent -> event.file
      is VFileDeleteEvent -> event.file
      is VFileMoveEvent -> event.file
      is VFileCopyEvent -> event.findCreatedFile()
      is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) event.file else null
      else -> null
    }
  return if (file?.isDirectory == false) file else null
}

private fun getOldFilePath(event: VFileEvent): Path? =
  when (event) {
    is VFileCreateEvent -> null // explicit branch for code clarity
    is VFileCopyEvent -> null // explicit branch for code clarity
    is VFileDeleteEvent -> event.path
    is VFileMoveEvent -> event.oldPath
    is VFilePropertyChangeEvent -> if (event.propertyName == VirtualFile.PROP_NAME) event.oldPath else null
    else -> null
  }?.toNioPathOrNull()

fun getRelatedProjects(file: VirtualFile): List<Project> =
  ProjectManager // ProjectLocator::getProjectsForFile won't work, since it only recognises files already added to content roots
    .getInstance()
    .openProjects
    .filter {
      projectIsBazelAndContainsFile(it, file) &&
        it.hasAnyTargets() // if a project has no targets, there is no point in processing (also, it could interrupt the initial sync)
    }

private fun projectIsBazelAndContainsFile(project: Project, file: VirtualFile): Boolean {
  val rootDir =
    try {
      project.rootDir
    } catch (_: IllegalStateException) {
      return false
    }
  return project.isBazelProject && VfsUtil.isAncestor(rootDir, file, false)
}

private fun Project.hasAnyTargets(): Boolean = this.targetUtils.allTargets().any()

private fun List<Project>.associateProjectIdWithJob(action: (Project) -> Job?): Map<String, Job> =
  mapNotNull {
    val projectHash = it.locationHash
    val job = action(it)
    if (job != null) {
      projectHash to job
    } else {
      null
    }
  }.toMap()

private suspend fun processFileEvent(
  event: VFileEvent,
  project: Project,
  workspaceModel: WorkspaceModel,
) {
  val entityStorageDiff = MutableEntityStorage.from(workspaceModel.currentSnapshot)

  val newFile = getNewFile(event)
  val oldFilePath = getOldFilePath(event) // the old file must be kept as a path, since this file no longer exists

  withBackgroundProgress(project, event.getProgressMessage(newFile)) {
    reportSequentialProgress { reporter ->
      val contentRootsToRemove =
        oldFilePath?.let {
          val targetUtils = project.serviceAsync<TargetUtils>()
          processFileRemoved(
            oldFilePath = it,
            newFile = newFile,
            project = project,
            workspaceModel = workspaceModel,
            targetUtils = targetUtils,
          )
        }
      val mutableRemovalMap = contentRootsToRemove?.toMutableMap() ?: mutableMapOf()

      reporter.nextStep(PROGRESS_DELETE_STEP_SIZE)
      newFile?.let {
        processFileCreated(
          newFile = it,
          project = project,
          workspaceModel = workspaceModel,
          entityStorageDiff = entityStorageDiff,
          progressReporter = reporter,
          mutableRemovalMap = mutableRemovalMap,
        )
      }

      mutableRemovalMap.values.flatten().forEach { entityStorageDiff.removeEntity(it) }

      reporter.nextStep(endFraction = 100, text = BazelPluginBundle.message("file.change.processing.step.commit")) {
        workspaceModel.update("File changes processing (Bazel)") {
          it.applyChangesFrom(entityStorageDiff)
        }
      }
    }
  }
}

private fun VFileEvent.getProgressMessage(newFile: VirtualFile?): String =
  when (this) {
    is VFileCreateEvent -> BazelPluginBundle.message("file.change.processing.title.create", newFile?.name ?: "")
    is VFileDeleteEvent -> BazelPluginBundle.message("file.change.processing.title.delete")
    else -> BazelPluginBundle.message("file.change.processing.title.change", newFile?.name ?: "")
  }

private suspend fun processFileCreated(
  newFile: VirtualFile,
  project: Project,
  workspaceModel: WorkspaceModel,
  entityStorageDiff: MutableEntityStorage,
  progressReporter: SequentialProgressReporter,
  mutableRemovalMap: MutableMap<ModuleEntity, List<ContentRootEntity>>,
) {
  val existingModules =
    getModulesForFile(newFile, project)
      .filter { it.moduleEntity?.entitySource != BazelDummyEntitySource }
      .mapNotNull { it.moduleEntity }

  val url = newFile.toVirtualFileUrl(workspaceModel.getVirtualFileUrlManager())
  val path = url.toPath()
  val targets =
    progressReporter.nextStep(
      endFraction = PROGRESS_QUERY_STEP_SIZE,
      text = BazelPluginBundle.message("file.change.processing.step.query"),
    ) { queryTargetsForFile(project, url) } ?: return

  val modulesWithTestFlag =
    targets
      .map { it.toModuleEntity(workspaceModel.currentSnapshot, entityStorageDiff, project) }

  for ((module, isTestModule) in modulesWithTestFlag) {
    // if we want a file to be both added and removed in the same module, neither of them will be done
    val moduleContainsContentRootForRemoval = mutableRemovalMap.remove(module) != null
    val alreadyAdded = existingModules.contains(module)
    if (!moduleContainsContentRootForRemoval && !alreadyAdded) {
      url.addToModule(entityStorageDiff, module, newFile.extension, isTestModule)
    }
  }
  project.targetUtils.addFileToTargetIdEntry(path, targets)
}

suspend fun getModulesForFile(newFile: VirtualFile, project: Project): Set<Module> =
  readAction { ProjectFileIndex.getInstance(project).getModulesForFile(newFile, true) }

private fun processFileRemoved(
  oldFilePath: Path,
  newFile: VirtualFile?,
  project: Project,
  workspaceModel: WorkspaceModel,
  targetUtils: TargetUtils,
): Map<ModuleEntity, List<ContentRootEntity>> {
  val oldUrl = workspaceModel.getVirtualFileUrlManager().fromPath(oldFilePath.toString())
  val newUrl = newFile?.toVirtualFileUrl(workspaceModel.getVirtualFileUrlManager())
  val modules =
    targetUtils
      .getTargetsForPath(oldFilePath)
      .mapNotNull { it.toExistingModuleEntity(workspaceModel.currentSnapshot, project) }
  targetUtils.removeFileToTargetIdEntry(oldFilePath)
  return modules.associateWith { module ->
    // IntelliJ might have already changed the content root's path to the new one, so we need to check both
    val newUrlContentRoots = newUrl?.let { findContentRoots(module, it) } ?: emptyList()
    findContentRoots(module, oldUrl) + newUrlContentRoots
  }
}

// Helper function for cases where we only want to resolve existing modules, not create new ones
private fun Label.toExistingModuleEntity(storage: ImmutableEntityStorage, project: Project): ModuleEntity? {
  val moduleId = ModuleId(this.formatAsModuleName(project))
  return storage.resolve(moduleId)
}

private suspend fun queryTargetsForFile(project: Project, fileUrl: VirtualFileUrl): List<Label>? =
  if (!project.serviceAsync<SyncStatusService>().isSyncInProgress) {
    try {
      askForInverseSources(project, fileUrl)
        .targets
        .toList()
    } catch (ex: Exception) {
      println("queryTargetsForFile: Exception occurred: ${ex.message}")
      ex.printStackTrace()
      null
    }
  } else {
    null
  }

suspend fun askForInverseSources(project: Project, fileUrl: VirtualFileUrl): InverseSourcesResult =
  project.connection.runWithServer { bspServer ->
    bspServer
      .buildTargetInverseSources(InverseSourcesParams(TextDocumentIdentifier(fileUrl.toPath())))
  }

suspend fun Label.toModuleEntity(snapshot: ImmutableEntityStorage, storage: MutableEntityStorage, project: Project): Pair<ModuleEntity, Boolean> {
  val moduleId = ModuleId(this.formatAsModuleName(project))
  println("toModuleEntity: Processing label '$this' -> moduleId: '${moduleId.name}'")

  // First check if module exists in the mutable storage (from previous calls in this batch)
  val existingInStorage = storage.resolve(moduleId)
  if (existingInStorage != null) {
    println("toModuleEntity: Found existing module entity in mutable storage for '$this' with entitySource: ${existingInStorage.entitySource::class.simpleName}")
    // For existing modules, check if it's a test module from the cached target
    val cachedTarget = project.targetUtils.getBuildTargetForLabel(this)
    val isTestModule = (cachedTarget?.kind?.ruleType == RuleType.TEST)
    return existingInStorage to isTestModule
  }

  // Then check if module exists in the immutable snapshot
  val existingModule = snapshot.resolve(moduleId)
  if (existingModule != null) {
    println("toModuleEntity: Found existing module entity in snapshot for '$this' with entitySource: ${existingModule.entitySource::class.simpleName}")
    // For existing modules, check if it's a test module from the cached target
    val cachedTarget = project.targetUtils.getBuildTargetForLabel(this)
    val isTestModule = cachedTarget?.kind?.ruleType == RuleType.TEST
    return existingModule to isTestModule
  }

  println("toModuleEntity: No existing module found for '$this', creating new module entity")

  // Try to get build target information from TargetUtils first (for synced targets)
  val cachedTarget = project.targetUtils.getBuildTargetForLabel(this)
  var targetInfoFromPartialSync: TargetInfo? = null
  val dependencies = mutableListOf<ModuleDependencyItem>()

  println("toModuleEntity: Cached target lookup for '$this': ${if (cachedTarget != null) "FOUND (${cachedTarget::class.simpleName})" else "NOT FOUND"}")

  // If target is not in cache, trigger a partial sync to fetch it
  if (cachedTarget == null) {
    println("toModuleEntity: Target '$this' not in cache, triggering partial sync...")
    try {
      val partialSyncResult = project.connection.runWithServer { server ->
        server.workspaceBuildTargets(
          WorkspaceBuildTargetParams(
            WorkspaceBuildTargetSelector.SpecificTargets(listOf(this))
          )
        )
      }
      println("toModuleEntity: Partial sync completed for '$this', fetched ${partialSyncResult.targets.size} targets: ${partialSyncResult.targets.keys.joinToString(", ")}")

      // Extract the target info from the partial sync result
      // RawAspectTarget wraps BspTargetInfo.TargetInfo which contains the raw target data
      val rawAspectTarget = partialSyncResult.targets[this]
      if (rawAspectTarget != null) {
        targetInfoFromPartialSync = rawAspectTarget.target
        val targetInfo = targetInfoFromPartialSync
        println("toModuleEntity: Extracted target info from partial sync for '$this'")
        println("toModuleEntity: Target kind: ${targetInfo.kind}, dependencies: ${targetInfo.dependenciesCount}")

        // Add SDK Dependency
        val languages = inferLanguages(targetInfo)
        if (languages.contains(LanguageClass.JAVA)) {
          dependencies.add(SdkDependency(SdkId(project.defaultJdkName!!, "JavaSDK")))
        }

        // Transform the TargetInfo to RawBuildTarget and save to TargetUtils
        // This is needed for features like "run test" button to work
        try {
          val targetKind = inferKind(TargetTagsResolver().resolveTags(targetInfo), targetInfo.kind, languages)
          val baseDirectory = project.rootDir.toNioPath()

          // Convert dependencies from protobuf format to Label list
          val dependencies = targetInfo.dependenciesList.map { Label.parse(it.id) }

          // Convert sources from protobuf format to SourceItem list
          val sources = targetInfo.sourcesList.map { fileLocation ->
            SourceItem(
              path = baseDirectory.resolve(fileLocation.relativePath),
              generated = !fileLocation.isSource,
              jvmPackagePrefix = null
            )
          }

          // Convert resources from protobuf format
          val resources = targetInfo.resourcesList.map { fileLocation ->
            baseDirectory.resolve(fileLocation.relativePath)
          }

          // Create a minimal RawBuildTarget from the TargetInfo
          val rawBuildTarget = RawBuildTarget(
            id = this,
            tags = targetInfo.tagsList,
            dependencies = dependencies,
            kind = targetKind,
            sources = sources,
            resources = resources,
            baseDirectory = baseDirectory,
            noBuild = false,
            data = null, // Will be set by language-specific processors in full sync
            lowPrioritySharedSources = emptyList()
          )

          // Save the target to TargetUtils using setTargets (marked @TestOnly but needed here)
          project.targetUtils.addTargets(mapOf(this to rawBuildTarget))

          println("toModuleEntity: ✓ Successfully saved target '$this' to TargetUtils with ${sources.size} sources and ${dependencies.size} dependencies")
        } catch (e: Exception) {
          println("toModuleEntity: Failed to save target '$this' to TargetUtils: ${e.message}")
          e.printStackTrace()
        }
      } else {
        println("toModuleEntity: WARNING - Target '$this' not found in partial sync result")
      }
    } catch (ex: Exception) {
      println("toModuleEntity: Failed to trigger partial sync for '$this': ${ex.message}")
      ex.printStackTrace()
    }
  }

  // Determine module type based on target kind (TEST or JAVA_MODULE for non-test)
  val isTestModule = project.targetUtils.getBuildTargetForLabel(this)?.kind?.ruleType == RuleType.TEST

  // Create dependencies based on build target information if available

  if (cachedTarget != null) {
    val target = cachedTarget

    // Add module dependencies from target dependencies
    if (target is RawBuildTarget) {
      println("toModuleEntity: Processing ${target.dependencies.size} dependencies for target '$this'")
      val moduleDeps = target.dependencies.map { dependencyLabel ->
        println("toModuleEntity: Checking dependency '$dependencyLabel'")

        // Check if this dependency is a library target using TargetKind
        val depTarget = project.targetUtils.getBuildTargetForLabel(dependencyLabel)
        val isLibraryDep = depTarget?.kind?.ruleType == RuleType.LIBRARY

        println("toModuleEntity: Dependency '$dependencyLabel' - depTarget found: ${depTarget != null}, " +
                "ruleType: ${depTarget?.kind?.ruleType}, isLibrary: $isLibraryDep")

        val baseDependencyName = dependencyLabel.formatAsModuleName(project)
        // If it's a library, try to use the library module version (with prefix) if it exists
        val depModuleName = if (isLibraryDep) {
          val libraryModuleName = baseDependencyName.addLibraryModulePrefix()
          println("toModuleEntity: Checking for library module '$libraryModuleName' in workspace model")

          // Check if the library module exists in the workspace model snapshot
          val libraryModuleId = ModuleId(libraryModuleName)
          val existsInSnapshot = snapshot.resolve(libraryModuleId) != null
          val existsInStorage = storage.resolve(libraryModuleId) != null
          val libraryModuleExists = existsInSnapshot || existsInStorage

          println("toModuleEntity: Library module '$libraryModuleName' - existsInSnapshot: $existsInSnapshot, " +
                  "existsInStorage: $existsInStorage, total exists: $libraryModuleExists")

          if (libraryModuleExists) {
            println("toModuleEntity: ✓ Using library module '$libraryModuleName' for dependency '$dependencyLabel'")
            libraryModuleName
          } else {
            println("toModuleEntity: ✗ Library module '$libraryModuleName' not found, using base name '$baseDependencyName' for dependency '$dependencyLabel'")
            baseDependencyName
          }
        } else {
          println("toModuleEntity: Not a library dependency, using base name '$baseDependencyName'")
          baseDependencyName
        }

        println("toModuleEntity: Adding module dependency '$dependencyLabel' -> module: '$depModuleName' (isLibrary: $isLibraryDep)")
        // Use interners to deduplicate instances, matching ModuleEntityUpdater pattern
        moduleDependencyInterner.intern(
          ModuleDependency(
            module = moduleIdInterner.intern(ModuleId(depModuleName)) as ModuleId,
            exported = false,
            scope = if (isTestModule) DependencyScope.TEST else DependencyScope.COMPILE,
            productionOnTest = true
          )
        ) as ModuleDependency
      }
      dependencies.addAll(moduleDeps)
      println("toModuleEntity: ✓ Successfully added ${moduleDeps.size} module dependencies for target '$this': [${target.dependencies.joinToString(", ")}]")
    } else {
      println("toModuleEntity: Target '$this' is not RawBuildTarget (type: ${target::class.simpleName}), no module dependencies extracted")
    }
  } else if (targetInfoFromPartialSync != null) {
    // Extract dependencies from partial sync result
    println("toModuleEntity: Processing ${targetInfoFromPartialSync.dependenciesCount} dependencies from partial sync for target '$this'")
    val targetInfo = targetInfoFromPartialSync
    val moduleDeps = targetInfo.dependenciesList.map { dependency ->
      val dependencyLabel = Label.parse(dependency.id)
      println("toModuleEntity: [Partial Sync] Checking dependency '$dependencyLabel'")

      val baseDependencyName = dependencyLabel.formatAsModuleName(project)

      // First, check if a module with the base name exists in the snapshot
      val baseModuleId = ModuleId(baseDependencyName)
      val baseModuleExists = snapshot.resolve(baseModuleId) != null || storage.resolve(baseModuleId) != null
      println("toModuleEntity: [Partial Sync] Base module '$baseDependencyName' exists: $baseModuleExists")

      val depModuleName = if (baseModuleExists) {
        // Module exists, use it
        println("toModuleEntity: [Partial Sync] ✓ Using base module '$baseDependencyName' for dependency '$dependencyLabel'")
        baseDependencyName
      } else {
        // Module doesn't exist, check if a library module with prefix exists
        val libraryModuleName = baseDependencyName.addLibraryModulePrefix()
        val libraryModuleId = ModuleId(libraryModuleName)
        val libraryModuleExists = snapshot.resolve(libraryModuleId) != null || storage.resolve(libraryModuleId) != null

        println("toModuleEntity: [Partial Sync] Library module '$libraryModuleName' exists: $libraryModuleExists")

        if (libraryModuleExists) {
          println("toModuleEntity: [Partial Sync] ✓ Using library module '$libraryModuleName' for dependency '$dependencyLabel'")
          libraryModuleName
        } else {
          println("toModuleEntity: [Partial Sync] ✗ Neither base module nor library module found, using base name '$baseDependencyName'")
          baseDependencyName
        }
      }

      println("toModuleEntity: [Partial Sync] Adding module dependency '$dependencyLabel' -> module: '$depModuleName'")
      // Use interners to deduplicate instances, matching ModuleEntityUpdater pattern
      moduleDependencyInterner.intern(
        ModuleDependency(
          module = moduleIdInterner.intern(ModuleId(depModuleName)) as ModuleId,
          exported = true,
          scope = if (isTestModule) DependencyScope.TEST else DependencyScope.COMPILE,
          productionOnTest = true
        )
      ) as ModuleDependency
    }
    dependencies.addAll(moduleDeps)
    println("toModuleEntity: [Partial Sync] ✓ Successfully added ${moduleDeps.size} module dependencies from partial sync: [${targetInfo.dependenciesList.joinToString(", ") { it.id }}]")
  } else {
    println("toModuleEntity: No cached target or partial sync data found for '$this', creating module with empty dependencies")
  }

  // Use BazelModuleEntitySource for dynamically created modules
  // Note: We can't use the full JPS entity source logic from ModuleEntityUpdater here because
  // BazelProjectModelExternalSource is not accessible from this package due to module boundaries.
  // Dynamically created modules (added via file listener) should use BazelModuleEntitySource.
  val entitySource = BazelModuleEntitySource(moduleId.name)
  println("toModuleEntity: Using BazelModuleEntitySource for '$this'")

  val moduleEntity = ModuleEntity(
    name = moduleId.name,
    dependencies = dependencies,
    entitySource = entitySource,
  )

  val addedEntity = storage.addEntity(moduleEntity)
  println("toModuleEntity: Successfully created module entity for '$this' - name: '${moduleEntity.name}', isTestModule: $isTestModule, dependencies: ${dependencies.size}, entitySource: ${entitySource::class.simpleName}")

  return addedEntity to isTestModule
}


fun VirtualFileUrl.addToModule(
  entityStorageDiff: MutableEntityStorage,
  module: ModuleEntity,
  extension: String?,
  isTestModule: Boolean = false,
) {
  if (module.contentRoots.any { it.url == this }) return // we don't want to duplicate content roots

  // TODO: https://youtrack.jetbrains.com/issue/BAZEL-1917
  val sourceRootType =
    when (extension) {
      "java" -> if (isTestModule) SourceRootTypeId("java-test") else SourceRootTypeId("java-source")
      "kt" -> SourceRootTypeId("kotlin-source") // Kotlin uses same type for test and production
      "py" -> SourceRootTypeId("python-source") // Python uses same type for test and production
      else -> {
        println("addToModule: Bazel recognised a file as a source, but we failed to parse its extension: .$extension")
        SourceRootTypeId("unknown-source")
      }
    }

  val sourceRoot =
    SourceRootEntity(
      url = this,
      entitySource = module.entitySource,
      rootTypeId = sourceRootType,
    )

  val contentRootEntity =
    ContentRootEntity(
      url = this,
      excludedPatterns = emptyList(),
      entitySource = module.entitySource,
    ) {
      sourceRoots += listOf(sourceRoot)
    }

  entityStorageDiff.modifyModuleEntity(module) { contentRoots += contentRootEntity }
}

private fun inferKind(
  tags: Set<Tag>,
  kindString: String,
  languages: Set<LanguageClass>,
): TargetKind {
  val ruleType =
    when {
      tags.contains(Tag.TEST) -> RuleType.TEST
      tags.contains(Tag.APPLICATION) -> RuleType.BINARY
      tags.contains(Tag.LIBRARY) -> RuleType.LIBRARY
      else -> RuleType.UNKNOWN
    }
  return TargetKind(
    kindString = kindString,
    languageClasses = languages,
    ruleType = ruleType,
  )
}

// TODO: this is a another re-creation of `Language.allOfKind`. To be removed when this becomes public from upstream
private val languagesFromKinds: Map<String, Set<LanguageClass>> =
  mapOf(
    "java_library" to setOf(LanguageClass.JAVA),
    "java_binary" to setOf(LanguageClass.JAVA),
    "java_test" to setOf(LanguageClass.JAVA),
    // a workaround to register this target type as Java module in IntelliJ IDEA
    "intellij_plugin_debug_target" to setOf(LanguageClass.JAVA),
    "kt_jvm_library" to setOf(LanguageClass.JAVA, LanguageClass.KOTLIN),
    "kt_jvm_binary" to setOf(LanguageClass.JAVA, LanguageClass.KOTLIN),
    "kt_jvm_test" to setOf(LanguageClass.JAVA, LanguageClass.KOTLIN),
    "scala_library" to setOf(LanguageClass.JAVA, LanguageClass.SCALA),
    "scala_binary" to setOf(LanguageClass.JAVA, LanguageClass.SCALA),
    "scala_test" to setOf(LanguageClass.JAVA, LanguageClass.SCALA),
    // rules_jvm from IntelliJ monorepo
    "jvm_library" to setOf(LanguageClass.JAVA, LanguageClass.KOTLIN),
    "jvm_binary" to setOf(LanguageClass.JAVA, LanguageClass.KOTLIN),
    "jvm_resources" to setOf(LanguageClass.JAVA, LanguageClass.KOTLIN),
    "go_binary" to setOf(LanguageClass.GO),
    "go_test" to setOf(LanguageClass.GO),
    "go_library" to setOf(LanguageClass.GO),
    "go_source" to setOf(LanguageClass.GO),
    "py_binary" to setOf(LanguageClass.PYTHON),
    "py_test" to setOf(LanguageClass.PYTHON),
    "py_library" to setOf(LanguageClass.PYTHON),
  )

private fun inferLanguages(target: TargetInfo): Set<LanguageClass> =
  buildSet {
    // TODO It's a hack preserved from before TargetKind refactorking, to be removed
    if (target.hasJvmTargetInfo()) {
      add(LanguageClass.JAVA)
    }
    if (target.hasPythonTargetInfo()) {
      add(LanguageClass.PYTHON)
    }
    if (target.hasGoTargetInfo()) {
      add(LanguageClass.GO)
    }
    languagesFromKinds[target.kind]?.let {
      addAll(it)
    }
  }


private fun findContentRoots(module: ModuleEntity, url: VirtualFileUrl): List<ContentRootEntity> =
  module.contentRoots.filter { it.url == url }

private const val PROCESSING_DELAY = 250L // not noticeable by the user, but if there are many events simultaneously, we will get them all

private const val PROGRESS_DELETE_STEP_SIZE = 20
private const val PROGRESS_QUERY_STEP_SIZE = 80
