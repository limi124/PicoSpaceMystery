package com.example.picospacemystery.model

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

data class Vector3Data(val x: Float, val y: Float, val z: Float)

data class Extent2D(val width: Float, val height: Float)

data class SurfaceData(
    val id: String,
    val type: String,
    val center: Vector3Data,
    val extent: Extent2D
)

data class RoomLayoutData(
    val surfaces: List<SurfaceData>
)

data class ClueItem(
    val clueId: String,
    val title: String,
    val surfaceId: String,
    val offsetFromCenter: Vector3Data,
    val visualType: String, // "footprint" | "rune" | "note" | "box"
    val shortHint: String,  // 直接显示在 3D 房间悬浮牌上的简短可视化线索
    val content: String,
    val isLocked: Boolean = false,
    val unlockCode: String? = null,
    val isTool: Boolean = false,
    val requiredToolId: String? = null,
    val encryptedHint: String = "❓ 痕迹模糊（需使用本房勘查工具破译）",
    val encryptedContent: String = "表面覆盖着特殊涂层或加密干扰，必须先在房间内找到勘查工具并激活，才能显影出隐藏的线索信息。",
    val imagePrompt: String = ""
)

data class SuspectItem(
    val suspectId: String,
    val name: String,
    val surfaceId: String,
    val personaPrompt: String,
    val initialGreeting: String = "你有什么事？我可什么都没看见。",
    val quickQuestions: List<Pair<String, String>> = emptyList(),
    val keyEvidenceClueId: String = "",
    val evidenceConfrontQuestion: String = "📢 出示关键证物拆穿谎言",
    val breakthroughReply: String = "",
    val imagePrompt: String = ""
)

data class SpatialAudioEvent(
    val triggerClueId: String,
    val soundType: String,
    val relativeDirection: String
)

/**
 * 单个密室房间的数据（一套剧本包含 3 个递进房间）
 */
data class EscapeRoomLevel(
    val roomIndex: Int,
    val roomName: String,
    val roomAmbience: String,
    val doorUnlockCode: String,
    val doorPrompt: String,
    val clues: List<ClueItem>,
    val suspects: List<SuspectItem>,
    val wallImagePrompt: String = ""
)

data class CaseScript(
    val worldview: String,
    val caseTitle: String,
    val openingNarration: String,
    val rooms: List<EscapeRoomLevel>,
    val spatialAudioEvents: List<SpatialAudioEvent>,
    val truthSummary: String,
    val culpritQuestion: String = "综合三间密室的物证，谁才是幕后操纵者及其真实目的？",
    val culpritOptions: List<String> = emptyList(),
    val correctCulpritIndex: Int = 0,
    val isLiveAiGenerated: Boolean = false
) {
    // 兼容快捷属性
    val clues: List<ClueItem> get() = rooms.flatMap { it.clues }
    val suspects: List<SuspectItem> get() = rooms.flatMap { it.suspects }
}

/**
 * 3D 密室瞬移观察机位（支持全景、书桌特写、左右墙壁近距离搜证）
 */
data class SpatialViewpoint(
    val index: Int,
    val label: String,
    val offsetX: Float,
    val offsetY: Float,
    val offsetZ: Float
) {
    companion object {
        val PRESETS: List<SpatialViewpoint> = listOf(
            SpatialViewpoint(0, "📍 全景中央", 0f, 0f, 0f),
            SpatialViewpoint(1, "🔍 凑近书桌", 0f, -0.05f, 0.48f),
            SpatialViewpoint(2, "🚪 左墙与大门", 0.65f, 0f, 0.55f),
            SpatialViewpoint(3, "🗣️ 右墙与后方", -0.65f, 0f, -0.35f)
        )

        fun byIndex(index: Int): SpatialViewpoint =
            PRESETS.firstOrNull { it.index == index } ?: PRESETS.first()
    }
}

enum class AgentRole(val displayName: String, val badge: String) {
    DIRECTOR("剧情总控 Agent", "🎬"),
    ART_TEXTURE("美术贴图 Sub-Agent", "🎨"),
    SPATIAL_LAYOUT("空间布局 Sub-Agent", "📐"),
    SUSPECT_ROLEPLAY("角色演绎 Sub-Agent", "🗣️")
}

enum class AgentRunState { IDLE, RUNNING, DONE }

data class AgentStepLog(
    val role: AgentRole,
    val state: AgentRunState,
    val message: String
)

data class ThemePalette(
    val primaryHex: Long,
    val accentHex: Long,
    val surfaceTintHex: Long,
    val styleLabel: String
) {
    val primaryColor: Color get() = Color(primaryHex)
    val accentColor: Color get() = Color(accentHex)
    val surfaceTint: Color get() = Color(surfaceTintHex)
}

data class RoomVisualBundle(
    val wallBitmap: Bitmap,
    val floorBitmap: Bitmap,
    val tableBitmap: Bitmap,
    val doorLockedBitmap: Bitmap,
    val doorUnlockedBitmap: Bitmap
)

data class GeneratedVisualAssets(
    val palette: ThemePalette,
    val clueTextures: Map<String, ImageBitmap>,
    val suspectPortraits: Map<String, ImageBitmap>,
    val clueBitmaps: Map<String, Bitmap>,
    val clueSignBitmaps: Map<String, Bitmap>,
    val suspectBitmaps: Map<String, Bitmap>,
    val roomBundles: List<RoomVisualBundle>,
    val textureSourceNote: String
)

data class PlacedEntityAnchor(
    val id: String,
    val worldPos: Vector3Data,
    val surfaceId: String
)

data class LlmConfig(
    val baseUrl: String = "https://chat.ecnu.edu.cn/open/api/v1",
    val apiKey: String = com.example.picospacemystery.BuildConfig.DEFAULT_ECNU_API_KEY,
    val chatModel: String = "ecnu-plus",
    val imageBaseUrl: String = "https://api.siliconflow.cn/v1",
    val imageApiKey: String = com.example.picospacemystery.BuildConfig.DEFAULT_IMAGE_API_KEY,
    val imageModel: String = "Kwai-Kolors/Kolors"
)

data class CustomGameConfig(
    val worldview: String = "民国雨夜古宅",
    val plotStyle: String = "悬疑烧脑",
    val customRoles: String = ""
)
