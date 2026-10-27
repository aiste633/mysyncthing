package eu.stedb.mysyncthing.core

/**
 * The core's error texts for people, where upstream's wording is technical:
 * a full disk is named as such, without database paths and percentages. Any
 * other text is kept as it is.
 *
 * Used for a folder's error and a single file's ("syncing: insufficient space
 * in folder ..." when a pull fails on "Keep free"). Upstream: lib/model/folder.go
 * ("insufficient space on disk for database (path): current x % < required y"),
 * lib/config/folderconfiguration.go ("insufficient space in folder ..."), and
 * SQLite's or the OS's "no space left on device". The percentage counts the
 * space this app may use, which excludes what Android reserves, so it can read
 * 0.00 % while a file manager still shows some megabytes free.
 */
fun problemText(raw: String): String {
    val required = Regex("< required (\\S+(?: %| ?[kMGT]?B)?)\\s*$").find(raw)?.groupValues?.get(1)?.replace(' ', '\u00A0') // never wrapped from its number
    val keeps = required?.let { " (Syncthing keeps $it free)" }.orEmpty()
    return when {
        "insufficient space on disk for database" in raw ->
            "The phone's storage is almost full$keeps. Free some space: syncing goes on by itself."
        "insufficient space in folder" in raw ->
            "Too little free space where this folder is$keeps. Free some space, or lower \"Keep free\" in the folder's settings."
        "no space left on device" in raw.lowercase() ->
            "The phone's storage is full. Free some space, then try again."
        else -> raw
    }
}
