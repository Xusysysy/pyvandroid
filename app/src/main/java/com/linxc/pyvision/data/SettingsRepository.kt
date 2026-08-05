package com.linxc.pyvision.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "pyvision_settings")

/** 设置持久化（对应 pyvision 桌面版 settings.json） */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val CAMERA_ID = stringPreferencesKey("camera_id")
        val RESOLUTION = stringPreferencesKey("resolution")
        val PROCESSOR = stringPreferencesKey("processor")
        val MODEL_PATH = stringPreferencesKey("model_path")
        val SHOW_FPS = booleanPreferencesKey("show_fps")
        val SHOW_CROSSHAIR = booleanPreferencesKey("show_crosshair")
        val MIRROR = booleanPreferencesKey("mirror")
        val OFFSET_X = intPreferencesKey("offset_x")
        val OFFSET_Y = intPreferencesKey("offset_y")
        val PHOTO_PREFIX = stringPreferencesKey("photo_prefix")
        val OUTPUT_DIR = stringPreferencesKey("output_dir")
        val SNAPSHOT_COUNT = intPreferencesKey("snapshot_count")
        val VAL_RATIO = floatPreferencesKey("val_ratio")
        val EPOCHS = intPreferencesKey("epochs")
        val IMGSZ = intPreferencesKey("imgsz")
        val BATCH = intPreferencesKey("batch")
    }

    data class Settings(
        val cameraId: String = "0",
        val resolution: String = "1920x1080",
        val processor: String = "直通 (原始)",
        val modelPath: String = "",
        val showFps: Boolean = true,
        val showCrosshair: Boolean = false,
        val mirror: Boolean = false,
        val offsetX: Int = 0,
        val offsetY: Int = 0,
        val photoPrefix: String = "snap",
        val outputDir: String = "",
        val snapshotCount: Int = 0,
        val valRatio: Float = 0.2f,
        val epochs: Int = 30,
        val imgsz: Int = 28,
        val batch: Int = 16,
    )

    val settings: Flow<Settings> = context.settingsStore.data.map { prefs ->
        Settings(
            cameraId = prefs[Keys.CAMERA_ID] ?: "0",
            resolution = prefs[Keys.RESOLUTION] ?: "1920x1080",
            processor = prefs[Keys.PROCESSOR] ?: "直通 (原始)",
            modelPath = prefs[Keys.MODEL_PATH] ?: "",
            showFps = prefs[Keys.SHOW_FPS] ?: true,
            showCrosshair = prefs[Keys.SHOW_CROSSHAIR] ?: false,
            mirror = prefs[Keys.MIRROR] ?: false,
            offsetX = prefs[Keys.OFFSET_X] ?: 0,
            offsetY = prefs[Keys.OFFSET_Y] ?: 0,
            photoPrefix = prefs[Keys.PHOTO_PREFIX] ?: "snap",
            outputDir = prefs[Keys.OUTPUT_DIR] ?: "",
            snapshotCount = prefs[Keys.SNAPSHOT_COUNT] ?: 0,
            valRatio = prefs[Keys.VAL_RATIO] ?: 0.2f,
            epochs = prefs[Keys.EPOCHS] ?: 30,
            imgsz = prefs[Keys.IMGSZ] ?: 28,
            batch = prefs[Keys.BATCH] ?: 16,
        )
    }

    suspend fun update(
        cameraId: String? = null,
        resolution: String? = null,
        processor: String? = null,
        modelPath: String? = null,
        showFps: Boolean? = null,
        showCrosshair: Boolean? = null,
        mirror: Boolean? = null,
        offsetX: Int? = null,
        offsetY: Int? = null,
        photoPrefix: String? = null,
        outputDir: String? = null,
        snapshotCount: Int? = null,
        valRatio: Float? = null,
        epochs: Int? = null,
        imgsz: Int? = null,
        batch: Int? = null,
    ) {
        context.settingsStore.edit { prefs ->
            cameraId?.let { prefs[Keys.CAMERA_ID] = it }
            resolution?.let { prefs[Keys.RESOLUTION] = it }
            processor?.let { prefs[Keys.PROCESSOR] = it }
            modelPath?.let { prefs[Keys.MODEL_PATH] = it }
            showFps?.let { prefs[Keys.SHOW_FPS] = it }
            showCrosshair?.let { prefs[Keys.SHOW_CROSSHAIR] = it }
            mirror?.let { prefs[Keys.MIRROR] = it }
            offsetX?.let { prefs[Keys.OFFSET_X] = it }
            offsetY?.let { prefs[Keys.OFFSET_Y] = it }
            photoPrefix?.let { prefs[Keys.PHOTO_PREFIX] = it }
            outputDir?.let { prefs[Keys.OUTPUT_DIR] = it }
            snapshotCount?.let { prefs[Keys.SNAPSHOT_COUNT] = it }
            valRatio?.let { prefs[Keys.VAL_RATIO] = it }
            epochs?.let { prefs[Keys.EPOCHS] = it }
            imgsz?.let { prefs[Keys.IMGSZ] = it }
            batch?.let { prefs[Keys.BATCH] = it }
        }
    }
}
