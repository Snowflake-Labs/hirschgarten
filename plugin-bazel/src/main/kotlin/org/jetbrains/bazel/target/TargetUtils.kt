@file:Suppress("ReplacePutWithAssignment", "ReplaceGetOrSet")

package org.jetbrains.bazel.target

import com.intellij.configurationStore.SettingsSavingComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.getProjectDataPath
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.util.awaitCancellationAndInvoke
import com.intellij.util.concurrency.SynchronizedClearableLazy
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.workspaceModel.ide.impl.legacyBridge.module.findModuleEntity
import com.intellij.workspaceModel.ide.legacyBridge.ModuleBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.TestOnly
import org.jetbrains.bazel.annotations.InternalApi
import org.jetbrains.bazel.annotations.PublicApi
import org.jetbrains.bazel.commons.RuleType
import org.jetbrains.bazel.label.Label
import org.jetbrains.bazel.magicmetamodel.formatAsModuleName
import org.jetbrains.bsp.protocol.BuildTarget
import org.jetbrains.bsp.protocol.LibraryItem
import org.jetbrains.bsp.protocol.RawBuildTarget
import java.nio.file.Path
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val MAX_EXECUTABLE_TARGET_IDS = 10

private fun nowAsDuration() = System.currentTimeMillis().toDuration(DurationUnit.MILLISECONDS)

private fun getStorageFilename(): String {
  // version for 243 cannot use `Hashing.xxh3_128()`
  // (not available in 243, and we don't want to bundle `hash4j` as a part of Bazel plugin - JIT, increased build script complexity)
  val suffix = if (ApplicationInfo.getInstance().build.baselineVersion <= 243) "-243" else ""
  return "bazel-targets-v2$suffix.db"
}

private val LOG = logger<TargetUtils>()

@PublicApi
@Service(Service.Level.PROJECT)
class TargetUtils(private val project: Project, private val coroutineScope: CoroutineScope) : SettingsSavingComponent {
  // ...existing code...

  private fun logThreadPoolDiagnostics(phase: String) {
    try {
      // Get the default IO dispatcher thread pool
      val ioDispatcher = Dispatchers.IO as? kotlinx.coroutines.internal.LimitedDispatcher
        ?: (Dispatchers.IO as? kotlinx.coroutines.CoroutineDispatcher)

      // Use Java ThreadMXBean to get thread statistics
      val threadMXBean = java.lang.management.ManagementFactory.getThreadMXBean()
      val allThreads = threadMXBean.allThreadIds
      val threadInfos = threadMXBean.getThreadInfo(allThreads, 0)

      // Count threads by state and name pattern
      var ioThreadsRunning = 0
      var ioThreadsWaiting = 0
      var ioThreadsBlocked = 0
      var totalIOThreads = 0

      for (info in threadInfos) {
        if (info != null && info.threadName.contains("DefaultDispatcher-worker", ignoreCase = true)) {
          totalIOThreads++
          when (info.threadState) {
            Thread.State.RUNNABLE -> ioThreadsRunning++
            Thread.State.WAITING, Thread.State.TIMED_WAITING -> ioThreadsWaiting++
            Thread.State.BLOCKED -> ioThreadsBlocked++
            else -> {}
          }
        }
      }

      LOG.info(
        "Thread pool diagnostics ($phase): " +
        "IO threads total=$totalIOThreads, running=$ioThreadsRunning, waiting=$ioThreadsWaiting, blocked=$ioThreadsBlocked, " +
        "current thread=${Thread.currentThread().name}"
      )

      // Also log total system thread count for context
      LOG.info("System total threads: ${threadMXBean.threadCount}, daemon threads: ${threadMXBean.daemonThreadCount}")

    } catch (e: Exception) {
      LOG.warn("Failed to collect thread pool diagnostics", e)
    }
  }

  // ...existing code...
  private val db = openStore(storeFile = project.getProjectDataPath(getStorageFilename()), filePathSuffix = project.basePath!! + "/")

  // we save only once every 5 minutes, and not earlier than 5 minutes after IDEA startup
  private var lastSaved = nowAsDuration()

  // Throttle saves to avoid freezing - save at most once per minute
  private val saveThrottleMillis = 60_000L
  private var pendingSave: kotlinx.coroutines.Job? = null

  // Dedicated single-thread dispatcher for non-blocking commits
  // This ensures commits happen sequentially and don't block the IO pool
  private val commitDispatcher = Dispatchers.IO.limitedParallelism(1)

  private val allTargetsAndLibrariesLabelsCache =
    SynchronizedClearableLazy {
      db.getAllTargetsAndLibrariesLabelsCache(project)
    }

  @InternalApi
  val allTargetsAndLibrariesLabels: List<String>
    get() = allTargetsAndLibrariesLabelsCache.value

