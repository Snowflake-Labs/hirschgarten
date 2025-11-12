# FileToModuleAssignmentService

## Overview

The `FileToModuleAssignmentService` allows other plugins to trigger file-to-module assignment for specific files in a Bazel project. This is useful when your plugin modifies BUILD files and needs to ensure that affected source files are properly assigned to their corresponding modules.

## Use Case

When your plugin:
1. Modifies a BUILD file (e.g., adds a file to the `srcs` attribute of a target)
2. Needs IntelliJ to recognize that the file now belongs to specific modules
3. Wants to trigger the same file-to-module assignment that happens automatically when files are created

## Usage

### Basic Example

```java
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.bazel.workspace.FileToModuleAssignmentService;

public class MyPlugin {
    public void assignFileAfterBuildFileChange(Project project, VirtualFile sourceFile) {
        FileToModuleAssignmentService.getInstance(project).assignFileToModules(sourceFile);
    }
}
```

### Complete Example

Here's a complete example showing how to use this service after modifying a BUILD file:

```java
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.bazel.workspace.FileToModuleAssignmentService;

public class BuildFileUpdater {
    public void addFileToBuildAndAssignToModule(
        Project project,
        VirtualFile buildFile,
        VirtualFile sourceFile
    ) {
        // 1. Modify the BUILD file (your plugin's logic)
        modifyBuildFile(buildFile, sourceFile);
        
        // 2. Ensure BUILD file changes are written to disk
        //    (Critical: Bazel reads from disk, not from IntelliJ's memory)
        WriteAction.run(() -> {
            PsiDocumentManager.getInstance(project).commitAllDocuments();
            FileDocumentManager.getInstance().saveAllDocuments();
        });
        
        // 3. Trigger file-to-module assignment
        FileToModuleAssignmentService.getInstance(project).assignFileToModules(sourceFile);
    }
    
    private void modifyBuildFile(VirtualFile buildFile, VirtualFile sourceFile) {
        // Your BUILD file modification logic here
    }
}
```

## How It Works

When you call `assignFileToModules(file)`:

1. **Queries Bazel**: Runs `bazel query` to find which targets should contain the file (based on their `srcs` attributes)
2. **Creates Modules**: If the target's module doesn't exist, it creates it via partial sync
3. **Adds Content Roots**: Adds the file as a content root to the appropriate module(s)
4. **Updates Metadata**: Updates internal caches so the IDE knows which files belong to which targets

## Important Notes

### 1. Save Documents Before Calling

**Critical**: Always save all documents to disk before calling this service. Bazel reads BUILD files from disk, not from IntelliJ's in-memory representation.

```java
// REQUIRED before calling assignFileToModules
WriteAction.run(() -> {
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    FileDocumentManager.getInstance().saveAllDocuments();
});
```

### 2. Only Source Files

The service only processes source files (files with extensions like `.java`, `.kt`, `.py`, etc.). Other file types are ignored.

### 3. Asynchronous Processing

The assignment happens asynchronously in a background coroutine. The method returns immediately, but the actual workspace model update happens later.

### 4. Requires Valid Bazel Project

The service only works in Bazel projects that have completed at least one sync. During initial sync, the service may return early without processing.

## API Reference

### Getting the Service

```java
import org.jetbrains.bazel.workspace.FileToModuleAssignmentService;

FileToModuleAssignmentService service = 
    FileToModuleAssignmentService.getInstance(project);
```

### Method

```java
public void assignFileToModules(VirtualFile file)
```

**Parameters:**
- `file`: The source file to assign to modules (must be a `VirtualFile`)

**Returns:** `void`

**Behavior:**
- Ignores non-source files (e.g., `.txt`, `.md`, etc.)
- Queries Bazel to find containing targets
- Updates workspace model with new module assignments
- Runs asynchronously in a background coroutine

## Troubleshooting

### Files Not Being Assigned

**Symptom**: Calling `assignFileToModules()` but the file doesn't appear in any module.

**Solution**: Ensure you save all documents before calling the service:

```java
WriteAction.run(() -> {
    PsiDocumentManager.getInstance(project).commitAllDocuments();
    FileDocumentManager.getInstance().saveAllDocuments();
});
```

### Query Fails

**Symptom**: Bazel query fails to find targets.

**Possible causes**:
- BUILD file changes not saved to disk
- File path is outside the Bazel workspace
- Bazel query syntax error
- Project sync is in progress

### No Module Created

**Symptom**: The service runs but no module appears.

**Possible causes**:
- The file is not listed in any target's `srcs` attribute
- The target is not in the project view
- Bazel query returned empty results

## Complete Example: BUILD File Modifier

Here's a complete working example that modifies a BUILD file and assigns the source file:

```java
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.bazel.workspace.FileToModuleAssignmentService;

public class MyBuildFileModifier {
    
    /**
     * Adds a source file to a Bazel target and assigns it to the corresponding module.
     */
    public void addSourceToTarget(
        Project project,
        VirtualFile sourceFile,
        String targetLabel
    ) {
        // 1. Find and modify BUILD file
        VirtualFile buildFile = findBuildFile(project, targetLabel);
        if (buildFile == null) {
            return;
        }
        
        boolean modified = addSourceToBuildFile(project, buildFile, sourceFile);
        
        if (modified) {
            // 2. Save changes to disk (CRITICAL STEP!)
            WriteAction.run(() -> {
                PsiDocumentManager.getInstance(project).commitAllDocuments();
                FileDocumentManager.getInstance().saveAllDocuments();
            });
            
            // 3. Trigger file-to-module assignment
            FileToModuleAssignmentService.getInstance(project)
                .assignFileToModules(sourceFile);
        }
    }
    
    private VirtualFile findBuildFile(Project project, String targetLabel) {
        // Your logic to find BUILD file from target label
        return null; // placeholder
    }
    
    private boolean addSourceToBuildFile(
        Project project,
        VirtualFile buildFile,
        VirtualFile sourceFile
    ) {
        // Your logic to add source file to BUILD file's srcs attribute
        // For example, using PSI manipulation
        PsiFile psiFile = PsiManager.getInstance(project).findFile(buildFile);
        if (psiFile == null) {
            return false;
        }
        
        // Modify the BUILD file PSI here
        // ...
        
        return true;
    }
}
```

## Best Practices

1. **Always save documents** before calling `assignFileToModules()` - This is critical since Bazel reads from disk
2. **Verify file types before calling** - The service silently ignores non-source files (e.g., `.txt`, `.md`), so check the file extension beforehand if needed
3. **Don't call too frequently** - Batch multiple file assignments if possible, as each call triggers a Bazel query
4. **Handle asynchronous behavior** - The method returns immediately but processing happens in the background
5. **Test with different file types** - Ensure your code works with Java, Kotlin, Python, etc.

## Related APIs

- `AssignFileToModuleListener` - Internal listener that automatically assigns files on creation
- `TargetUtils` - Provides utilities for working with Bazel targets
- `WorkspaceModel` - The underlying storage for modules and content roots

