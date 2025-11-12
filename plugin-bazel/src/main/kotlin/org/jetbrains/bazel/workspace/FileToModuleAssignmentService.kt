package org.jetbrains.bazel.workspace

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.storage.MutableEntityStorage
import org.jetbrains.bazel.coroutines.BazelCoroutineService
import org.jetbrains.bazel.utils.isSourceFile

/**
 * Service that allows other plugins to trigger file-to-module assignment for specific files.
 *
 * This is useful when another plugin modifies BUILD files and wants to ensure that
 * the affected source files are properly assigned to their corresponding modules.
 */
@Service(Service.Level.PROJECT)
class FileToModuleAssignmentService(private val project: Project) {

  /**
   * Triggers file-to-module assignment for the given file.
   * This will query Bazel to find which targets should contain the file and update the workspace model accordingly.
   *
   * @param file The file to assign to modules
   */
  fun assignFileToModules(file: VirtualFile) {
    if (!file.isSourceFile()) return

    BazelCoroutineService.getInstance(project).start {
      val workspaceModel = project.service<WorkspaceModel>()
      val entityStorageDiff = MutableEntityStorage.from(workspaceModel.currentSnapshot)

      processFileForAssignment(
        newFile = file,
        project = project,
        workspaceModel = workspaceModel,
        entityStorageDiff = entityStorageDiff,
      )

      workspaceModel.update("File assignment from external plugin") {
        it.applyChangesFrom(entityStorageDiff)
      }
    }
  }

  companion object {
    @JvmStatic
    fun getInstance(project: Project): FileToModuleAssignmentService = project.service()
  }
}

