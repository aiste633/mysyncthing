package eu.stedb.mysyncthing.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import eu.stedb.mysyncthing.core.UiText

// Every text field of the app is one of these two, so none goes without a
// length limit: [limit] has no default, and InputLimitTest fails on an
// OutlinedTextField anywhere else. The field's own check must refuse the
// same length (Save stays off); here it is also shown, ahead of [error], so
// "At most N characters" is what a too long value says.

/**
 * A text field with its length [limit]. [error] is the field's own problem to
 * show now (null while there is none or it isn't shown yet), [hint] the text
 * under the field otherwise.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    limit: Limit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelContent: (@Composable () -> Unit)? = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    placeholder: String? = null,
    error: UiText? = null,
    hint: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val shown = (lengthError(value, limit) ?: error)?.asString()
    OutlinedTextField(
        value, onValueChange, modifier, enabled = enabled, readOnly = readOnly, label = labelContent,
        placeholder = placeholder?.let { { Text(it) } },
        isError = shown != null, supportingText = (shown ?: hint)?.let { { Text(it) } },
        singleLine = singleLine, minLines = minLines, keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
    )
}

/** [TextInput] for text edited with its selection, such as a folder's name or the ignore patterns. */
@Composable
fun TextInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    limit: Limit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = false,
    placeholder: String? = null,
    error: UiText? = null,
    hint: String? = null,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
) {
    val shown = (lengthError(value.text, limit) ?: error)?.asString()
    OutlinedTextField(
        value, onValueChange, modifier, enabled = enabled, readOnly = readOnly, textStyle = textStyle,
        label = label?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } }, singleLine = singleLine,
        placeholder = placeholder?.let { { Text(it) } },
        isError = shown != null, supportingText = (shown ?: hint)?.let { { Text(it) } },
    )
}
