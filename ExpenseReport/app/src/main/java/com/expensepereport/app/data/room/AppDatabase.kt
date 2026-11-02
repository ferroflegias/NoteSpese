package com.expensepereport.app.data.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SpesaEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun spesaDao(): SpesaDao

    companion object {
        const val DATABASE_NAME = "expense_report.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                    // No fallbackToDestructiveMigration(): a schema change without a migration
                    // must fail loudly instead of silently deleting all the expenses
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
