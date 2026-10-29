package eu.stedb.mysyncthing.core

/**
 * The core's error texts for people, in the app's language, where upstream's
 * wording is technical: a full disk is named as such, without database paths
 * and percentages. The core's messages are English; the ones people meet most
 * (a folder that stopped, a file that failed) get the app's words, any other
 * text is kept as it is.
 *
 * Used for a folder's error and a single file's ("syncing: insufficient space
 * in folder ..." when a pull fails on "Keep free"). Upstream: lib/model/folder.go
 * ("insufficient space on disk for database (path): current x % < required y"),
 * lib/config/folderconfiguration.go ("insufficient space in folder ..."), and
 * SQLite's or the OS's "no space left on device". The percentage counts the
 * space this app may use, which excludes what Android reserves, so it can read
 * 0.00 % while a file manager still shows some megabytes free.
 */
fun problemText(raw: String): UiText {
    val required = Regex("< required (\\S+(?: %| ?[kMGT]?B)?)\\s*$").find(raw)?.groupValues?.get(1)?.replace(' ', '\u00A0') // never wrapped from its number
    return when {
        "insufficient space on disk for database" in raw ->
            required?.let { text(R.string.problem_storage_almost_full_keeps, it) } ?: text(R.string.problem_storage_almost_full)
        "insufficient space in folder" in raw ->
            required?.let { text(R.string.problem_folder_space_keeps, it) } ?: text(R.string.problem_folder_space)
        "no space left on device" in raw.lowercase() -> text(R.string.problem_storage_full)
        // lib/config/folderconfiguration.go: a folder's error.
        "folder marker missing" in raw -> text(R.string.problem_folder_marker_missing)
        "folder path missing" in raw -> text(R.string.problem_folder_missing)
        "folder path not a directory" in raw -> text(R.string.problem_folder_not_folder)
        // lib/model/folder_sendrecv.go: a file's error; the OS's EACCES for either.
        "permission denied" in raw.lowercase() -> text(R.string.problem_permission_denied)
        "peers who had this file went away" in raw -> text(R.string.problem_peers_gone)
        "no connected device has the required version" in raw -> text(R.string.problem_no_version)
        "file modified but not rescanned" in raw -> text(R.string.problem_not_rescanned)
        else -> raw(raw)
    }
}

/**
 * A core notice ([SystemError]) for people. A folder whose first scan after
 * the start failed (lib/model/folder.go: `Failed initial scan (error=...
 * folder.label=... folder.id=...)`) is named, with its error in the app's
 * words. Any other notice keeps the core's text, without the log's tail.
 */
fun noticeText(message: String): UiText {
    val (head, values) = noticeParts(message) ?: return raw(message)
    val v = values.toMap()
    val error = v["error"]
    val folder = v["folder.label"] ?: v["folder.id"]
    return when {
        head == "Failed initial scan" && error != null && folder != null ->
            text(R.string.notice_initial_scan_failed, folder, problemText(readableNotice(error)))
        else -> raw(readableNotice(message))
    }
}
