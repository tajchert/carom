package com.mtajchert.carom.app.core.database.di

import android.content.Context
import com.mtajchert.carom.app.core.database.DemoTaskSeed
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.mtajchert.carom.app.core.database.TaskDatabase
import com.mtajchert.carom.app.core.database.migration.MIGRATION_1_2
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the Room database instance.
 *
 * ## Singleton Scope
 *
 * The database is scoped as [Singleton] because:
 * 1. Room database creation is expensive
 * 2. All DAOs should share the same database connection
 * 3. Multiple instances would cause data inconsistencies
 *
 * ## Build Patterns
 *
 * For development, you might add `.setQueryCallback()` for SQL logging.
 *
 * For production, ensure proper migrations are in place.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    /**
     * Provides the singleton Room database instance.
     */
    @Provides
    @Singleton
    fun providesTaskDatabase(
        @ApplicationContext context: Context,
    ): TaskDatabase = Room.databaseBuilder<TaskDatabase>(context, "carom-database")
        .setDriver(AndroidSQLiteDriver())
        .addMigrations(MIGRATION_1_2)
        .addCallback(DemoTaskSeed)
        .build()
}
