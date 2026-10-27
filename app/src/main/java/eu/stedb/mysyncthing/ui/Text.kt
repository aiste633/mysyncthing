package eu.stedb.mysyncthing.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import eu.stedb.mysyncthing.core.UiText
import eu.stedb.mysyncthing.core.resolve

/** The words in the current language; read again when the configuration changes. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(LocalResources.current)
