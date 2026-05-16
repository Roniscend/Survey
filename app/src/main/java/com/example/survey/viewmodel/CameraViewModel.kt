package com.example.survey.viewmodel

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import com.example.survey.data.CropData
import com.example.survey.data.MediaItem
import com.example.survey.data.Session
import com.example.survey.data.repository.SurveyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SurveyRepository = SurveyRepository(application)

    private val _uiState = mutableStateOf(CameraUiState())
    val uiState: State<CameraUiState> = _uiState

    private val _capturedMedia = mutableStateListOf<MediaItem>()
    val capturedMedia: List<MediaItem> get() = _capturedMedia

    private val _allSessions = mutableStateListOf<Session>()
    val allSessions: List<Session> get() = _allSessions


    private val _isDataLoaded = MutableStateFlow(false)
    val isDataLoaded: StateFlow<Boolean> = _isDataLoaded.asStateFlow()

    init {
        loadSessionsFromDatabase()
    }

    private fun loadSessionsFromDatabase() {
        viewModelScope.launch {
            repository.getAllSessions().collect { sessions ->
                _allSessions.clear()
                _allSessions.addAll(sessions)



                if (!_isDataLoaded.value) {
                    syncExistingSessionsToDatabase()
                    _isDataLoaded.value = true
                }
            }
        }
    }


    private suspend fun syncExistingSessionsToDatabase() {
        withContext(Dispatchers.IO) {
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val appDir = File(picturesDir, "SurveyApp")

                if (!appDir.exists() || !appDir.isDirectory) {
                    Log.d("CameraViewModel", "No SurveyApp folder found")
                    return@withContext
                }

                val sessionFolders = appDir.listFiles { file ->
                    file.isDirectory
                } ?: return@withContext

                Log.d("CameraViewModel", "Found ${sessionFolders.size} session folders on disk")


                val existingNames = _allSessions.map { it.name }.toSet()

                sessionFolders.forEach { folder ->
                    val sessionName = folder.name

                    if (!existingNames.contains(sessionName)) {

                        val exists = repository.sessionExists(sessionName)

                        if (!exists) {
                            val session = Session(
                                name = sessionName,
                                createdAt = folder.lastModified(),
                                mediaItems = mutableListOf()
                            )
                            repository.insertSession(session)
                            Log.d("CameraViewModel", "Added existing session to database: $sessionName")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error syncing sessions", e)
            }
        }
    }

    fun setSessionName(name: String) {
        _uiState.value = _uiState.value.copy(sessionName = name)
    }

    fun addMediaItem(mediaItem: MediaItem) {
        _capturedMedia.add(mediaItem)

        viewModelScope.launch {
            try {
                repository.insertMediaWithSession(mediaItem)
            } catch (e: Exception) {
                e.printStackTrace()
                _capturedMedia.remove(mediaItem)
            }
        }
    }

    fun removeMediaItem(mediaItem: MediaItem) {
        _capturedMedia.remove(mediaItem)

        viewModelScope.launch {
            try {
                repository.deleteMedia(mediaItem.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun toggleMediaSelection(mediaItem: MediaItem) {
        val index = _capturedMedia.indexOfFirst { it.id == mediaItem.id }
        if (index != -1) {
            _capturedMedia[index] = _capturedMedia[index].copy(
                isSelected = !_capturedMedia[index].isSelected
            )
        }
    }

    fun updateCropData(mediaItem: MediaItem, cropData: CropData) {
        val index = _capturedMedia.indexOfFirst { it.id == mediaItem.id }
        if (index != -1) {
            _capturedMedia[index] = _capturedMedia[index].copy(cropData = cropData)
        }
    }

    fun getCurrentTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
    }

    fun clearCapturedMedia() {
        _capturedMedia.clear()
    }

    fun getSelectedMedia(): List<MediaItem> {
        return _capturedMedia.filter { it.isSelected }
    }

    fun saveSession() {
        viewModelScope.launch {
            val sessionName = _uiState.value.sessionName
            val selectedMedia = getSelectedMedia()

            if (sessionName.isNotEmpty() && selectedMedia.isNotEmpty()) {
                val session = Session(
                    name = sessionName,
                    mediaItems = selectedMedia.toMutableList()
                )
                repository.insertSession(session)

                selectedMedia.forEach { mediaItem ->
                    repository.insertMedia(mediaItem)
                }

                clearCapturedMedia()
            }
        }
    }

    fun deleteSessionWithFiles(context: android.content.Context, sessionName: String) {
        viewModelScope.launch {
            repository.deleteSession(sessionName)
            _allSessions.removeAll { it.name == sessionName }
        }
    }

    fun getSessionByName(name: String): Session? {
        return _allSessions.find { it.name == name }
    }

    fun addSessionToDatabase(session: Session) {
        viewModelScope.launch {
            try {
                repository.insertSession(session)
                Log.d("CameraViewModel", "Session added to database: ${session.name}")
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error adding session to database", e)
            }
        }
    }
}

data class CameraUiState(
    val sessionName: String = "",
    val isRecording: Boolean = false,
    val showPreview: Boolean = false
)

