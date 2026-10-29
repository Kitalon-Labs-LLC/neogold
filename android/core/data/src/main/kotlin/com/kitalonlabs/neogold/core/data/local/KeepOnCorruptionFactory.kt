package com.kitalonlabs.neogold.core.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Wraps an open helper factory so that a database that cannot be opened is never deleted by the library.
 *
 * By default a SQLite open helper deletes the database file when it reports corruption, and a database opened with the
 * wrong key reports exactly that ("file is not a database"). The app promises the opposite (doc 8 Section 5.1): it
 * checks the keys, says plainly that saved data could not be opened, and deletes nothing until the user chooses Reset.
 * With this wrapper the open fails, the app shows "Saved data could not be opened", and the file is still there for a
 * retry or for the user's decision (TC-SEC-07).
 */
internal class KeepOnCorruptionFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val room = configuration.callback
        val callback = object : SupportSQLiteOpenHelper.Callback(room.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)

            override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                room.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)

            override fun onCorruption(db: SupportSQLiteDatabase) {
                // Deliberately nothing: the default deletes the file. The open then fails and the user decides.
            }
        }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                .name(configuration.name)
                .callback(callback)
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                .build()
        )
    }
}
