package com.kitalonlabs.neogold.core.data.migration

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.kitalonlabs.neogold.core.data.local.NeoGoldDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TC-REL-08: Upgrade migrations harness.
 *
 * Verifies that the Room database schema v1 can be created from exported schema JSON assets
 * and that all 9 required tables and schema constraints are properly initialized.
 * Ready for future schema version upgrades via [MigrationTestHelper].
 */
@RunWith(RobolectricTestRunner::class)
class MigrationHarnessTest {

    private val TEST_DB = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NeoGoldDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun tcRel08_createSchemaVersion1_verifiesAllNineTablesExist() {
        val db = helper.createDatabase(TEST_DB, 1)

        val expectedTables = listOf(
            "device_profile",
            "card_batch",
            "research_study",
            "research_participant",
            "screening_session",
            "capture",
            "tsb_measurement",
            "outbox",
            "audit_event"
        )

        for (table in expectedTables) {
            val cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                arrayOf(table)
            )
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.close()
        }

        db.close()
    }

    @Test
    fun tcRel08_schemaVersion1_supportsDataInsertionAndQuery() {
        val db = helper.createDatabase(TEST_DB, 1)

        val batchId = "BATCH-001"
        db.execSQL(
            """
            INSERT INTO card_batch (
                batch_id, issuer_id, format_version, reference_values,
                issued_at, expires_at, signature_valid, qr_payload,
                server_status, first_seen_at
            ) VALUES (
                '$batchId', 'ISSUER_1', 1, '[]',
                1000, 2000, 1, X'010203',
                'ACCEPTED', 1000
            )
            """.trimIndent()
        )

        val cursor = db.query("SELECT batch_id, server_status FROM card_batch WHERE batch_id = ?", arrayOf(batchId))
        assertThat(cursor.moveToFirst()).isTrue()
        assertThat(cursor.getString(0)).isEqualTo(batchId)
        assertThat(cursor.getString(1)).isEqualTo("ACCEPTED")
        cursor.close()

        db.close()
    }
}
