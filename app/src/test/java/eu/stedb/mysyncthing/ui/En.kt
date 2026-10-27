package eu.stedb.mysyncthing.ui

import eu.stedb.mysyncthing.R
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.XmlStrings
import eu.stedb.mysyncthing.core.resolve
import java.io.File
import eu.stedb.mysyncthing.core.R as CoreR

/** The English strings of the app and core modules, as the app shows them. */
val En = XmlStrings(listOf(File("src/main/res"), File("../core/src/main/res")), listOf(R::class.java, CoreR::class.java))

fun UiText.en(): String = resolve(En)

@JvmName("enOrNull")
fun UiText?.en(): String? = this?.resolve(En)
