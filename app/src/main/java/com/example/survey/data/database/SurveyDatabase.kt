package com.example.survey.data.database

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MediaEntity::class, UserEntity::class],
    version = 4,
    exportSchema = false
)
abstract class SurveyDatabase : RoomDatabase() {

    abstract fun surveyDao(): SurveyDao

    companion object {
        @Volatile
        private var INSTANCE: SurveyDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("SurveyDatabase", "Migrating from version 1 to 2")

            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("SurveyDatabase", "Migrating from version 2 to 3")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                Log.d("SurveyDatabase", "Migrating from version 3 to 4 — adding users table")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `users` (
                        `firebaseUid` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `phoneNumber` TEXT NOT NULL,
                        `selfieUrl` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `isPhoneVerified` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`firebaseUid`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): SurveyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SurveyDatabase::class.java,
                    "survey_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

