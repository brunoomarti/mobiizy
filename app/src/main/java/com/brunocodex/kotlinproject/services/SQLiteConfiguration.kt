package com.brunocodex.kotlinproject.services

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SQLiteConfiguration private constructor(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "kotlinproject.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_VEHICLES = "vehicle_objects"
        private const val COL_VEHICLE_ID = "vehicle_id"
        private const val COL_OWNER_ID = "owner_id"
        private const val COL_PLATE = "plate"
        private const val COL_STATUS = "status"
        private const val COL_PAYLOAD_JSON = "payload_json"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_DELETED = "deleted"
        private const val COL_SYNC_STATE = "sync_state"

        const val STATUS_DRAFT = "draft"
        const val STATUS_PUBLISHED = "published"

        const val SYNC_STATE_PENDING = "pending"
        const val SYNC_STATE_SYNCED = "synced"

        @Volatile
        private var instance: SQLiteConfiguration? = null

        fun getInstance(context: Context): SQLiteConfiguration {
            return instance ?: synchronized(this) {
                instance ?: SQLiteConfiguration(context.applicationContext).also { instance = it }
            }
        }
    }

    data class VehicleRow(
        val vehicleId: String,
        val ownerId: String,
        val plate: String,
        val status: String,
        val payloadJson: String,
        val updatedAt: Long,
        val deleted: Boolean,
        val syncState: String
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_VEHICLES (
                $COL_VEHICLE_ID TEXT PRIMARY KEY,
                $COL_OWNER_ID TEXT NOT NULL,
                $COL_PLATE TEXT NOT NULL,
                $COL_STATUS TEXT NOT NULL,
                $COL_PAYLOAD_JSON TEXT NOT NULL,
                $COL_UPDATED_AT INTEGER NOT NULL,
                $COL_DELETED INTEGER NOT NULL DEFAULT 0,
                $COL_SYNC_STATE TEXT NOT NULL DEFAULT '$SYNC_STATE_PENDING'
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_VEHICLES")
        onCreate(db)
    }

    fun upsertVehicle(row: VehicleRow) {
        val values = ContentValues().apply {
            put(COL_VEHICLE_ID, row.vehicleId)
            put(COL_OWNER_ID, row.ownerId)
            put(COL_PLATE, row.plate)
            put(COL_STATUS, row.status)
            put(COL_PAYLOAD_JSON, row.payloadJson)
            put(COL_UPDATED_AT, row.updatedAt)
            put(COL_DELETED, if (row.deleted) 1 else 0)
            put(COL_SYNC_STATE, row.syncState)
        }
        writableDatabase.insertWithOnConflict(
            TABLE_VEHICLES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteVehiclePermanently(vehicleId: String) {
        writableDatabase.delete(
            TABLE_VEHICLES,
            "$COL_VEHICLE_ID = ?",
            arrayOf(vehicleId)
        )
    }

    fun markVehiclePending(vehicleId: String) {
        writableDatabase.update(
            TABLE_VEHICLES,
            ContentValues().apply { put(COL_SYNC_STATE, SYNC_STATE_PENDING) },
            "$COL_VEHICLE_ID = ?",
            arrayOf(vehicleId)
        )
    }

    fun markVehicleSynced(vehicleId: String) {
        writableDatabase.update(
            TABLE_VEHICLES,
            ContentValues().apply { put(COL_SYNC_STATE, SYNC_STATE_SYNCED) },
            "$COL_VEHICLE_ID = ?",
            arrayOf(vehicleId)
        )
    }

    fun markVehicleDeleted(vehicleId: String, updatedAt: Long) {
        writableDatabase.update(
            TABLE_VEHICLES,
            ContentValues().apply {
                put(COL_DELETED, 1)
                put(COL_UPDATED_AT, updatedAt)
                put(COL_SYNC_STATE, SYNC_STATE_PENDING)
            },
            "$COL_VEHICLE_ID = ?",
            arrayOf(vehicleId)
        )
    }

    fun getVehicle(vehicleId: String): VehicleRow? {
        readableDatabase.query(
            TABLE_VEHICLES,
            null,
            "$COL_VEHICLE_ID = ?",
            arrayOf(vehicleId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toVehicleRow() else null
        }
    }

    fun getPendingVehicles(ownerId: String): List<VehicleRow> {
        val result = mutableListOf<VehicleRow>()
        readableDatabase.query(
            TABLE_VEHICLES,
            null,
            "$COL_OWNER_ID = ? AND $COL_SYNC_STATE = ?",
            arrayOf(ownerId, SYNC_STATE_PENDING),
            null,
            null,
            "$COL_UPDATED_AT ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.toVehicleRow()
            }
        }
        return result
    }

    fun getVehicles(ownerId: String): List<VehicleRow> {
        val result = mutableListOf<VehicleRow>()
        readableDatabase.query(
            TABLE_VEHICLES,
            null,
            "$COL_OWNER_ID = ? AND $COL_DELETED = 0",
            arrayOf(ownerId),
            null,
            null,
            "$COL_UPDATED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.toVehicleRow()
            }
        }
        return result
    }

    private fun Cursor.toVehicleRow(): VehicleRow {
        return VehicleRow(
            vehicleId = getString(getColumnIndexOrThrow(COL_VEHICLE_ID)),
            ownerId = getString(getColumnIndexOrThrow(COL_OWNER_ID)),
            plate = getString(getColumnIndexOrThrow(COL_PLATE)),
            status = getString(getColumnIndexOrThrow(COL_STATUS)),
            payloadJson = getString(getColumnIndexOrThrow(COL_PAYLOAD_JSON)),
            updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
            deleted = getInt(getColumnIndexOrThrow(COL_DELETED)) == 1,
            syncState = getString(getColumnIndexOrThrow(COL_SYNC_STATE))
        )
    }
}