  private val mutableTargetListUpdated = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_LATEST)
  val targetListUpdated: SharedFlow<Unit> = mutableTargetListUpdated.asSharedFlow()

  init {
    // cannot use opt-in (no such opt-in in 243)
    @Suppress("OPT_IN_USAGE")
    coroutineScope.awaitCancellationAndInvoke(Dispatchers.IO) {
      db.close()
    }
  }

  override suspend fun save() {
    // Throttle saves to prevent excessive disk I/O
    val now = nowAsDuration()
    if ((now - lastSaved).inWholeMilliseconds < saveThrottleMillis) {
      return
    }
    db.save()
    lastSaved = now
  }

  fun addFileToTargetIdEntry(file: Path, targets: List<Label>) {
    db.addFileToTarget(file, targets)
  }

  fun removeFileToTargetIdEntry(file: Path) {
    db.removeFileToTarget(file)
  }

  @InternalApi
  @TestOnly
  fun setTargets(labelToTargetInfo: Map<Label, BuildTarget>) {
    db.setTargets(labelToTargetInfo)
    notifyTargetListUpdated()
  }

  fun addTargets(labelToTargetInfo: Map<Label, BuildTarget>, project: Project) {
    db.addTargets(labelToTargetInfo, project)
    notifyTargetListUpdated()
  }

  // todo expensive operation
  fun computeFullLabelToTargetInfoMap(syncedTargetIdToTargetInfo: Map<Label, BuildTarget>): Map<Label, BuildTarget> =
    db.computeFullLabelToTargetInfoMap(syncedTargetIdToTargetInfo)

  @InternalApi
  fun saveTargets(
    targets: List<RawBuildTarget>,
    fileToTarget: Map<Path, List<Label>>,
    libraryItems: List<LibraryItem>?,
  ) {
    ThreadingAssertions.assertBackgroundThread()
    val labelToTargetInfo = targets.associateByTo(HashMap(targets.size)) { it.id }
    db.reset(
      fileToTarget = fileToTarget,
      fileToExecutableTargets =
        calculateFileToExecutableTargets(
          libraryItems = libraryItems,
          fileToTarget = fileToTarget,
          targets = targets,
          labelToTargetInfo = labelToTargetInfo,
        ),
      libraryItems = libraryItems,
      targets = targets,
      project = project,
    )

    notifyTargetListUpdated()

    // Fire-and-forget async save on dedicated single-thread dispatcher
    // This ensures commits don't block the shared IO pool or reads
    // The commitDispatcher has parallelism=1 so saves happen sequentially
    pendingSave?.cancel()
    pendingSave = coroutineScope.launch(commitDispatcher + NonCancellable) {
      val now = nowAsDuration()
      if ((now - lastSaved).inWholeMilliseconds >= saveThrottleMillis) {
        val startTime = System.currentTimeMillis()

        // Diagnostic: Log thread pool info before save
        logThreadPoolDiagnostics("before save")

        try {
          // This blocks only the dedicated commit thread, not the main IO pool
          // Reads can continue during the commit using in-memory cached data
          db.save()
          lastSaved = now

          val duration = System.currentTimeMillis() - startTime

          // Diagnostic: Log thread pool info after save
          logThreadPoolDiagnostics("after save")

          // Log performance metrics
          LOG.info("TargetUtils database save completed in ${duration}ms (targets: ${targets.size}, files: ${fileToTarget.size})")

          if (duration > 1000) {
            LOG.warn("TargetUtils database save took ${duration}ms - this may indicate I/O bottleneck")
          }
        } catch (e: Exception) {
          LOG.error("Failed to save TargetUtils database", e)
        }
      }
    }
  }

  private fun calculateFileToExecutableTargets(
    libraryItems: List<LibraryItem>?,
    fileToTarget: Map<Path, List<Label>>,
    targets: List<RawBuildTarget>,
    labelToTargetInfo: Map<Label, BuildTarget>,
  ): Map<Path, List<Label>> {
    val targetDependentsGraph = TargetDependentsGraph(targets, libraryItems)
      val targetToTransitiveRevertedDependenciesCache = mutableMapOf<Label, Set<Label>>()
    return fileToTarget.entries
      .mapNotNull { (path, targets) ->
        val dependents =
          targets
            .flatMap { label ->
              calculateTransitivelyExecutableTargets(
                resultCache = targetToTransitiveRevertedDependenciesCache,
                targetDependentsGraph = targetDependentsGraph,
                labelToTargetInfo = labelToTargetInfo,
                target = label,
              )
            }.distinct()

        if (dependents.isEmpty()) {
          null
        } else {
          path to dependents
        }
      }.toMap()
  }

  private fun calculateTransitivelyExecutableTargets(
    resultCache: MutableMap<Label, Set<Label>>,
    targetDependentsGraph: TargetDependentsGraph,
    target: Label,
    labelToTargetInfo: Map<Label, BuildTarget>,
    visited: MutableSet<Label> = HashSet(),
  ): Set<Label> =
    resultCache.getOrPut(target) {
      // Check if we've already visited this target in the current path to prevent cycles
      if (target in visited) {
        return@getOrPut emptySet()
      }

      val targetInfo = labelToTargetInfo[target]
      if (targetInfo?.kind?.isExecutable == true) {
        return@getOrPut setOf(target)
      }

      // Add current target to visited set
      visited.add(target)

      val directDependentIds = targetDependentsGraph.directDependentIds(target)

      val executableTargetsFromSamePackage = directDependentIds.filter {
        it.packagePath == target.packagePath && labelToTargetInfo[it]?.kind?.isExecutable == true
      }
      if (executableTargetsFromSamePackage.isNotEmpty()) {
        val result = executableTargetsFromSamePackage.toHashSet()
        visited.remove(target)
        return@getOrPut result
      }

      val result = directDependentIds
        .asSequence()
        .flatMap { dependency ->
          calculateTransitivelyExecutableTargets(resultCache, targetDependentsGraph, dependency, labelToTargetInfo, visited)
        }.distinct()
        .take(MAX_EXECUTABLE_TARGET_IDS)
        .toHashSet()
      // Remove current target from visited set (backtracking)
      visited.remove(target)
      return@getOrPut result
    }

  private fun notifyTargetListUpdated() {
    check(mutableTargetListUpdated.tryEmit(Unit))
    allTargetsAndLibrariesLabelsCache.drop()
  }

  @PublicApi
  fun allTargets(): Sequence<Label> = db.getAllTargets()

  fun getTotalTargetCount(): Int = db.getTotalTargetCount()

  @PublicApi
  fun getTargetsForPath(path: Path): List<Label> = db.getTargetsForPath(path) ?: emptyList()

  @PublicApi
  fun getTargetsForFile(file: VirtualFile): List<Label> = file.toNioPathOrNull()?.let { getTargetsForPath(it) } ?: emptyList()

  @InternalApi
  fun getExecutableTargetsForFile(file: VirtualFile): List<Label> {
    val executableDirectTargets =
      getTargetsForFile(file)
        .filter { label -> db.getBuildTargetForLabel(label, project)?.kind?.isExecutable == true }
    if (executableDirectTargets.isEmpty()) {
      return file.toNioPathOrNull()?.let { db.getExecutableTargetsForPath(it) } ?: emptyList()
    }
    return executableDirectTargets
  }

  @PublicApi
  fun isLibrary(target: Label): Boolean = getBuildTargetForLabel(target)?.kind?.ruleType == RuleType.LIBRARY

  @PublicApi
  fun getTargetForModuleId(moduleId: String): Label? = db.getTargetForModuleId(moduleId)

  @PublicApi
  fun getTargetForLibraryId(libraryId: String): Label? = db.getTargetForLibraryId(libraryId)

  /**
   * All labels in a label-to-target map are canonical.
   * The label must be first canonicalized via toCanonicalLabel.
   */
  @InternalApi
  fun getBuildTargetForLabel(label: Label): BuildTarget? = db.getBuildTargetForLabel(label, project)

  @InternalApi
  fun getBuildTargetForModule(module: Module): BuildTarget? = getTargetForModuleId(module.name)?.let { getBuildTargetForLabel(it) }

  @InternalApi
  fun allBuildTargets(): Sequence<BuildTarget> = db.getAllBuildTargets()

  // todo: avoid such methods as we load all targets into memory
  @InternalApi
  fun allBuildTargetAsLabelToTargetMap(predicate: (BuildTarget) -> Boolean): List<Label> = db.allBuildTargetAsLabelToTargetMap(predicate)

  fun getTotalFileCount(): Int = db.getTotalFileCount()
}

private val LIBRARY_MODULE_PREFIX = "_aux.libraries."

@InternalApi
fun String.addLibraryModulePrefix() = LIBRARY_MODULE_PREFIX + this

@InternalApi
fun String.isLibraryModule() = startsWith(LIBRARY_MODULE_PREFIX)

@PublicApi
val Project.targetUtils: TargetUtils
  get() = service<TargetUtils>()

@PublicApi
fun Label.getModule(project: Project): Module? = project.targetUtils.getBuildTargetForLabel(this)?.getModule(project)

@PublicApi
fun Label.getModuleEntity(project: Project): ModuleEntity? = getModule(project)?.moduleEntity

val Module.moduleEntity: ModuleEntity?
  @InternalApi
  get() {
    val bridge = this as? ModuleBridge ?: return null
    return bridge.findModuleEntity(bridge.entityStorage.current)
  }

@InternalApi
fun BuildTarget.getModule(project: Project): Module? {
  val moduleName = this.id.formatAsModuleName(project)
  return ModuleManager.getInstance(project).findModuleByName(moduleName)
}
