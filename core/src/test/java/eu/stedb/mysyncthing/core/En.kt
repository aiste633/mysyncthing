package eu.stedb.mysyncthing.core

import java.io.File

/** English as res/values holds it. Tests run in the module's directory. */
val En = XmlStrings(listOf(File("src/main/res")), listOf(R::class.java))

fun UiText.en(): String = resolve(En)

@JvmName("enOrNull")
fun UiText?.en(): String? = this?.resolve(En)
