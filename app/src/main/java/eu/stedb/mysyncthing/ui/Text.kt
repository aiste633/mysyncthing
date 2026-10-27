package eu.stedb.mysyncthing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import eu.stedb.mysyncthing.core.Strings
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.resolve
import eu.stedb.mysyncthing.core.strings

/** The words in the current language; read again when the configuration changes. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(currentStrings())

/** Words, numbers and times as the current configuration writes them, for code outside composition. */
@Composable
@ReadOnlyComposable
fun currentStrings(): Strings = LocalContext.current.strings(LocalResources.current)
