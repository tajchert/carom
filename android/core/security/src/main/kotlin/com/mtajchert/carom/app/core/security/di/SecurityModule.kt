package com.mtajchert.carom.app.core.security.di

import android.content.Context
import com.mtajchert.carom.app.core.common.di.CaromDispatchers
import com.mtajchert.carom.app.core.common.di.Dispatcher
import com.mtajchert.carom.app.core.security.AndroidKeystoreCipher
import com.mtajchert.carom.app.core.security.FileSecretVault
import com.mtajchert.carom.app.core.security.SecretVault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object SecurityModule {

    /** `noBackupFilesDir` is excluded from Auto Backup and device transfer by the platform itself. */
    @Provides
    @Singleton
    fun providesSecretVault(
        @ApplicationContext context: Context,
        @Dispatcher(CaromDispatchers.IO) ioDispatcher: CoroutineDispatcher,
    ): SecretVault = FileSecretVault(
        file = File(context.noBackupFilesDir, "carom/vault.bin"),
        cipher = AndroidKeystoreCipher(),
        ioDispatcher = ioDispatcher,
    )
}
