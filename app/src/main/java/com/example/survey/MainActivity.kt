package com.example.survey

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.survey.ui.*
import com.example.survey.utils.PermissionHelper
import com.example.survey.viewmodel.AuthViewModel
import com.example.survey.viewmodel.CameraViewModel

import com.cloudinary.android.MediaManager

class MainActivity : ComponentActivity() {

    private lateinit var permissionHelper: PermissionHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val config = mapOf(
                "cloud_name" to "dvqjhrxqh",
                "api_key" to "588838467451375",
                "api_secret" to "NQSNBsQQd7hkHFQvWFCSIjf7lfc"
            )
            MediaManager.init(this, config)
        } catch (e: Exception) {

        }

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
                    val cameraViewModel: CameraViewModel = viewModel()
                    val authViewModel: AuthViewModel = viewModel()

                    val authState by authViewModel.uiState.collectAsState()
                    val isDataLoaded by cameraViewModel.isDataLoaded.collectAsState()


                    if (authState.isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {

                        val startDestination = remember(authState.isLoggedIn, authState.isProfileComplete) {
                            when {
                                !authState.isLoggedIn -> "login"
                                !authState.isProfileComplete -> "profile_setup"
                                else -> "home"
                            }
                        }



                        LaunchedEffect(authState.isLoggedIn, authState.isProfileComplete) {
                            val currentRoute = navController.currentBackStackEntry?.destination?.route
                            when {
                                authState.isLoggedIn && !authState.isProfileComplete && currentRoute == "login" -> {
                                    navController.navigate("profile_setup") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                }
                                authState.isLoggedIn && authState.isProfileComplete && (currentRoute == "login" || currentRoute == "profile_setup") -> {
                                    navController.navigate("home") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                                !authState.isLoggedIn && currentRoute != "login" -> {
                                    navController.navigate("login") {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            }
                        }

                        NavHost(
                            navController = navController,
                            startDestination = startDestination
                        ) {

                            composable("login") {
                                LoginScreen(authViewModel = authViewModel)
                            }

                            composable("profile_setup") {
                                ProfileSetupScreen(
                                    authViewModel = authViewModel,
                                    onProfileComplete = {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                )
                            }


                            composable("home") {
                                if (!isDataLoaded) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                } else {
                                    HomeScreen(
                                        viewModel = cameraViewModel,
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
                            }

                            composable("search") {
                                SearchScreen(
                                    viewModel = cameraViewModel,
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
                                    viewModel = cameraViewModel,
                                    onNavigateToCamera = {
                                        cameraViewModel.setSessionName(sessionName)
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

                            composable("session_setup") {
                                SessionSetupScreen(
                                    viewModel = cameraViewModel,
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
                                    viewModel = cameraViewModel,
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
                                    viewModel = cameraViewModel,
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

