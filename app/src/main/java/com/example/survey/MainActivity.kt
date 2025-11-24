package com.example.survey

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.survey.ui.*
import com.example.survey.utils.PermissionHelper
import com.example.survey.viewmodel.CameraViewModel
import com.example.survey.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var permissionHelper: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHelper = PermissionHelper(this)

        if (!permissionHelper.hasAllPermissions()) {
            permissionHelper.requestMissingPermissions()
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val viewModel: CameraViewModel = viewModel()

                    LaunchedEffect(Unit) {
                        viewModel.initializeRepository(this@MainActivity)
                        syncExistingSessionsToDatabase(viewModel)
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateToSearch = {
                                    navController.navigate("search")
                                },
                                onNavigateToNewSession = {
                                    navController.navigate("session_setup")
                                },
                                onSessionClick = { sessionName ->
                                    navController.navigate("session_detail/$sessionName")
                                }
                            )
                        }

                        composable("search") {
                            SearchScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                },
                                onSessionClick = { sessionName ->
                                    navController.navigate("session_detail/$sessionName")
                                }
                            )
                        }

                        composable(
                            route = "session_detail/{sessionName}",
                            arguments = listOf(navArgument("sessionName") {
                                type = NavType.StringType
                            })
                        ) { backStackEntry ->
                            val sessionName = backStackEntry.arguments?.getString("sessionName") ?: ""
                            SessionDetailScreen(
                                sessionName = sessionName,
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("session_setup") {
                            SessionSetupScreen(
                                viewModel = viewModel,
                                onNavigateToCamera = {
                                    if (permissionHelper.hasAllPermissions()) {
                                        navController.navigate("camera")
                                    } else {
                                        permissionHelper.requestMissingPermissions()
                                    }
                                },
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("camera") {
                            CameraScreen(
                                viewModel = viewModel,
                                onNavigateToPreview = {
                                    navController.navigate("preview")
                                },
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }

                        composable("preview") {
                            MediaPreviewScreen(
                                viewModel = viewModel,
                                onBack = {
                                    navController.popBackStack()
                                },
                                onSave = {
                                    navController.navigate("home") {
                                        popUpTo("home") { inclusive = true }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun syncExistingSessionsToDatabase(viewModel: CameraViewModel) {
        withContext(Dispatchers.IO) {
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val appDir = File(picturesDir, "SurveyApp")

                if (!appDir.exists() || !appDir.isDirectory) {
                    Log.d("MainActivity", "No SurveyApp folder found")
                    return@withContext
                }

                val sessionFolders = appDir.listFiles { file ->
                    file.isDirectory
                } ?: return@withContext

                Log.d("MainActivity", "Found ${sessionFolders.size} session folders on disk")

                sessionFolders.forEach { folder ->
                    val sessionName = folder.name

                    val existingSession = viewModel.getSessionByName(sessionName)

                    if (existingSession == null) {
                        val session = Session(
                            name = sessionName,
                            createdAt = folder.lastModified(),
                            mediaItems = mutableListOf()
                        )

                        viewModel.addSessionToDatabase(session)
                        Log.d("MainActivity", "Added existing session to database: $sessionName")
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error syncing sessions", e)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PermissionHelper.PERMISSION_REQUEST_CODE) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }

            if (deniedPermissions.isEmpty()) {
                Log.d("MainActivity", "All permissions granted")
            } else {
                Log.w("MainActivity", "Some permissions denied: $deniedPermissions")
            }
        }
    }
}
