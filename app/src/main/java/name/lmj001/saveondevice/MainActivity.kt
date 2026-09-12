package name.lmj001.saveondevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

/**
 * Custom contract to launch the system file picker for creating a new document
 */
class CreateDocumentDynamicMime : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mimeType, fileName) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

class MainActivity : ComponentActivity() {
    private var saveIndividually: Boolean = false   // Tracks if multiple incoming files should be saved sequentially instead of all at once
    private var inputUris = mutableListOf<Uri>()    // Tracks the queue of files that need to be saved
    private var sharedText: String? = null          // Tracks any shared text that needs to be written to a file

    // UI state for holding error messages to be displayed in the error dialog.
    private val errorMessage = mutableStateOf<String?>(null)    // UI state for holding error messages to be displayed in the error dialog

    // Registers the callback for saving a single file. Once the user selects a location, it triggers the file copy operation
    private val createDocumentLauncher = registerForActivityResult(CreateDocumentDynamicMime()) { uri ->
        if (uri != null) {
            saveSingleFile(uri)
        } else {
            finish()
        }
    }

    // Registers the callback for selecting a folder tree to save multiple files at once
    private val openDocumentTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            saveMultipleFiles(uri)
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Loads user preferences for saving individually
        val settings = getSharedPreferences("set", MODE_PRIVATE)
        saveIndividually = settings.getBoolean("saveIndividually", false)

        val action = intent.action
        
