package com.example.survey.ui

import android.os.Environment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.survey.data.Session
import com.example.survey.viewmodel.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class SessionWithCounts(
    val session: Session,
    val photoCount: Int,
    val videoCount: Int,
    val totalFiles: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: CameraViewModel,
    onNavigateToSearch: () -> Unit,
    onNavigateToNewSession: () -> Unit,
    onSessionClick: (String) -> Unit
) {
    val sessions = viewModel.allSessions
    val sessionsSnapshot = sessions.toList()


    var sessionWithFileCounts by remember { mutableStateOf<List<SessionWithCounts>>(emptyList()) }


    val fileCountCache = remember { mutableMapOf<String, Pair<Long, SessionWithCounts>>() }

    var selectedSessions by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedSessions.isNotEmpty()
    val context = LocalContext.current

    LaunchedEffect(sessionsSnapshot) {
        sessionWithFileCounts = withContext(Dispatchers.IO) {
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)

            sessionsSnapshot.map { session ->
                val sessionDir = File(picturesDir, "SurveyApp${File.separator}${session.name}")
                val currentModified = if (sessionDir.exists()) sessionDir.lastModified() else 0L

                val cached = fileCountCache[session.name]
                if (cached != null && cached.first == currentModified) {

                    cached.second.copy(session = session)
                } else {

                    val files = if (sessionDir.exists()) {
                        sessionDir.listFiles { file ->
                            val extension = file.extension.lowercase()
                            extension in listOf("jpg", "jpeg", "png", "mp4", "avi", "mov", "mkv")
                        } ?: arrayOf()
                    } else {
                        arrayOf()
                    }

                    var photoCount = 0
                    var videoCount = 0

                    files.forEach { file ->
                        val ext = file.extension.lowercase()
                        if (ext == "jpg" || ext == "jpeg" || ext == "png") {
                            photoCount++
                        } else if (ext == "mp4" || ext == "avi" || ext == "mov" || ext == "mkv") {
                            videoCount++
                        }
                    }

                    val newCounts = SessionWithCounts(session, photoCount, videoCount, files.size)
                    fileCountCache[session.name] = Pair(currentModified, newCounts)
                    newCounts
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (isSelectionMode) {
                    Text(
                        "${selectedSessions.size} Selected",
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        "Survey Sessions",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            actions = {
                if (isSelectionMode) {
                    IconButton(onClick = {
                        if (selectedSessions.size == sessionWithFileCounts.size) {
                            selectedSessions = emptySet()
                        } else {
                            selectedSessions = sessionWithFileCounts.map { it.session.name }.toSet()
                        }
                    }) {
                        Icon(
                            Icons.Default.SelectAll,
                            contentDescription = "Select All",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = {
                        selectedSessions.forEach { name ->
                            viewModel.deleteSessionWithFiles(context, name)
                            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                            val sessionDir = File(picturesDir, "SurveyApp${File.separator}$name")
                            sessionDir.deleteRecursively()
                        }
                        selectedSessions = emptySet()
                    }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Selected",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(onClick = onNavigateToSearch) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search Sessions",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        if (sessionWithFileCounts.isEmpty() && sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No Sessions Yet",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Create your first session to get started",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onNavigateToNewSession,
                        modifier = Modifier.fillMaxWidth(0.6f)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Create New Session")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(sessionWithFileCounts) { sessionWithCount ->
                    SessionCard(
                        sessionWithCount = sessionWithCount,
                        isSelected = selectedSessions.contains(sessionWithCount.session.name),
                        onLongClick = {
                            val name = sessionWithCount.session.name
                            if (selectedSessions.contains(name)) {
                                selectedSessions = selectedSessions - name
                            } else {
                                selectedSessions = selectedSessions + name
                            }
                        },
                        onClick = {
                            val name = sessionWithCount.session.name
                            if (isSelectionMode) {
                                if (selectedSessions.contains(name)) {
                                    selectedSessions = selectedSessions - name
                                } else {
                                    selectedSessions = selectedSessions + name
                                }
                            } else {
                                onSessionClick(name)
                            }
                        }
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomEnd
            ) {
                FloatingActionButton(
                    onClick = onNavigateToNewSession,
                    modifier = Modifier.padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Session")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionCard(
    sessionWithCount: SessionWithCounts,
    isSelected: Boolean,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val session = sessionWithCount.session

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 4.dp
        )
    ) {
        Column(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open Session",
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Photo,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${sessionWithCount.photoCount} photos",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${sessionWithCount.videoCount} videos",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        .format(Date(session.createdAt)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (sessionWithCount.totalFiles > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (sessionWithCount.totalFiles > 0)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            "${sessionWithCount.totalFiles} files saved",
                            fontSize = 10.sp,
                            color = if (sessionWithCount.totalFiles > 0)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Icon(
                        Icons.Default.Folder,
                        contentDescription = "View Files",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "No files saved yet",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

