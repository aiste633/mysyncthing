package eu.stedb.mysyncthing.ui

import java.util.Locale

/** SI (base-1000) byte sizes. */
fun bytes(n: Long): String {
    if (n < 1000) return "$n B"
    val units = "kMGTPE"
    var v = n.toDouble()
    var i = -1
    while (v >= 1000 && i < units.length - 1) { v /= 1000; i++ }
    return String.format(Locale.ROOT, "%.1f %sB", v, units[i])
}

/** Syncthing's grouped display form, e.g. ABCDEFG-HIJKLMN. Only the first group is shown in lists. */
fun shortId(id: String): String = id.substringBefore('-')
