package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.XmlStrings
import eu.stedb.mysyncthing.core.resolve
import java.io.File
import eu.stedb.mysyncthing.core.R as CoreR

val RES_DIRS = listOf(File("src/main/res"), File("../core/src/main/res"))
val R_CLASSES = listOf(R::class.java, CoreR::class.java)

/** The English strings of the app and core modules, as the app shows them. */
val En = XmlStrings(RES_DIRS, R_CLASSES)

/** A placeholder as String.format reads it: %1$s, %2$d, %%. */
val PLACEHOLDER = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

fun UiText.en(): String = resolve(En)

@JvmName("enOrNull")
fun UiText?.en(): String? = this?.resolve(En)
