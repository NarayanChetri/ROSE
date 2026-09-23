package dev.narayan.rose.update

/**
 * Robust Semantic Version representation following SemVer 2.0.0 specification.
 * Handles:
 * - 'v' / 'V' prefixes (e.g., "v1.2.4")
 * - Major, minor, patch, and additional numeric components (e.g., "1.2", "1.2.4", "1.2.4.1")
 * - Pre-release identifiers (e.g., "1.2.4-rc.1", "1.2.4-beta")
 * - Build metadata ignored for precedence (e.g., "1.2.4+build.2024")
 */
data class SemanticVersion(
    val numbers: List<Int>,
    val preRelease: List<String> = emptyList(),
    val raw: String = ""
) : Comparable<SemanticVersion> {

    val major: Int get() = numbers.getOrElse(0) { 0 }
    val minor: Int get() = numbers.getOrElse(1) { 0 }
    val patch: Int get() = numbers.getOrElse(2) { 0 }
    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemanticVersion): Int {
        // Compare numeric components
        val maxLen = maxOf(this.numbers.size, other.numbers.size)
        for (i in 0 until maxLen) {
            val num1 = this.numbers.getOrElse(i) { 0 }
            val num2 = other.numbers.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }

        // Numbers are equal:
        // Rule: Normal version has higher precedence than pre-release version.
        // e.g., 1.0.0 > 1.0.0-rc.1
        if (this.isPreRelease && !other.isPreRelease) return -1
        if (!this.isPreRelease && other.isPreRelease) return 1
        if (!this.isPreRelease && !other.isPreRelease) return 0

        // Both are pre-release: compare pre-release identifiers dot by dot
        val maxPre = maxOf(this.preRelease.size, other.preRelease.size)
        for (i in 0 until maxPre) {
            val id1 = this.preRelease.getOrNull(i)
            val id2 = other.preRelease.getOrNull(i)

            // A larger set of pre-release fields has higher precedence if preceding are equal
            if (id1 == null) return -1
            if (id2 == null) return 1

            val num1 = id1.toIntOrNull()
            val num2 = id2.toIntOrNull()

            when {
                // Both numeric: compare numerically
                num1 != null && num2 != null -> {
                    if (num1 != num2) return num1.compareTo(num2)
                }
                // Numeric identifiers have lower precedence than non-numeric identifiers
                num1 != null && num2 == null -> return -1
                num1 == null && num2 != null -> return 1
                // Both alphanumeric: compare ASCII lexical order
                else -> {
                    val cmp = id1.compareTo(id2)
                    if (cmp != 0) return cmp
                }
            }
        }

        return 0
    }

    companion object {
        /**
         * Parses a version string into a [SemanticVersion].
         * Tolerates 'v' prefix, missing patch numbers, whitespace, and build metadata.
         */
        fun parseOrNull(versionStr: String?): SemanticVersion? {
            if (versionStr.isNullOrBlank()) return null
            var s = versionStr.trim()
            if (s.startsWith("v", ignoreCase = true)) {
                s = s.substring(1).trim()
            }
            if (s.isEmpty()) return null

            // Drop build metadata after '+'
            val plusIdx = s.indexOf('+')
            val withoutBuild = if (plusIdx >= 0) s.substring(0, plusIdx) else s

            // Separate core version and pre-release after '-'
            val hyphenIdx = withoutBuild.indexOf('-')
            val coreStr = if (hyphenIdx >= 0) withoutBuild.substring(0, hyphenIdx) else withoutBuild
            val preReleaseStr = if (hyphenIdx >= 0) withoutBuild.substring(hyphenIdx + 1) else null

            val numbers = coreStr.split('.')
                .map { it.trim().toIntOrNull() ?: return null }

            if (numbers.isEmpty()) return null

            val preReleaseList = if (!preReleaseStr.isNullOrBlank()) {
                preReleaseStr.split('.').map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                emptyList()
            }

            return SemanticVersion(
                numbers = numbers,
                preRelease = preReleaseList,
                raw = versionStr
            )
        }

        /**
         * Returns true if [latest] is strictly newer than [current].
         * Safe against parsing failures with fallback logic.
         */
        fun isNewerVersion(current: String?, latest: String?): Boolean {
            if (latest.isNullOrBlank()) return false
            if (current.isNullOrBlank()) return true

            val currentSem = parseOrNull(current)
            val latestSem = parseOrNull(latest)

            if (currentSem != null && latestSem != null) {
                return latestSem > currentSem
            }

            // Fallback for non-standard formats: compare numeric parts safely
            return fallbackIsNewer(current, latest)
        }

        private fun fallbackIsNewer(current: String, latest: String): Boolean {
            val cClean = current.trim().removePrefix("v").removePrefix("V")
            val lClean = latest.trim().removePrefix("v").removePrefix("V")

            val currentParts = cClean.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
            val latestParts = lClean.split('.').mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLen) {
                val curr = currentParts.getOrElse(i) { 0 }
                val late = latestParts.getOrElse(i) { 0 }
                if (late > curr) return true
                if (late < curr) return false
            }
            return false
        }
    }
}

