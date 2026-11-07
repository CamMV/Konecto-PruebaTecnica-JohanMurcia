package com.konecto.series76.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import com.konecto.series76.core.ApplicationScope
import com.konecto.series76.core.IoDispatcher
import com.konecto.series76.data.connectivity.ConnectivityNetworkMonitor
import com.konecto.series76.data.local.db.KonectoDatabase
import com.konecto.series76.data.repository.AuthRepositoryImpl
import com.konecto.series76.data.repository.ChatRepositoryImpl
import com.konecto.series76.data.repository.DebugRepositoryImpl
import com.konecto.series76.data.repository.ServerConfigRepositoryImpl
import com.konecto.series76.domain.repository.AuthRepository
import com.konecto.series76.domain.repository.ChatRepository
import com.konecto.series76.domain.repository.DebugRepository
import com.konecto.series76.domain.repository.NetworkMonitor
import com.konecto.series76.domain.repository.ServerConfigRepository
import com.konecto.series76.sync.SyncManager
import com.konecto.series76.sync.SyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): KonectoDatabase =
        Room.databaseBuilder(context, KonectoDatabase::class.java, KonectoDatabase.NAME)
            // Only a cache of the server: if the schema changes, it is rebuilt from the API.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides @Singleton
    fun dataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope + io) {
        context.preferencesDataStoreFile("konecto_prefs")
    }

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun auth(impl: AuthRepositoryImpl): AuthRepository
    @Binds abstract fun serverConfig(impl: ServerConfigRepositoryImpl): ServerConfigRepository
    @Binds abstract fun chats(impl: ChatRepositoryImpl): ChatRepository
    @Binds abstract fun debug(impl: DebugRepositoryImpl): DebugRepository
    @Binds abstract fun network(impl: ConnectivityNetworkMonitor): NetworkMonitor
    @Binds abstract fun syncScheduler(impl: SyncManager): SyncScheduler
}
