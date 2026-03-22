package com.callshow.gui

import java.io.File
import java.nio.file.{Files, Path, Paths}

/**
 * Locates the Python callshow tracer binary.
 */
object TracerLocator {

  /**
   * Find the Python tracer binary.
   * Priority:
   * 1. Bazel runfiles (when run via `bazel run`)
   * 2. Sibling in workspace (for development)
   * 3. System: `python3 -m callshow.cli` fallback
   */
  def findTracer(): java.util.List[String] = {
    val result = new java.util.ArrayList[String]()

    // Check Bazel runfiles
    val runfilesDir = System.getenv("RUNFILES_DIR")
    if (runfilesDir != null) {
      val tracerPath = Paths.get(runfilesDir,
        "_main", "src", "main", "python", "callshow", "callshow")
      if (Files.exists(tracerPath) && Files.isExecutable(tracerPath)) {
        result.add(tracerPath.toString)
        return result
      }
    }

    // Check JAVA_RUNFILES
    val javaRunfiles = System.getenv("JAVA_RUNFILES")
    if (javaRunfiles != null) {
      val tracerPath = Paths.get(javaRunfiles,
        "_main", "src", "main", "python", "callshow", "callshow")
      if (Files.exists(tracerPath) && Files.isExecutable(tracerPath)) {
        result.add(tracerPath.toString)
        return result
      }
    }

    // Check workspace directory (BUILD_WORKSPACE_DIRECTORY set by bazel run)
    val workspaceDir = System.getenv("BUILD_WORKSPACE_DIRECTORY")
    if (workspaceDir != null) {
      val tracerPath = Paths.get(workspaceDir,
        "bazel-bin", "src", "main", "python", "callshow", "callshow")
      if (Files.exists(tracerPath) && Files.isExecutable(tracerPath)) {
        result.add(tracerPath.toString)
        return result
      }
    }

    // Fallback: use python3 -m callshow.cli
    result.add("python3")
    result.add("-m")
    result.add("callshow.cli")
    result
  }
}
