package tv.frostline.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room

private val Context.frostlineDataStore: DataStore<Preferences> by preferencesDataStore(name = "frostline")

fun Context.vaultDataStore(): DataStore<Preferences> = frostlineDataStore

fun createVault(context: Context): TokenVault =
    TokenVault(context.applicationContext.vaultDataStore(), androidKeystoreCipher())

fun createDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "frostline.db").build()
