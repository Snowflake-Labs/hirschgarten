package org.jetbrains.bazel.workspace.fileEvents

import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException

internal suspend fun withPartialSyncMetrics(
  project: Project,
  metricsHolder: PartialSyncMetricsHolder,
  trigger: String = "auto",
  allFileEvents: List<SimplifiedFileEvent>? = null,
  body: suspend () -> Unit,
) {
  val startNanos = System.nanoTime()
  try {
    body()
  } catch (ex: CancellationException) {
    throw ex
  } catch (ex: Throwable) {
    metricsHolder.outcome = PartialSyncMetricsHolder.OUTCOME_ERROR
    throw ex
  } finally {
    runCatching {
      publishPartialSyncResult(project, metricsHolder, startNanos, trigger, allFileEvents)
    }
  }
}

internal fun publishPartialSyncResult(
  project: Project,
  metricsHolder: PartialSyncMetricsHolder,
  startNanos: Long? = null,
  trigger: String = "auto",
  allFileEvents: List<SimplifiedFileEvent>? = null,
) {
  val totalMs = startNanos?.let { (System.nanoTime() - it) / 1_000_000 }
  val opCounts = allFileEvents?.groupingBy { it.toOpName() }?.eachCount() ?: emptyMap()
  val batchSize = allFileEvents?.size ?: 1
  project.messageBus.syncPublisher(PartialSyncResultListener.TOPIC).onResult(
    PartialSyncResult(
      outcome = metricsHolder.outcome,
      trigger = trigger,
      opCounts = opCounts,
      batchSize = batchSize,
      totalMs = totalMs,
      targetQueryMs = metricsHolder.targetQueryMs,
      unsyncedTargetFetchMs = metricsHolder.unsyncedTargetFetchMs,
    ),
  )
}

private fun SimplifiedFileEvent.toOpName(): String =
  when (this) {
    is SimplifiedFileEvent.Create -> "create"
    is SimplifiedFileEvent.ExternalCreate -> "externalCreate"
    is SimplifiedFileEvent.CreateDirectory -> "createDirectory"
    is SimplifiedFileEvent.Copy -> "copy"
    is SimplifiedFileEvent.Move -> "move"
    is SimplifiedFileEvent.Rename -> "rename"
    is SimplifiedFileEvent.Delete -> "delete"
  }

internal class PartialSyncMetricsHolder {
  var outcome: String = OUTCOME_UNKNOWN
  var targetQueryMs: Long? = null
  var unsyncedTargetFetchMs: Long? = null
  var hasFilesNoTarget: Boolean = false
  var hasUnsyncedTargets: Boolean = false

  fun resolveQuerySuccessOutcome() {
    outcome = when {
      hasFilesNoTarget -> OUTCOME_QUERY_NO_TARGET
      hasUnsyncedTargets -> OUTCOME_QUERY_RAN_ASPECT
      else -> OUTCOME_QUERY_FOUND_SYNCED
    }
  }

  companion object {
    const val OUTCOME_UNKNOWN = "unknown"
    const val OUTCOME_NO_OP = "no_op"
    const val OUTCOME_NO_QUERY_NEEDED = "no_query_needed"
    const val OUTCOME_QUERY_FOUND_SYNCED = "query_found_synced"
    const val OUTCOME_QUERY_RAN_ASPECT = "query_ran_aspect"
    const val OUTCOME_QUERY_NO_TARGET = "query_no_target"
    const val OUTCOME_QUERY_DISALLOWED = "query_disallowed"
    const val OUTCOME_QUERY_UNAVAILABLE = "query_unavailable"
    const val OUTCOME_TOO_MANY_EVENTS = "too_many_events"
    const val OUTCOME_ERROR = "error"
  }
}