        // Handles receiving a single file or text snippet shared from another app
        if (Intent.ACTION_VIEW == action) {
            intent.data?.let {
                inputUris.add(it)
                launchCreateDocument(it)
            } ?: finish()
        } else if (Intent.ACTION_SEND == action) {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) {
                    inputUris.add(uri)
                    launchCreateDocument(uri)
                } else {
                    finish()
                }
            } else if (intent.hasExtra(Intent.EXTRA_TEXT)) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (sharedText != null) {
                    val fileName = generateFileNameFromText(sharedText!!)
                    launchCreateDocumentText(fileName)
                } else {
                    Toast.makeText(this, R.string.nothing, Toast.LENGTH_LONG).show()
                    finish()
                }
            } else {
                finish()
            }
        // Handles receiving multiple files shared at once
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (!uris.isNullOrEmpty()) {
                    inputUris.addAll(uris)
                    if (saveIndividually) {
                        launchCreateDocument(inputUris.first())
                    } else {
                        openDocumentTreeLauncher.launch(null)
                    }
                } else {
                    finish()
                }
            } else {
                finish()
            }
        } else {
            // Main UI
            setContent {
                val context = LocalContext.current
                val colorScheme = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                    }
                    isSystemInDarkTheme() -> darkColorScheme()
                    else -> lightColorScheme()
                }
                
                MaterialTheme(colorScheme = colorScheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MainScreen(
                            initialSaveIndividually = saveIndividually,
                            onSaveIndividuallyChanged = { isChecked ->
                                saveIndividually = isChecked
                                settings.edit { putBoolean("saveIndividually", isChecked) }
                            }
                        )
                    }
                    
                    ErrorDialog(errorMessage.value) {
                        errorMessage.value = null
                        finish()
                    }
                }
            }
            return
        }

        // Setup clear UI for processing intents
        // Shows a blank screen with error handling capability when processing background intents so the app doesn't flash a settings screen during a share operation
        setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                ErrorDialog(errorMessage.value) {
                    errorMessage.value = null
                    finish()
                }
            }
        }
    }

    // Generates a safe file name from shared text content by sanitizing special characters
    private fun generateFileNameFromText(text: String): String {
        var fileName = text.take(20)
        fileName = Normalizer.normalize(fileName, Normalizer.Form.NFD)
            .replace("[^\\p{ASCII}]".toRegex(), "")
            .replace("[^a-zA-Z0-9\\s]+".toRegex(), "")
            .trim()
            .replace("\\s+".toRegex(), "-")
            .lowercase(Locale.getDefault())
        return "$fileName.txt"
    }

    private fun launchCreateDocumentText(fileName: String) {
        createDocumentLauncher.launch("text/plain" to fileName)
    }

    // Determines the MIME type of the incoming file and launches the single file picker
    private fun launchCreateDocument(uri: Uri) {
        val fileName = getOriginalFileName(this, uri)
        var mimeType = contentResolver.getType(uri)
        if (mimeType.isNullOrEmpty()) {
            val extension = fileName.substringAfterLast('.', "")
            if (extension.isNotEmpty()) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.getDefault()))
            }
        }
        if (mimeType.isNullOrEmpty()) {
            mimeType = "application/octet-stream"
        }
        createDocumentLauncher.launch(mimeType to fileName)
    }

    // Asynchronously copies the content from the source URI to the destination URI. If there are remaining files (for individual saving mode), it triggers the picker for the next one
    private fun saveSingleFile(outputUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentInputUri = inputUris.firstOrNull()
                contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    if (currentInputUri != null) {
                        contentResolver.openInputStream(currentInputUri)?.use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    } else if (sharedText != null) {
                        outputStream.write(sharedText!!.toByteArray())
                    }
                    Unit
                }
                
                if (inputUris.isNotEmpty()) {
                    inputUris.removeAt(0)
                }

                withContext(Dispatchers.Main) {
                    if (inputUris.isEmpty()) {
                        Toast.makeText(this@MainActivity, R.string.success, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        launchCreateDocument(inputUris.first())
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(e)
                }
            }
        }
    }

    // Asynchronously copies all shared files into the single folder tree chosen by the user
    private fun saveMultipleFiles(treeUri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )

                for (inputUri in inputUris) {
                    val fileName = getOriginalFileName(this@MainActivity, inputUri)
                    val newFileUri = DocumentsContract.createDocument(contentResolver, docUri, "*/*", fileName)
                    if (newFileUri != null) {
                        contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                            contentResolver.openInputStream(inputUri)?.use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                            Unit
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.success, Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError(e)
                }
            }
        }
    }

    // Extracts the stack trace and updates the error state to display the error dialog
    private fun showError(e: Exception) {
        val sb = StringBuilder(e.toString())
        for (ste in e.stackTrace) {
            sb.append('\n').append(ste)
        }
        errorMessage.value = sb.toString()
    }

    // Queries the Android content resolver to find the original display name of the shared file
    private fun getOriginalFileName(context: Context, uri: Uri): String {
        var result: String? = null
        try {
            if (uri.scheme == "content") {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            result = cursor.getString(index)
                        }
                    }
                }
            }
            if (result == null) {
                result = uri.path
                val cut = result?.lastIndexOf('/') ?: -1
                if (cut != -1) {
                    result = result?.substring(cut + 1)
                }
            }
        } catch (ignored: Exception) {
        }
        return result ?: "filename_not_found"
    }
}

/**
 * Declarative UI for the main settings screen
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(initialSaveIndividually: Boolean, onSaveIndividuallyChanged: (Boolean) -> Unit) {
    var saveIndividually by remember { mutableStateOf(initialSaveIndividually) }

    Scaffold() { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                text = stringResource(id = R.string.switchText),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = saveIndividually,
                onCheckedChange = { 
                    saveIndividually = it
                    onSaveIndividuallyChanged(it)
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = stringResource(id = R.string.multiSaveInfo),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary
        )
    }
    }
}

// Declarative UI component for showing errors
@Composable
fun ErrorDialog(errorMsg: String?, onDismiss: () -> Unit) {
    if (errorMsg != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.err)) },
            text = { Text(errorMsg) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            },
            containerColor = Color.Black,
            titleContentColor = Color(0xFF691383),
            textContentColor = Color(0xFF691383)
        )
    }
}