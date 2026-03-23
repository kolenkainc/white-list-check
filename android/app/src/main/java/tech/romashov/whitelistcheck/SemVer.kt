package tech.romashov.whitelistcheck

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    companion object {
        fun parse(raw: String): SemVer? {
            val v = raw.trim().removePrefix("v").trim()
            if (v.isEmpty()) return null
            val parts = v.split('.')
            val ma = parts.getOrNull(0)?.toIntOrNull() ?: return null
            val mi = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val pa = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return SemVer(ma, mi, pa)
        }
    }
}
