package com.example.survey.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val firebaseUid: String,
    val name: String,
    val phoneNumber: String,
    val selfieUrl: String,
    val email: String = "",
    val isPhoneVerified: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

