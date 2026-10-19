package com.kitalonlabs.neogold.core.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.kitalonlabs.neogold.core.data.local.dao.AuditEventDao
import com.kitalonlabs.neogold.core.data.local.dao.CardBatchDao
import com.kitalonlabs.neogold.core.data.local.dao.CaptureDao
import com.kitalonlabs.neogold.core.data.local.dao.DeviceProfileDao
import com.kitalonlabs.neogold.core.data.local.dao.OutboxDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchParticipantDao
import com.kitalonlabs.neogold.core.data.local.dao.ResearchStudyDao
import com.kitalonlabs.neogold.core.data.local.dao.ScreeningSessionDao
import com.kitalonlabs.neogold.core.data.local.dao.TsbMeasurementDao
import com.kitalonlabs.neogold.core.data.local.entity.AuditEventEntity
import com.kitalonlabs.neogold.core.data.local.entity.CardBatchEntity
import com.kitalonlabs.neogold.core.data.local.entity.CaptureEntity
import com.kitalonlabs.neogold.core.data.local.entity.DeviceProfileEntity
import com.kitalonlabs.neogold.core.data.local.entity.OutboxEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchParticipantEntity
import com.kitalonlabs.neogold.core.data.local.entity.ResearchStudyEntity
import com.kitalonlabs.neogold.core.data.local.entity.ScreeningSessionEntity
import com.kitalonlabs.neogold.core.data.local.entity.TsbMeasurementEntity
import com.kitalonlabs.neogold.core.security.DatabaseKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        DeviceProfileEntity::class,
        CardBatchEntity::class,
        ResearchStudyEntity::class,
        ResearchParticipantEntity::class,
        ScreeningSessionEntity::class,
        CaptureEntity::class,
        TsbMeasurementEntity::class,
        OutboxEntity::class,
        AuditEventEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class NeoGoldDatabase : RoomDatabase() {

    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun cardBatchDao(): CardBatchDao
    abstract fun researchStudyDao(): ResearchStudyDao
    abstract fun researchParticipantDao(): ResearchParticipantDao
    abstract fun screeningSessionDao(): ScreeningSessionDao
    abstract fun captureDao(): CaptureDao
    abstract fun tsbMeasurementDao(): TsbMeasurementDao
    abstract fun outboxDao(): OutboxDao
    abstract fun auditEventDao(): AuditEventDao

    companion object {
        const val DATABASE_NAME = "neogold.db"

        fun buildDatabase(
            context: Context,
            databaseKeyProvider: DatabaseKeyProvider,
            databaseName: String = DATABASE_NAME,
            openHelperFactory: SupportSQLiteOpenHelper.Factory? = null
        ): NeoGoldDatabase {
            val factory = openHelperFactory ?: SupportOpenHelperFactory(databaseKeyProvider.getSqlCipherPassphrase())
            return Room.databaseBuilder(context, NeoGoldDatabase::class.java, databaseName)
                .openHelperFactory(factory)
                .addCallback(DatabaseCallback)
                .build()
        }

        fun buildInMemory(
            context: Context,
            openHelperFactory: SupportSQLiteOpenHelper.Factory? = null
        ): NeoGoldDatabase {
            val builder = Room.inMemoryDatabaseBuilder(context, NeoGoldDatabase::class.java)
                .addCallback(DatabaseCallback)
            if (openHelperFactory != null) {
                builder.openHelperFactory(openHelperFactory)
            }
            return builder.build()
        }
    }

    object DatabaseCallback : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            db.execSQL("PRAGMA foreign_keys = ON;")
        }

        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            db.execSQL("PRAGMA foreign_keys = ON;")

            // Install SQLite CHECK constraint triggers matching doc 5 Section 2
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS check_screening_session_insert
                BEFORE INSERT ON screening_session
                FOR EACH ROW
                BEGIN
                    SELECT CASE
                        WHEN NOT (NEW.age_hours >= 0.0 AND NEW.age_hours <= 720.0)
                            THEN RAISE(ABORT, 'CHECK constraint failed: age_hours must be between 0 and 720')
                        WHEN NOT (NEW.gestational_age_weeks >= 22 AND NEW.gestational_age_weeks <= 44)
                            THEN RAISE(ABORT, 'CHECK constraint failed: gestational_age_weeks must be between 22 and 44')
                        WHEN NOT (NEW.mode = 'SCREENING' OR NEW.participant_id IS NOT NULL)
                            THEN RAISE(ABORT, 'CHECK constraint failed: research sessions require participant_id')
                        WHEN NOT (NEW.mode = 'RESEARCH' OR NEW.participant_id IS NULL)
                            THEN RAISE(ABORT, 'CHECK constraint failed: screening sessions cannot have participant_id')
                        WHEN NOT (NEW.mode = 'SCREENING' OR NEW.register_number IS NULL)
                            THEN RAISE(ABORT, 'CHECK constraint failed: research sessions cannot have register_number')
                    END;
                END;
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS check_capture_insert
                BEFORE INSERT ON capture
                FOR EACH ROW
                BEGIN
                    SELECT CASE
                        WHEN NOT (NEW.image_file IS NULL OR NEW.image_sha256 IS NOT NULL)
                            THEN RAISE(ABORT, 'CHECK constraint failed: image_file requires image_sha256')
                        WHEN NOT (NEW.attempt_number >= 1 AND NEW.attempt_number <= 3)
                            THEN RAISE(ABORT, 'CHECK constraint failed: attempt_number must be between 1 and 3')
                    END;
                END;
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS check_tsb_measurement_insert
                BEFORE INSERT ON tsb_measurement
                FOR EACH ROW
                BEGIN
                    SELECT CASE
                        WHEN NOT (NEW.tsb_mg_dl > 0.0 AND NEW.tsb_mg_dl <= 40.0)
                            THEN RAISE(ABORT, 'CHECK constraint failed: tsb_mg_dl must be > 0 and <= 40')
                    END;
                END;
                """.trimIndent()
            )
        }
    }
}
