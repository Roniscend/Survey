package com.example.survey.viewmodel

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.survey.data.database.SurveyDatabase
import com.example.survey.data.database.UserEntity
import com.example.survey.utils.CloudinaryHelper
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class AuthUiState(
    val isLoading: Boolean = true,
    val isLoggedIn: Boolean = false,
    val isProfileComplete: Boolean = false,
    val user: UserEntity? = null,
    val error: String? = null,
    val isSelfieUploading: Boolean = false,

    val isPhoneVerified: Boolean = false,
    val isOtpSent: Boolean = false,
    val isSendingOtp: Boolean = false,
    val isVerifyingOtp: Boolean = false,
    val phoneVerificationError: String? = null
)

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "AuthViewModel"
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val dao = SurveyDatabase.getDatabase(application).surveyDao()

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()


    private var storedVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    init {
        checkAuthState()
    }


    private fun checkAuthState() {
        viewModelScope.launch {
            val firebaseUser = auth.currentUser
            if (firebaseUser != null) {

                val userEntity = withContext(Dispatchers.IO) {
                    dao.getUserByUid(firebaseUser.uid)
                }
                _uiState.value = AuthUiState(
                    isLoading = false,
                    isLoggedIn = true,
                    isProfileComplete = userEntity != null,
                    user = userEntity
                )
            } else {
                _uiState.value = AuthUiState(
                    isLoading = false,
                    isLoggedIn = false,
                    isProfileComplete = false
                )
            }
        }
    }


    fun signInWithEmail(email: String, password: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "signInWithEmail:success")
                    checkAuthState()
                } else {
                    Log.w(TAG, "signInWithEmail:failure", task.exception)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = task.exception?.message ?: "Authentication failed"
                    )
                }
            }
    }


    fun createAccountWithEmail(email: String, password: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "createAccount:success")
                    checkAuthState()
                } else {
                    Log.w(TAG, "createAccount:failure", task.exception)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = task.exception?.message ?: "Account creation failed"
                    )
                }
            }
    }




    fun sendVerificationCode(phoneNumber: String, activity: Activity) {
        _uiState.value = _uiState.value.copy(
            isSendingOtp = true,
            phoneVerificationError = null,
            isOtpSent = false
        )

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

            override fun onVerificationCompleted(credential: PhoneAuthCredential) {

                Log.d(TAG, "onVerificationCompleted")
                _uiState.value = _uiState.value.copy(
                    isSendingOtp = false,
                    isPhoneVerified = true,
                    isOtpSent = false
                )
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Log.w(TAG, "onVerificationFailed", e)
                _uiState.value = _uiState.value.copy(
                    isSendingOtp = false,
                    phoneVerificationError = e.message ?: "Verification failed"
                )
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                Log.d(TAG, "onCodeSent: $verificationId")
                storedVerificationId = verificationId
                resendToken = token
                _uiState.value = _uiState.value.copy(
                    isSendingOtp = false,
                    isOtpSent = true
                )
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }


    fun resendVerificationCode(phoneNumber: String, activity: Activity) {
        _uiState.value = _uiState.value.copy(
            isSendingOtp = true,
            phoneVerificationError = null
        )

        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                _uiState.value = _uiState.value.copy(
                    isSendingOtp = false,
                    isPhoneVerified = true,
                    isOtpSent = false
                )
            }

            override fun onVerificationFailed(e: FirebaseException) {
                _uiState.value = _uiState.value.copy(
                    isSendingOtp = false,
                    phoneVerificationError = e.message ?: "Verification failed"
                )
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                storedVerificationId = verificationId
                resendToken = token
                _uiState.value = _uiState.value.copy(
                    isSendingOtp = false,
                    isOtpSent = true
                )
            }
        }

        val builder = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)

        resendToken?.let { builder.setForceResendingToken(it) }

        PhoneAuthProvider.verifyPhoneNumber(builder.build())
    }


    fun verifyOtpCode(code: String) {
        val verificationId = storedVerificationId
        if (verificationId == null) {
            _uiState.value = _uiState.value.copy(
                phoneVerificationError = "No verification ID. Please resend the code."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isVerifyingOtp = true,
            phoneVerificationError = null
        )

        val credential = PhoneAuthProvider.getCredential(verificationId, code)


        val currentUser = auth.currentUser
        if (currentUser != null) {
            currentUser.linkWithCredential(credential)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Phone linked successfully")
                        _uiState.value = _uiState.value.copy(
                            isVerifyingOtp = false,
                            isPhoneVerified = true,
                            isOtpSent = false
                        )
                    } else {

                        Log.w(TAG, "linkWithCredential failed, trying signIn", task.exception)
                        auth.signInWithCredential(credential)
                            .addOnCompleteListener { signInTask ->
                                _uiState.value = _uiState.value.copy(
                                    isVerifyingOtp = false,
                                    isPhoneVerified = signInTask.isSuccessful,
                                    phoneVerificationError = if (!signInTask.isSuccessful)
                                        signInTask.exception?.message ?: "Invalid OTP"
                                    else null
                                )
                            }
                    }
                }
        } else {

            auth.signInWithCredential(credential)
                .addOnCompleteListener { task ->
                    _uiState.value = _uiState.value.copy(
                        isVerifyingOtp = false,
                        isPhoneVerified = task.isSuccessful,
                        phoneVerificationError = if (!task.isSuccessful)
                            task.exception?.message ?: "Invalid OTP"
                        else null
                    )
                }
        }
    }

    fun resetPhoneVerification() {
        storedVerificationId = null
        resendToken = null
        _uiState.value = _uiState.value.copy(
            isPhoneVerified = false,
            isOtpSent = false,
            isSendingOtp = false,
            isVerifyingOtp = false,
            phoneVerificationError = null
        )
    }

    fun clearPhoneVerificationError() {
        _uiState.value = _uiState.value.copy(phoneVerificationError = null)
    }




    fun completeProfile(
        name: String,
        phoneNumber: String,
        selfieFile: File,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            onError("Not authenticated")
            return
        }

        _uiState.value = _uiState.value.copy(isSelfieUploading = true, error = null)

        viewModelScope.launch {
            try {

                val selfieUrl = CloudinaryHelper.uploadImage(
                    file = selfieFile,
                    folder = "survey_selfies",
                    publicId = "user_${firebaseUser.uid}"
                )

                if (selfieUrl == null) {
                    _uiState.value = _uiState.value.copy(
                        isSelfieUploading = false,
                        error = "Failed to upload selfie"
                    )
                    onError("Failed to upload selfie. Check your internet connection.")
                    return@launch
                }


                val userEntity = UserEntity(
                    firebaseUid = firebaseUser.uid,
                    name = name,
                    phoneNumber = phoneNumber,
                    selfieUrl = selfieUrl,
                    email = firebaseUser.email ?: "",
                    isPhoneVerified = _uiState.value.isPhoneVerified
                )

                withContext(Dispatchers.IO) {
                    dao.insertUser(userEntity)
                }

                _uiState.value = _uiState.value.copy(
                    isSelfieUploading = false,
                    isProfileComplete = true,
                    user = userEntity
                )

                Log.d(TAG, "Profile complete: $name, selfie: $selfieUrl")
                onSuccess()
            } catch (e: Throwable) {
                Log.e(TAG, "Error completing profile", e)
                _uiState.value = _uiState.value.copy(
                    isSelfieUploading = false,
                    error = e.message
                )
                onError(e.message ?: "Unknown error")
            }
        }
    }

    fun signOut() {
        auth.signOut()
        _uiState.value = AuthUiState(
            isLoading = false,
            isLoggedIn = false,
            isProfileComplete = false
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun getCurrentFirebaseUser(): FirebaseUser? = auth.currentUser
}

