package org.jetbrains.bazel.sync.status

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import org.jetbrains.bazel.sync.scope.FirstPhaseSync
import org.jetbrains.bazel.sync.scope.PartialProjectSync
import org.jetbrains.bazel.sync.scope.ProjectSyncScope
import org.jetbrains.bazel.sync.scope.SecondPhaseSync

/**
 * Published once per completed full project sync from [org.jetbrains.bazel.sync.task.ProjectSyncTask].
 * Carries the sync result, scope, and duration so that Snowjet can record richer sync metrics
 * than [SyncStatusListener] provides (which only distinguishes completed vs cancelled).
 */
data class FullSyncResult(
  val result: String,
  val scope: String,
  val buildProject: Boolean,
  val durationMs: Long,
)

interface FullSyncResultListener {
  fun onResult(result: FullSyncResult)

  companion object {
    @JvmField
    val TOPIC: Topic<FullSyncResultListener> = Topic(FullSyncResultListener::class.java)
  }
}

internal fun publishFullSyncResult(
  project: Project,
  syncScope: ProjectSyncScope,
  buildProject: Boolean,
  result: String,
  syncStartNanos: Long,
) {
  val durationMs = (System.nanoTime() - syncStartNanos) / 1_000_000
  val scopeLabel = when (syncScope) {
    FirstPhaseSync -> "first_phase"
    SecondPhaseSync -> "full"
    is PartialProjectSync -> "partial"
  }
  runCatching {
    project.messageBus.syncPublisher(FullSyncResultListener.TOPIC).onResult(
      FullSyncResult(
        result = result,
        scope = scopeLabel,
        buildProject = buildProject,
        durationMs = durationMs,
      ),
    )
  }
}
