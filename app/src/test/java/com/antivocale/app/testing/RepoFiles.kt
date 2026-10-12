package com.antivocale.app.testing

import java.io.File

/**
 * TASK-818: the ONE repo-file locator for unit tests, consolidating the
 * ~13 private dual-path readers (and the inline `File("app/...")` fallback
 * chains) that had drifted into two fallback directions once a test needed
 * a file OUTSIDE the app module (eval/).
 *
 * The path convention is MODULE-RELATIVE (the convention every pre-existing
 * reader used: `src/main/assets/models_catalog.json`), and the file is
 * resolved from whichever cwd the test JVM inherited:
 *  - the app module: the path directly,
 *  - the repo root: the path under `app/`,
 *  - anything else (a module subdir, or a target outside the module such
 *    as `../eval/loop-guard-cases.json`): the `../` form.
 *
 * Failure names the cwd and every candidate tried, the contract the
 * AppNavigationTest/ModelAccuracyTest readers established (a bare
 * FileNotFoundException with no context was the diagnosability gap).
 * Files and directories both resolve (the res/ locale scans pass their
 * directory root through the same locator).
 */
object RepoFiles {

    fun resolve(moduleRelative: String): File {
        // The repo-root form probes FIRST so a "../"-prefixed target (the
        // outside-module spelling) can never escape the repository: from a
        // root cwd the direct "../x" candidate would point at the repo's
        // PARENT and silently win over the correct in-repo "app/../x"
        // (review F5).
        val candidates = listOf(
            File("app/$moduleRelative"),
            File(moduleRelative),
            File("../$moduleRelative"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "cannot locate '$moduleRelative' from cwd ${File(".").absolutePath} " +
                    "(tried ${candidates.joinToString { it.path }})")
    }

    fun readText(moduleRelative: String): String = resolve(moduleRelative).readText()
}
