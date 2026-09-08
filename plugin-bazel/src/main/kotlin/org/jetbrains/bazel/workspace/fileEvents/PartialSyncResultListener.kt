package org.jetbrains.bazel.workspace.fileEvents

import com.intellij.util.messages.Topic

/**
 * Published once per processed batch of file events (auto-detect) or per manual file-to-module
 * action (right-click), after the workspace model commit (or after a decision not to commit).
 * Snowjet subscribes to record partial sync metrics (CUJ 2, 3, 4, 5).
 *
 * All fields use plain types with Kotlin defaults so that future additions never require changing
 * the publish() call sites in upstream files.
 */
data class PartialSyncResult(
  val outcome: String,
  val trigger: String = "auto",
  val opCounts: Map<String, Int> = emptyMap(),
  val batchSize: Int = 0,
  val totalMs: Long? = null,
  val targetQueryMs: Long? = null,
  val unsyncedTargetFetchMs: Long? = null,
)

interface PartialSyncResultListener {
  fun onResult(result: PartialSyncResult)

  companion object {
    @JvmField
    val TOPIC: Topic<PartialSyncResultListener> = Topic(PartialSyncResultListener::class.java)
  }
}
