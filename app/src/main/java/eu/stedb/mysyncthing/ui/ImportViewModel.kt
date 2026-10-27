package eu.stedb.mysyncthing.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.stedb.mysyncthing.core.ForkImport
import eu.stedb.mysyncthing.core.ForkInspection
import eu.stedb.mysyncthing.core.ForkPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The import wizard's state and work. A ViewModel, so rotation or a font
 * change neither loses the step nor cancels the work; ForkImport.import itself
 * also finishes when the screen goes away. In memory only, never saved state:
 * the steps hold the backup's password. The screen keeps only harmless choices
 * (rememberSaveable), never the password.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val _step = MutableStateFlow<ImportStep>(ImportStep.Choose)
    val step: StateFlow<ImportStep> = _step.asStateFlow()

    /** Numbers the password attempts, so each one starts with fresh field state. */
    private var attempt = 0

    /** What the password field holds. Kept here, not in the field, so it survives recreation. */
    var password by mutableStateOf("")

    /** Reads the backup at [uri] with [typed] as its password (empty when none was asked for yet). */
    fun inspect(uri: Uri, typed: String) {
        if (_step.value is ImportStep.Working) return
        _step.value = ImportStep.Working("Reading the backup")
        viewModelScope.launch {
            val next = when (val r = ForkImport.inspect(getApplication<Application>().contentResolver, uri, typed)) {
                is ForkInspection.Ok -> ImportStep.Review(uri, typed, r.preview)
                ForkInspection.NeedPassword -> ImportStep.Password(uri, ++attempt, tried = "", wrong = false)
                ForkInspection.WrongPassword -> ImportStep.Password(uri, ++attempt, tried = typed, wrong = true)
                is ForkInspection.Failed -> ImportStep.Failed(r.message)
            }
            password = (next as? ImportStep.Password)?.tried.orEmpty()
            _step.value = next
        }
    }

    fun import(review: ImportStep.Review, keepIdentity: Boolean, deleteBackup: Boolean) {
        if (_step.value is ImportStep.Working) return
        _step.value = ImportStep.Working("Importing")
        viewModelScope.launch {
            val problem = ForkImport.import(
                getApplication(), review.uri, review.password, keepIdentity, review.preview.conditions, deleteBackup,
            )
            _step.value = ImportStep.Done(keepIdentity, problem)
        }
    }

    fun restart() {
        if (_step.value is ImportStep.Working) return
        _step.value = ImportStep.Choose
        password = ""
    }
}

sealed interface ImportStep {
    data object Choose : ImportStep

    /** [attempt] keys the field state; [tried] is the password that was [wrong], kept for fixing a typo. */
    data class Password(val uri: Uri, val attempt: Int, val tried: String, val wrong: Boolean) : ImportStep {
        override fun toString() = "Password(attempt=$attempt, wrong=$wrong)"
    }

    data class Review(val uri: Uri, val password: String, val preview: ForkPreview) : ImportStep {
        override fun toString() = "Review(${preview.deviceName})"
    }

    data class Working(val what: String) : ImportStep
    data class Done(val keepIdentity: Boolean, val problem: String?) : ImportStep
    data class Failed(val message: String) : ImportStep
}
