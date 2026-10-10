package com.mtajchert.carom.app.core.database.di

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.mtajchert.carom.app.core.database.CaromDatabase
import com.mtajchert.carom.app.core.database.dao.DirectoryDao
import com.mtajchert.carom.app.core.database.dao.NotificationAppDao
import com.mtajchert.carom.app.core.database.dao.OutboxDao
import com.mtajchert.carom.app.core.database.dao.RecentItemDao
import com.mtajchert.carom.app.core.database.dao.SeenMessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {

    /** Add migrations here as the schema evolves; never fall back to destructive migration. */
    @Provides
    @Singleton
    fun providesCaromDatabase(@ApplicationContext context: Context): CaromDatabase =
        Room.databaseBuilder<CaromDatabase>(context, CaromDatabase.NAME)
            .setDriver(AndroidSQLiteDriver())
            .build()

    @Provides
    fun providesOutboxDao(db: CaromDatabase): OutboxDao = db.outboxDao()

    @Provides
    fun providesSeenMessageDao(db: CaromDatabase): SeenMessageDao = db.seenMessageDao()

    @Provides
    fun providesDirectoryDao(db: CaromDatabase): DirectoryDao = db.directoryDao()

    @Provides
    fun providesNotificationAppDao(db: CaromDatabase): NotificationAppDao = db.notificationAppDao()

    @Provides
    fun providesRecentItemDao(db: CaromDatabase): RecentItemDao = db.recentItemDao()
}
