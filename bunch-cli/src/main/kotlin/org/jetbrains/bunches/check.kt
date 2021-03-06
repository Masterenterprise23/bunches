@file:JvmName("BunchCheck")

package org.jetbrains.bunches.check

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import org.eclipse.jgit.diff.DiffEntry
import org.jetbrains.bunches.file.BUNCH_FILE_NAME
import org.jetbrains.bunches.file.readExtensionsFromFile
import org.jetbrains.bunches.file.resultWithExit
import org.jetbrains.bunches.general.BunchSubCommand
import org.jetbrains.bunches.general.exitWithError
import org.jetbrains.bunches.git.CommitInfo
import org.jetbrains.bunches.git.readCommits
import java.io.File

data class Settings(val repoPath: String, val sinceRef: String, val untilRef: String, val extensions: String?)

fun main(args: Array<String>) {
    CheckCommand().main(args)
}

const val CHECK_DESCRIPTION =
    "Check if commits at this interval have forgotten bunch files."

val CHECK_EXAMPLE =
    """
    Example:
    bunch check -C C:/Projects/kotlin HEAD 377572896b7dc09a5e2aa6af29825ffe07f71e58
    """.trimIndent()

const val CH_SINCE = "since-ref"
const val CH_UNTIL = "until-ref"

class CheckCommand : BunchSubCommand(
    name = "check",
    help = CHECK_DESCRIPTION,
    epilog = CHECK_EXAMPLE
) {
    val repoPath by repoPathOption()
    private val sinceRef by argument(
        "<$CH_SINCE>",
        help = "Reference to the most recent commit that should be checked."
    )

    private val untilRef by argument("<$CH_UNTIL>", help = "Parent of the last commit that should be checked.")

    val extension by option(
        "--ext",
        help = "Set of extensions to check with ',' separator. " +
                "'$BUNCH_FILE_NAME' file will be used if the option is missing."
    )

    override fun run() {
        val settings = Settings(
            repoPath = repoPath.toString(),
            sinceRef = sinceRef,
            untilRef = untilRef,
            extensions = extension
        )

        process { doCheck(settings) }
    }
}

fun doCheck(settings: Settings) {
    val extensions = settings.extensions?.split(',')
        ?: readExtensionsFromFile(settings.repoPath).resultWithExit()

    val commits = readCommits(settings.repoPath, settings.sinceRef, settings.untilRef)
    var problemCommitsFound = false

    println("Found commits:")
    for (commit in commits) {
        println(commit.title)
    }
    println()

    println("Result:")

    val createFileCommitIndex = getCreateFileCommitIndexMap(commits, extensions)

    for (commitIndex in commits.indices) {
        val commit = commits[commitIndex]
        val forgottenFilesPaths =
            checkOneCommit(commit, extensions, settings.repoPath, createFileCommitIndex, commitIndex)

        val deletedCache: MutableMap<String, Boolean> = HashMap()
        fun isDeletedWithCache(bunchFilePath: String): Boolean {
            return deletedCache.getOrPut(bunchFilePath) {
                val bunchFile = File(settings.repoPath, bunchFilePath)
                isDeletedBunchFile(bunchFile)
            }
        }

        if (forgottenFilesPaths.isNotEmpty()) {
            problemCommitsFound = true

            println("${commit.hash} ${commitAuthorString(commit)} ${commit.title}")
            for (forgottenPath in forgottenFilesPaths) {
                println("    $forgottenPath ${if (isDeletedWithCache(forgottenPath)) "[deleted]" else ""}")
            }
            println()
        }
    }

    if (problemCommitsFound) {
        exitWithError()
    }

    println("${commits.size} commits have been checked. No problem commits found.")
}

fun checkOneCommit(
    commit: CommitInfo,
    extensions: List<String>,
    directory: String,
    createFileCommitIndex: Map<String, Int>,
    commitIndex: Int
): List<String> {
    val affectedPaths = commit.fileActions.mapNotNullTo(HashSet()) { it.newPath }

    val forgottenFilesPaths = mutableListOf<String>()
    for (fileAction in commit.fileActions) {
        val newPath = fileAction.newPath ?: continue
        if (File(newPath).extension in extensions) continue

        for (extension in extensions) {
            val bunchFilePath = "$newPath.$extension"
            val file = File(directory, bunchFilePath)
            if (bunchFilePath !in affectedPaths
                && file.exists()
                && isCreatedBefore(createFileCommitIndex[bunchFilePath], commitIndex)
            ) {
                forgottenFilesPaths.add(bunchFilePath)
            }
        }
    }
    return forgottenFilesPaths
}

internal fun getCreateFileCommitIndexMap(commits: List<CommitInfo>, extensions: List<String>): Map<String, Int> {
    val creationIndex = mutableMapOf<String, Int>()
    for (commitIndex in commits.indices) {
        val commit = commits[commitIndex]
        for (action in commit.fileActions) {
            val filePath = action.newPath ?: continue
            if (action.changeType == DiffEntry.ChangeType.ADD
                && File(filePath).extension in extensions
                && filePath !in creationIndex.keys
            ) {
                creationIndex[filePath] = commitIndex
            }
        }
    }
    return creationIndex
}

private fun isCreatedBefore(firstCommitIndex: Int?, secondCommitIndex: Int): Boolean {
    return firstCommitIndex == null || secondCommitIndex < firstCommitIndex
}

fun isDeletedBunchFile(bunchFile: File): Boolean = bunchFile.exists() && bunchFile.readText().trim().isEmpty()

fun commitAuthorString(commit: CommitInfo): String {
    val committer = commit.committer?.name
    var author = commit.author?.name
    if (author == null && committer != null) {
        author = committer
    }

    return when {
        author == null -> ""
        author == committer || committer == null -> "[$author]"
        else -> "[$author ($committer)]"
    }
}

