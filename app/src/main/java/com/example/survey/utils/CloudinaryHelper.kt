package com.example.survey.utils

import android.util.Log
import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

import com.cloudinary.android.MediaManager


object CloudinaryHelper {

    private const val TAG = "CloudinaryHelper"


    suspend fun uploadImage(
        file: File,
        folder: String = "survey_selfies",
        publicId: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting upload: ${file.name} to folder: $folder")

            val params = mutableMapOf<String, Any>(
                "folder" to folder,
                "resource_type" to "image",
                "overwrite" to true
            )
            if (publicId != null) {
                params["public_id"] = publicId
            }

            val result = MediaManager.get().cloudinary.uploader().upload(file, params)
            val secureUrl = result["secure_url"] as? String

            Log.d(TAG, "Upload successful: $secureUrl")
            secureUrl
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            null
        }
    }


    suspend fun deleteImage(publicId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = MediaManager.get().cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap())
            val status = result["result"] as? String
            status == "ok"
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed", e)
            false
        }
    }
}

