package starterpack.bench

import java.io.File

/**
 * Everything the bench does with the filesystem.
 *
 * **This is deliberately not part of [LoadoutBench], and must stay that way.** The mission is
 * compiled at runtime by Janino under a classloader that refuses to load `java.io.File` at all --
 * "File access and reflection are not allowed to scripts". Resolving the script's single call makes
 * Janino ask [LoadoutBench] for its declared methods, which forces the JVM to load every type in
 * every one of its signatures. A single `File` anywhere on that class is enough to kill the compile
 * and take the game's startup with it.
 *
 * Nothing here is ever touched by the script; it is reached only from the mod's own jar-loaded code,
 * which has no such restriction. Keep it that way: if a `File` ever needs to be visible to the
 * mission, it doesn't -- pass a String.
 */
internal object BenchFiles {

    /** `saves/missions/<mission id>`, wherever this install keeps its saves. */
    fun benchDir(): File = File(savesDir(), "missions/${LoadoutBench.MISSION_ID}")

    /**
     * The saves directory.
     *
     * The launcher passes this as a system property (`-Dcom.fs.starfarer.settings.paths.saves`), so
     * installs that relocate their saves are handled. The fallback matches the stock launcher, whose
     * working directory is `starsector-core`.
     */
    private fun savesDir(): File =
        File(System.getProperty("com.fs.starfarer.settings.paths.saves") ?: "../saves")

    /** Every variant the bench has written, in fleet order. Empty when it has never been visited. */
    fun savedVariants(): List<File> {
        val dir = benchDir()
        if (!dir.isDirectory) return emptyList()
        val files = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".variant") } ?: return emptyList()
        return files.sortedBy { indexOf(it) }
    }

    /**
     * The fleet index encoded in a bench variant's filename.
     *
     * The game names these `mission_<mission id>_ship_<n>.variant`. A name that does not parse
     * returns [Int.MAX_VALUE] so it sorts last and is rejected by the importer rather than silently
     * being treated as ship zero.
     */
    fun indexOf(file: File): Int {
        val name = file.name.removeSuffix(".variant")
        val marker = "_ship_"
        val at = name.lastIndexOf(marker)
        if (at < 0) return Int.MAX_VALUE
        return name.substring(at + marker.length).toIntOrNull() ?: Int.MAX_VALUE
    }

    /**
     * Deletes what the bench previously wrote.
     *
     * Run before sending the user in, so a template that lost a ship cannot have the stale variant of
     * a since-removed slot imported back onto whatever now occupies that index.
     */
    fun clearSavedVariants(): Int {
        var removed = 0
        for (file in savedVariants()) {
            if (runCatching { file.delete() }.getOrDefault(false)) removed++
        }
        return removed
    }
}
