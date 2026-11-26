package com.example.picospacemystery.agents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.picospacemystery.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.sin

/**
 * 美术贴图 Sub-Agent（烘焙光影 + 丰富细节 + 防剧透全息立牌版）：
 * 1) 为 3 个递进密室房间分别生成具有烘焙聚光灯晕、砖缝/铆钉高光、暗角（Vignette）、战术法阵网格与双金线桌面的高精度表面贴图；
 * 2) 为 4 类 3D 线索（地面暗格/足迹、墙面铭文壁画、桌面机密手札、机关宝箱）生成高立体感防剧透贴图；
 * 3) 生成玩家搜获线索后才在 3D 道具上方点亮的全息悬浮立牌贴图，以及高辨识度的 AI 驻场嫌疑人立牌。
 */
class ArtTextureAgent {

    fun resolvePalette(worldview: String): ThemePalette {
        val w = worldview.lowercase()
        return when {
            w.contains("赛博") || w.contains("cyber") || w.contains("霓虹") || w.contains("未来") ->
                ThemePalette(0xFF00F0FF, 0xFFFF2A85, 0xFF141E38, "赛博霓虹装甲")
            w.contains("深海") || w.contains("克苏鲁") || w.contains("潜艇") ->
                ThemePalette(0xFF38BDF8, 0xFF10B981, 0xFF0E2938, "深海古神遗迹")
            w.contains("太空") || w.contains("星际") || w.contains("科幻") ->
                ThemePalette(0xFF818CF8, 0xFFF59E0B, 0xFF1E293B, "星际空间舱壁")
            w.contains("魔法") || w.contains("地窖") || w.contains("学院") ->
                ThemePalette(0xFFF59E0B, 0xFFA855F7, 0xFF261C14, "魔法学院石砌地窖")
            w.contains("蒸汽") || w.contains("列车") ->
                ThemePalette(0xFFD97706, 0xFF38BDF8, 0xFF291D12, "蒸汽黄铜齿轮")
            else ->
                ThemePalette(0xFFF59E0B, 0xFFEF4444, 0xFF2B1D16, "民国复古护墙板")
        }
    }

    fun paletteForRoom(base: ThemePalette, roomIndex: Int): ThemePalette {
        return when (roomIndex) {
            0 -> base
            1 -> ThemePalette(
                primaryHex = base.accentHex,
                accentHex = 0xFF38BDF8,
                surfaceTintHex = 0xFF161F33,
                styleLabel = "${base.styleLabel} · 二层暗室"
            )
            else -> ThemePalette(
                primaryHex = 0xFFFBBF24,
                accentHex = 0xFF34D399,
                surfaceTintHex = 0xFF26152B,
                styleLabel = "${base.styleLabel} · 终极核心室"
            )
        }
    }

    suspend fun generateAssets(
        script: CaseScript,
        config: LlmConfig,
        useRemoteImageApi: Boolean
    ): GeneratedVisualAssets = withContext(Dispatchers.Default) {
        val basePalette = resolvePalette(script.worldview)
        val clueTextures = mutableMapOf<String, ImageBitmap>()
        val suspectPortraits = mutableMapOf<String, ImageBitmap>()
        val clueBitmaps = mutableMapOf<String, Bitmap>()
        val clueSignBitmaps = mutableMapOf<String, Bitmap>()
        val suspectBitmaps = mutableMapOf<String, Bitmap>()
        var remoteCount = 0
        val remoteWallBitmaps = mutableMapOf<Int, Bitmap>()
        val remoteDoorBitmaps = mutableMapOf<Int, Bitmap>()

        if (useRemoteImageApi && config.imageApiKey.isNotBlank()) {
            coroutineScope {
                // 1) 并行生成 3 间密室的 AI 环境墙面贴图 + 3 扇密室大门 AI 贴图 + 3 名驻场嫌疑人 AI 肖像立绘 + 全部 12 件道具（工具/壁画/证物/机关宝箱）AI 贴图
                val roomWallJobs = script.rooms.map { room ->
                    async {
                        val prompt = room.wallImagePrompt.ifBlank {
                            "Atmospheric dark mystery room wall environment texture, ${script.worldview}, ${room.roomName}, game concept art, rich lighting, no text"
                        }
                        val bmp = tryGenerateRemoteTexture(prompt, config)
                        if (bmp != null) {
                            synchronized(remoteWallBitmaps) {
                                remoteWallBitmaps[room.roomIndex] = bmp
                            }
                        }
                    }
                }
                val roomDoorJobs = script.rooms.map { room ->
                    async {
                        val prompt = "Heavy ornate mystery escape room door texture, ${script.worldview}, ${room.roomName}, mechanical gears and glowing runes, front view, game asset illustration, dramatic lighting, no text"
                        val bmp = tryGenerateRemoteTexture(prompt, config)
                        if (bmp != null) {
                            synchronized(remoteDoorBitmaps) {
                                remoteDoorBitmaps[room.roomIndex] = bmp
                            }
                        }
                    }
                }
                val suspectJobs = script.rooms.flatMap { room ->
                    val rPalette = paletteForRoom(basePalette, room.roomIndex)
                    room.suspects.map { suspect ->
                        async {
                            val prompt = suspect.imagePrompt.ifBlank {
                                "Mystery detective game character portrait of ${suspect.name} in ${script.worldview}, dramatic rim lighting, digital painting, half body"
                            }
                            val rawBmp = tryGenerateRemoteTexture(prompt, config)
                            if (rawBmp != null) {
                                val framed = frameRemoteSuspectBitmap(rawBmp, suspect.name, room.roomName.substringBefore("："), rPalette)
                                synchronized(suspectBitmaps) {
                                    suspectBitmaps[suspect.suspectId] = framed
                                    suspectPortraits[suspect.suspectId] = framed.asImageBitmap()
                                }
                            }
                        }
                    }
                }
                val allClueJobs = script.rooms.flatMap { room ->
                    val rPalette = paletteForRoom(basePalette, room.roomIndex)
                    room.clues.map { clue ->
                        async {
                            val defaultPrompt = when {
                                clue.isLocked || clue.visualType == "box" ->
                                    "Ornate mechanical puzzle treasure chest lockbox in ${script.worldview}, ${clue.title}, glowing combination dials, game prop close-up, rich lighting"
                                clue.isTool ->
                                    "Detective inspection tool prop ${clue.title} in ${script.worldview}, high detail game icon illustration, dramatic rim light"
                                clue.visualType == "rune" ->
                                    "Mysterious glowing cipher mural or inscription ${clue.title} on wall in ${script.worldview}, game texture art"
                                else ->
                                    "Secret evidence dossier prop ${clue.title} in ${script.worldview}, tabletop mystery game item, dramatic lighting"
                            }
                            val prompt = clue.imagePrompt.ifBlank { defaultPrompt }
                            val rawBmp = tryGenerateRemoteTexture(prompt, config)
                            if (rawBmp != null) {
                                val framed = frameRemoteClueBitmap(rawBmp, clue.title, clue.isLocked, rPalette)
                                synchronized(clueBitmaps) {
                                    clueBitmaps[clue.clueId] = framed
                                    clueTextures[clue.clueId] = framed.asImageBitmap()
                                }
                            }
                        }
                    }
                }
                (roomWallJobs + roomDoorJobs + suspectJobs + allClueJobs).awaitAll()
                remoteCount = remoteWallBitmaps.size + remoteDoorBitmaps.size + suspectBitmaps.size + clueBitmaps.size
            }
        }

        script.rooms.forEach { room ->
            val rPalette = paletteForRoom(basePalette, room.roomIndex)
            room.clues.forEach { clue ->
                if (!clueTextures.containsKey(clue.clueId)) {
                    val bmp = synthesizeClueArtwork(
                        visualType = clue.visualType,
                        title = clue.title,
                        shortHint = clue.shortHint,
                        palette = rPalette
                    )
                    clueBitmaps[clue.clueId] = bmp
                    clueTextures[clue.clueId] = bmp.asImageBitmap()
                }
                clueSignBitmaps[clue.clueId] = synthesizeClueSignboardBitmap(
                    title = clue.title,
                    shortHint = clue.shortHint,
                    isLocked = clue.isLocked,
                    palette = rPalette
                )
            }

            room.suspects.forEachIndexed { idx, suspect ->
                if (!suspectBitmaps.containsKey(suspect.suspectId)) {
                    val bmp = synthesizeSuspectCardBitmap(
                        name = suspect.name,
                        roomName = room.roomName.substringBefore("："),
                        roleIndex = room.roomIndex + idx,
                        palette = rPalette
                    )
                    suspectBitmaps[suspect.suspectId] = bmp
                    suspectPortraits[suspect.suspectId] = bmp.asImageBitmap()
                }
            }
        }

        val roomBundles = script.rooms.map { room ->
            val rPalette = paletteForRoom(basePalette, room.roomIndex)
            val nextRoomTitle = script.rooms.getOrNull(room.roomIndex + 1)?.roomName ?: "通关真相出口"
            val remoteDoor = remoteDoorBitmaps[room.roomIndex]
            RoomVisualBundle(
                wallBitmap = remoteWallBitmaps[room.roomIndex] ?: synthesizeRoomSurfaceBitmap("wall", room.roomName, rPalette),
                floorBitmap = synthesizeRoomSurfaceBitmap("floor", room.roomName, rPalette),
                tableBitmap = synthesizeRoomSurfaceBitmap("table", room.roomName, rPalette),
                doorLockedBitmap = remoteDoor?.let { frameRemoteDoorBitmap(it, nextRoomTitle, false, rPalette) }
                    ?: synthesizeDoorBitmap(room.roomName, nextRoomTitle, room.doorUnlockCode, false, rPalette),
                doorUnlockedBitmap = remoteDoor?.let { frameRemoteDoorBitmap(it, nextRoomTitle, true, rPalette) }
                    ?: synthesizeDoorBitmap(room.roomName, nextRoomTitle, room.doorUnlockCode, true, rPalette)
            )
        }

        val note = if (remoteCount > 0) {
            "✨ 硅基流动 Kolors 已实时生成 $remoteCount 张 AI 贴图与角色立绘 (${basePalette.styleLabel})"
        } else {
            "已合成 3 间递进密室烘焙光影材质、机关大门与全息线索浮标 (${basePalette.styleLabel})"
        }
        GeneratedVisualAssets(
            palette = basePalette,
            clueTextures = clueTextures,
            suspectPortraits = suspectPortraits,
            clueBitmaps = clueBitmaps,
            clueSignBitmaps = clueSignBitmaps,
            suspectBitmaps = suspectBitmaps,
            roomBundles = roomBundles,
            textureSourceNote = note
        )
    }

    private suspend fun tryGenerateRemoteTexture(prompt: String, config: LlmConfig): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(config.imageBaseUrl.trimEnd('/') + "/images/generations")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 22000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${config.imageApiKey}")
                }
                val body = JSONObject().apply {
                    put("model", config.imageModel)
                    put("prompt", prompt)
                    put("image_size", "1024x1024")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                if (conn.responseCode !in 200..299) return@runCatching null
                val resp = conn.inputStream.bufferedReader().readText()
                val root = JSONObject(resp)
                val itemObj = root.optJSONArray("images")?.optJSONObject(0)
                    ?: root.optJSONArray("data")?.optJSONObject(0)
                    ?: return@runCatching null

                val imgUrl = itemObj.optString("url")
                val b64 = itemObj.optString("b64_json")
                val decoded = when {
                    imgUrl.isNotBlank() -> {
                        val dlConn = (URL(imgUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 15000
                        }
                        dlConn.inputStream.use { BitmapFactory.decodeStream(it) }
                    }
                    b64.isNotBlank() -> {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    else -> null
                }
                decoded?.let { Bitmap.createScaledBitmap(it, 512, 512, true) }
            }.getOrNull()
        }

    private fun frameRemoteClueBitmap(raw: Bitmap, title: String, isLocked: Boolean, palette: ThemePalette): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawBitmap(raw, 0f, 0f, null)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primaryHex.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRoundRect(RectF(12f, 12f, size - 12f, size - 12f), 20f, 20f, borderPaint)

        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD9070B14.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(36f, size - 110f, size - 36f, size - 24f), 16f, 16f, bannerPaint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 30f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title.take(11), size / 2f, size - 68f, titlePaint)
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primaryHex.toInt()
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(if (isLocked) "🔒 机关锁匣" else "✨ 点击搜证", size / 2f, size - 36f, subPaint)
        return bmp
    }

    private fun frameRemoteSuspectBitmap(raw: Bitmap, name: String, roomTag: String, palette: ThemePalette): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawBitmap(raw, 0f, 0f, null)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentHex.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRoundRect(RectF(14f, 14f, size - 14f, size - 14f), 24f, 24f, borderPaint)

        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE60B1120.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(28f, size - 120f, size - 28f, size - 24f), 16f, 16f, bannerPaint)
        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentHex.toInt()
            textSize = 22f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🗣️ $roomTag 驻场嫌疑人", size / 2f, size - 84f, tagPaint)
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 34f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(name.take(10), size / 2f, size - 40f, namePaint)
        return bmp
    }

    /**
     * 将 AI 生成的大门原图封装为带锁定/开启发光机械转盘与通往提示的 3D 门扇贴图
     */
    private fun frameRemoteDoorBitmap(
        raw: Bitmap,
        nextRoomName: String,
        unlocked: Boolean,
        palette: ThemePalette
    ): Bitmap {
        val w = 512
        val h = 768
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
        canvas.drawBitmap(scaled, 0f, 0f, null)

        val frameColor = if (unlocked) 0xFF34D399.toInt() else palette.primaryHex.toInt()
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameColor
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        canvas.drawRoundRect(RectF(16f, 16f, w - 16f, h - 16f), 24f, 24f, framePaint)

        val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6060B16.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(62f, h * 0.36f, w - 62f, h * 0.49f), 22f, 22f, badgeBg)
        canvas.drawRoundRect(RectF(62f, h * 0.36f, w - 62f, h * 0.49f), 22f, 22f, framePaint)

        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (unlocked) 0xFF6EE7B7.toInt() else 0xFFFBBF24.toInt()
            textSize = 36f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(if (unlocked) "🔓 大门已开启" else "🔒 密室大门锁定", w / 2f, h * 0.44f, statusPaint)

        canvas.drawRoundRect(RectF(44f, h - 186f, w - 44f, h - 60f), 18f, 18f, badgeBg)
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 28f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("通往：${nextRoomName.take(11)}", w / 2f, h - 132f, subPaint)

        val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (unlocked) 0xFF34D399.toInt() else 0xFFCBD5E1.toInt()
            textSize = 23f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            if (unlocked) "👉 点击此大门推门进入下一房间" else "集齐本房 3 处线索解开锁匣",
            w / 2f,
            h - 86f,
            actionPaint
        )
        return bmp
    }

    /**
     * 3D 空间嫌疑人头顶实时对话气泡贴图（支持自动折行、当前驻点标签与点击直接对话提示）
     */
    fun synthesizeSuspectSpeechBubbleBitmap(
        suspectName: String,
        spotLabel: String,
        dialogue: String,
        palette: ThemePalette
    ): Bitmap {
        val w = 512
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                0xFA0C152B.toInt(),
                0xF2060A14.toInt(),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentHex.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val rect = RectF(10f, 10f, w - 10f, h - 10f)
        canvas.drawRoundRect(rect, 28f, 28f, bg)
        canvas.drawRoundRect(rect, 28f, 28f, border)

        val headerBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x3838BDF8
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(28f, 24f, w - 28f, 70f), 14f, 14f, headerBg)

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF7DD3FC.toInt()
            textSize = 24f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🗣️ ${suspectName.take(8)} · [$spotLabel]", w / 2f, 56f, headerPaint)

        val clean = dialogue
            .replace("\n", " ")
            .replace("（", "(")
            .replace("）", ")")
            .trim()
        val line1 = clean.take(15)
        val line2 = clean.drop(15).take(15) + if (clean.length > 30) "…" else ""

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 27f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("“$line1", w / 2f, 118f, textPaint)
        if (line2.isNotBlank()) {
            canvas.drawText("$line2”", w / 2f, 158f, textPaint)
        } else {
            canvas.drawText("”", w / 2f, 158f, textPaint)
        }

        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFDE68A.toInt()
            textSize = 21f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("👉 点击人物或气泡 · 现场盘问 / 换位", w / 2f, 218f, hintPaint)
        return bmp
    }

    /**
     * 当玩家在 3D 房间搜获/破译线索后，悬浮在 3D 道具上方的全息立牌贴图：
     * 清晰展示「✅ 已搜获：$title」与「$shortHint」
     */
    fun synthesizeClueSignboardBitmap(
        title: String,
        shortHint: String,
        isLocked: Boolean,
        palette: ThemePalette
    ): Bitmap {
        val w = 512
        val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            0xFA091224.toInt(),
            0xF5050914.toInt(),
            Shader.TileMode.CLAMP
        )
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = bgShader
            style = Paint.Style.FILL
        }
        val borderColor = if (isLocked) 0xFFF59E0B.toInt() else 0xFF34D399.toInt()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            style = Paint.Style.STROKE
            strokeWidth = 9f
        }
        val outerRect = RectF(10f, 10f, w - 10f, h - 10f)
        canvas.drawRoundRect(outerRect, 26f, 26f, bg)
        canvas.drawRoundRect(outerRect, 26f, 26f, border)

        // 全息扫描横纹与内框装饰角
        val innerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primaryHex.toInt()
            alpha = 90
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRoundRect(RectF(22f, 22f, w - 22f, h - 22f), 18f, 18f, innerBorder)

        val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLocked) 0x38F59E0B else 0x3834D399
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(36f, 28f, w - 36f, 78f), 12f, 12f, tagBg)

        val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLocked) 0xFFFDE68A.toInt() else 0xFF6EE7B7.toInt()
            textSize = 28f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val headerText = if (isLocked) "🔒 机关锁匣：${title.take(10)}" else "✅ 已搜获：${title.take(10)}"
        canvas.drawText(headerText, w / 2f, 63f, tagPaint)

        // 中部主标题
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 36f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title.take(12), w / 2f, 126f, titlePaint)

        // 底部高亮破译密码/线索胶条
        val hintRect = RectF(34f, 150f, w - 34f, 226f)
        val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLocked) 0x4DF59E0B else 0x4D10B981
            style = Paint.Style.FILL
        }
        val hintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = borderColor
            alpha = 180
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(hintRect, 16f, 16f, hintBg)
        canvas.drawRoundRect(hintRect, 16f, 16f, hintStroke)

        val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isLocked) 0xFFFDE68A.toInt() else 0xFFA7F3D0.toInt()
            textSize = 36f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(shortHint.take(15), w / 2f, 201f, hintPaint)
        return bmp
    }

    /**
     * 3D 密室过道大门贴图（带有立体门框、机械能量锁盘、铆钉与状态指示灯）
     */
    fun synthesizeDoorBitmap(
        currentRoomName: String,
        nextRoomName: String,
        doorCode: String,
        unlocked: Boolean,
        palette: ThemePalette
    ): Bitmap {
        val w = 512
        val h = 768
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                if (unlocked) 0xFF064E3B.toInt() else 0xFF1E1B2E.toInt(),
                0xFF070A12.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // 顶部烘焙聚光灯效
        val spotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h * 0.25f, w * 0.65f,
                intArrayOf(
                    if (unlocked) 0x5534D399 else blendColor(0x00000000, palette.primaryHex.toInt(), 0.32f),
                    0x00000000
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), spotPaint)

        val frameColor = if (unlocked) 0xFF34D399.toInt() else palette.primaryHex.toInt()
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameColor
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        canvas.drawRoundRect(RectF(18f, 18f, w - 18f, h - 18f), 24f, 24f, framePaint)

        val innerFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameColor
            alpha = 180
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawRoundRect(RectF(48f, 48f, w - 48f, h - 48f), 16f, 16f, innerFramePaint)

        // 装甲门板骨骼线与铆钉
        val rivetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = frameColor
            alpha = 160
            style = Paint.Style.FILL
        }
        for (ry in listOf(76f, 220f, 460f, h - 76f)) {
            canvas.drawCircle(72f, ry, 6f, rivetPaint)
            canvas.drawCircle(w - 72f, ry, 6f, rivetPaint)
        }

        // 双开门中缝与中央机械转轮锁
        canvas.drawLine(w / 2f, 48f, w / 2f, h - 48f, framePaint)
        val coreFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF0080E1C.toInt()
            style = Paint.Style.FILL
        }
        val wheelCenterY = h * 0.42f
        canvas.drawCircle(w / 2f, wheelCenterY, 118f, coreFill)
        canvas.drawCircle(w / 2f, wheelCenterY, 118f, framePaint)
        canvas.drawCircle(w / 2f, wheelCenterY, 92f, innerFramePaint)

        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (unlocked) 0xFF6EE7B7.toInt() else 0xFFFBBF24.toInt()
            textSize = 38f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(if (unlocked) "🔓 大门已开启" else "🔒 密室大门锁定", w / 2f, wheelCenterY + 14f, statusPaint)

        val subBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC060A14.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(56f, h - 195f, w - 56f, h - 72f), 16f, 16f, subBg)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 30f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("通往：${nextRoomName.take(11)}", w / 2f, h - 142f, subPaint)

        val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (unlocked) 0xFF34D399.toInt() else 0xFFCBD5E1.toInt()
            textSize = 25f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            if (unlocked) "👉 点击此大门推门进入下一房间" else "需集齐本房间 3 处线索解开锁匣",
            w / 2f,
            h - 96f,
            actionPaint
        )
        return bmp
    }

    /**
     * 3D 线索道具表面贴图：
     * 绘制高立体感插画，底部铭牌仅显示道具标题（title.take(10)）和状态（🔒 机关锁匣 或 ✨ 点击搜证），
     * 绝不在未搜证前剧透密码数字！
     */
    private fun synthesizeClueArtwork(
        visualType: String,
        title: String,
        shortHint: String,
        palette: ThemePalette
    ): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        // 1. 烘焙聚光灯 + 暗角底板
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size / 2f, size * 0.42f, size * 0.68f,
                intArrayOf(
                    blendColor(palette.surfaceTintHex.toInt(), palette.primaryHex.toInt(), 0.24f),
                    palette.surfaceTintHex.toInt(),
                    0xFF060913.toInt()
                ),
                floatArrayOf(0.05f, 0.58f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val primaryFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primaryHex.toInt()
            style = Paint.Style.FILL
            alpha = 220
        }
        val primaryStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primaryHex.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val accentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentHex.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val glowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.accentHex.toInt()
            style = Paint.Style.FILL
            alpha = 70
        }

        // 双层金属外框与四角装饰螺钉
        canvas.drawRoundRect(RectF(16f, 16f, size - 16f, size - 16f), 26f, 26f, primaryStroke)
        val thinBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.primaryHex.toInt()
            alpha = 110
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(RectF(30f, 30f, size - 30f, size - 30f), 18f, 18f, thinBorder)

        when (visualType) {
            "footprint" -> {
                // 地面暗格/荧光足迹与扫描网格
                val hatchRect = RectF(78f, 58f, size - 78f, 356f)
                val hatchBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xAA0A101D.toInt()
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(hatchRect, 20f, 20f, hatchBg)
                canvas.drawRoundRect(hatchRect, 20f, 20f, accentStroke)

                // 左右双靴底印与脚跟
                canvas.drawOval(RectF(140f, 92f, 224f, 232f), glowFill)
                canvas.drawOval(RectF(146f, 98f, 218f, 226f), primaryFill)
                canvas.drawOval(RectF(156f, 248f, 214f, 314f), primaryFill)

                canvas.drawOval(RectF(274f, 70f, 358f, 210f), glowFill)
                canvas.drawOval(RectF(280f, 76f, 352f, 204f), primaryFill)
                canvas.drawOval(RectF(290f, 226f, 348f, 292f), primaryFill)

                // 放大镜瞄准准星
                canvas.drawCircle(size / 2f, 200f, 118f, thinBorder)
            }
            "rune" -> {
                // 墙面古老壁画/发光星象法阵铭文
                val cx = size / 2f
                val cy = size * 0.39f
                canvas.drawCircle(cx, cy, 132f, glowFill)
                canvas.drawCircle(cx, cy, 126f, primaryStroke)
                canvas.drawCircle(cx, cy, 94f, accentStroke)
                canvas.drawCircle(cx, cy, 52f, thinBorder)

                // 正反交叠六芒星几何法阵
                val tri1 = Path().apply {
                    moveTo(cx, cy - 112f)
                    lineTo(cx + 98f, cy + 58f)
                    lineTo(cx - 98f, cy + 58f)
                    close()
                }
                val tri2 = Path().apply {
                    moveTo(cx, cy + 112f)
                    lineTo(cx + 98f, cy - 58f)
                    lineTo(cx - 98f, cy - 58f)
                    close()
                }
                canvas.drawPath(tri1, primaryStroke)
                canvas.drawPath(tri2, accentStroke)
                canvas.drawCircle(cx, cy, 18f, primaryFill)
            }
            "box" -> {
                // 立体装甲机关宝箱：箱盖、箱体、包角铜条与核心锁芯
                val chestRect = RectF(88f, 90f, size - 88f, 344f)
                val chestFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 90f, 0f, 344f,
                        0xFF2B1F16.toInt(),
                        0xFF120D0A.toInt(),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(chestRect, 22f, 22f, chestFill)
                canvas.drawRoundRect(chestRect, 22f, 22f, primaryStroke)

                // 金属加固竖条
                canvas.drawRect(RectF(132f, 92f, 156f, 342f), glowFill)
                canvas.drawRect(RectF(size - 156f, 92f, size - 132f, 342f), glowFill)

                // 箱盖分界横线与中央发光锁盘
                canvas.drawLine(88f, 198f, size - 88f, 198f, accentStroke)
                val lockOuter = RectF(206f, 154f, 306f, 250f)
                canvas.drawRoundRect(lockOuter, 18f, 18f, primaryFill)
                val keyholePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF090D16.toInt()
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(size / 2f, 194f, 14f, keyholePaint)
                canvas.drawRect(RectF(size / 2f - 6f, 194f, size / 2f + 6f, 226f), keyholePaint)
            }
            else -> {
                // 桌面机密手札/羊皮卷宗 + 火漆印章
                val pageShadow = RectF(108f, 76f, size - 92f, 348f)
                val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x77000000
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(pageShadow, 16f, 16f, shadowPaint)

                val pageRect = RectF(96f, 68f, size - 104f, 340f)
                val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        96f, 68f, size - 104f, 340f,
                        0xFF3A2E21.toInt(),
                        0xFF221A12.toInt(),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRoundRect(pageRect, 16f, 16f, pagePaint)
                canvas.drawRoundRect(pageRect, 16f, 16f, primaryStroke)

                // 手札加密书写横线
                for (i in 0..3) {
                    val y = 122f + i * 42f
                    val lineEnd = if (i == 3) size - 210f else size - 140f
                    canvas.drawLine(136f, y, lineEnd, y, accentStroke)
                }
                // 右下角火漆印章
                val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.accentHex.toInt()
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(size - 156f, 286f, 28f, sealPaint)
                canvas.drawCircle(size - 156f, 286f, 28f, primaryStroke)
            }
        }

        // 底部双行铭牌：显示道具标题（title.take(10)）和状态（🔒 机关锁匣 或 ✨ 点击搜证），绝不剧透密码数字！
        val bannerRect = RectF(36f, size - 126f, size - 36f, size - 28f)
        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xE6070B14.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(bannerRect, 16f, 16f, bannerPaint)
        canvas.drawRoundRect(bannerRect, 16f, 16f, thinBorder)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 28f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(title.take(10), size / 2f, size - 82f, titlePaint)

        val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (visualType == "box") 0xFFFBBF24.toInt() else palette.primaryHex.toInt()
            textSize = 24f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            if (visualType == "box") "🔒 机关锁匣" else "✨ 点击搜证",
            size / 2f,
            size - 44f,
            statusPaint
        )
        return bmp
    }

    /**
     * 嫌疑人 3D 立牌视觉升级：
     * 绘制风衣/礼帽/装甲立领轮廓、发光护目镜/眼眸、声波律动装饰线与底部高对比身份铭牌
     */
    private fun synthesizeSuspectCardBitmap(
        name: String,
        roomName: String,
        roleIndex: Int,
        palette: ThemePalette
    ): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val haloColor = if (roleIndex % 2 == 0) palette.primaryHex.toInt() else palette.accentHex.toInt()
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size / 2f, size * 0.34f, size * 0.65f,
                intArrayOf(haloColor, palette.surfaceTintHex.toInt(), 0xFF060911.toInt()),
                floatArrayOf(0.04f, 0.52f, 1.0f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = haloColor
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRoundRect(RectF(16f, 16f, size - 16f, size - 16f), 28f, 28f, borderPaint)

        // 背景声波律动装饰线
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = haloColor
            alpha = 130
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        for (i in 0..5) {
            val offset = i * 16f
            val hBar = 14f + (i % 3) * 16f
            canvas.drawLine(46f + offset, 210f - hBar, 46f + offset, 210f + hBar, wavePaint)
            canvas.drawLine(size - 46f - offset, 210f - hBar, size - 46f - offset, 210f + hBar, wavePaint)
        }

        // 风衣宽肩 + V字装甲高领剪影
        val silhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0D1322.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawArc(RectF(74f, 232f, size - 74f, 510f), 180f, 180f, true, silhouettePaint)

        val lapelPath = Path().apply {
            moveTo(size / 2f - 78f, 240f)
            lineTo(size / 2f, 345f)
            lineTo(size / 2f + 78f, 240f)
        }
        val lapelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = haloColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawPath(lapelPath, lapelStroke)

        // 头部与礼帽/战术头冠
        canvas.drawCircle(size / 2f, 174f, 66f, silhouettePaint)
        canvas.drawCircle(size / 2f, 174f, 66f, lapelStroke)
        val hatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF172033.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(142f, 114f, size - 142f, 138f), 12f, 12f, hatPaint)
        canvas.drawRoundRect(RectF(142f, 114f, size - 142f, 138f), 12f, 12f, borderPaint)
        canvas.drawRoundRect(RectF(182f, 76f, size - 182f, 120f), 14f, 14f, hatPaint)
        canvas.drawRoundRect(RectF(182f, 76f, size - 182f, 120f), 14f, 14f, lapelStroke)

        // 发光护目镜与眼眸
        val visorBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = haloColor
            alpha = 75
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(size / 2f - 48f, 158f, size / 2f + 48f, 188f), 10f, 10f, visorBg)
        val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f - 22f, 173f, 7.5f, eyePaint)
        canvas.drawCircle(size / 2f + 22f, 173f, 7.5f, eyePaint)

        // 底部高对比身份铭牌
        val nameBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF0080D1A.toInt()
            style = Paint.Style.FILL
        }
        val cardRect = RectF(28f, size - 138f, size - 28f, size - 26f)
        canvas.drawRoundRect(cardRect, 18f, 18f, nameBg)
        canvas.drawRoundRect(cardRect, 18f, 18f, borderPaint)

        val roomTagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = haloColor
            textSize = 24f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("🗣️ $roomName 驻场嫌疑人", size / 2f, size - 96f, roomTagPaint)

        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 36f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(name.take(9), size / 2f, size - 46f, namePaint)
        return bmp
    }

    /**
     * 房间三大基础表面烘焙材质：
     * - wall: 石砖/装甲墙面的聚光灯晕、铆钉/砖缝高光与暗角（Vignette）、立体护墙板雕花
     * - floor: 石板纹理、神秘法阵/战术网格与边缘阴影
     * - table: 木纹/拉丝金属光泽与双层内嵌金线
     */
    fun synthesizeRoomSurfaceBitmap(
        surfaceType: String,
        roomTitle: String,
        palette: ThemePalette
    ): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        when (surfaceType) {
            "wall" -> {
                // 1. 顶部烘焙聚光灯晕 + 四周暗角 (Vignette)
                val wallBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        size / 2f, size * 0.28f, size * 0.78f,
                        intArrayOf(
                            blendColor(palette.surfaceTintHex.toInt(), palette.primaryHex.toInt(), 0.36f),
                            palette.surfaceTintHex.toInt(),
                            0xFF070A12.toInt()
                        ),
                        floatArrayOf(0f, 0.52f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), wallBg)

                // 2. 石砖/装甲接缝、高光边与铆钉
                val mortarDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xAA04060B.toInt()
                    strokeWidth = 4f
                    style = Paint.Style.STROKE
                }
                val highlightEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primaryHex.toInt()
                    alpha = 62
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }
                val rivetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primaryHex.toInt()
                    alpha = 85
                    style = Paint.Style.FILL
                }
                for (row in 0..5) {
                    val y = row * 56f
                    canvas.drawLine(0f, y, size.toFloat(), y, mortarDark)
                    canvas.drawLine(0f, y + 3f, size.toFloat(), y + 3f, highlightEdge)
                    val offset = if (row % 2 == 0) 0f else 64f
                    for (col in 0..4) {
                        val x = col * 128f + offset
                        canvas.drawLine(x, y, x, y + 56f, mortarDark)
                        canvas.drawLine(x + 2f, y + 3f, x + 2f, y + 53f, highlightEdge)
                        canvas.drawCircle(x + 12f, y + 12f, 2.8f, rivetPaint)
                    }
                }

                // 3. 底部立体护墙板 (Wainscoting) 与内嵌雕花线框
                val waistY = 336f
                val panelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, waistY, 0f, size.toFloat(),
                        0xE610182B.toInt(),
                        0xF5070B14.toInt(),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, waistY, size.toFloat(), size.toFloat(), panelBg)

                val trimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primaryHex.toInt()
                    alpha = 185
                    strokeWidth = 5f
                    style = Paint.Style.STROKE
                }
                val innerTrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.accentHex.toInt()
                    alpha = 115
                    strokeWidth = 2.5f
                    style = Paint.Style.STROKE
                }
                canvas.drawLine(0f, waistY, size.toFloat(), waistY, trimPaint)
                for (i in 0..3) {
                    val left = 20f + i * 122f
                    val outerBox = RectF(left, waistY + 20f, left + 104f, size - 18f)
                    val innerBox = RectF(left + 10f, waistY + 30f, left + 94f, size - 28f)
                    canvas.drawRoundRect(outerBox, 8f, 8f, trimPaint)
                    canvas.drawRoundRect(innerBox, 5f, 5f, innerTrimPaint)
                }
            }

            "floor" -> {
                // 1. 地面中心环境光与四周深邃边缘阴影
                val floorBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        size / 2f, size / 2f, size * 0.70f,
                        intArrayOf(
                            blendColor(palette.surfaceTintHex.toInt(), palette.primaryHex.toInt(), 0.16f),
                            palette.surfaceTintHex.toInt(),
                            0xFF05080F.toInt()
                        ),
                        floatArrayOf(0.05f, 0.58f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), floorBg)

                // 2. 石板地砖网格与微弱高光接缝
                val tileLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x99020408.toInt()
                    strokeWidth = 3.5f
                }
                val tileHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primaryHex.toInt()
                    alpha = 36
                    strokeWidth = 1.5f
                }
                for (i in 1..7) {
                    val p = i * 64f
                    canvas.drawLine(p, 0f, p, size.toFloat(), tileLine)
                    canvas.drawLine(p + 2f, 0f, p + 2f, size.toFloat(), tileHighlight)
                    canvas.drawLine(0f, p, size.toFloat(), p, tileLine)
                    canvas.drawLine(0f, p + 2f, size.toFloat(), p + 2f, tileHighlight)
                }

                // 3. 中央神秘法阵 / 战术方位罗盘与四角符文
                val rugPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primaryHex.toInt()
                    alpha = 115
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                }
                val rugAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.accentHex.toInt()
                    alpha = 125
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                canvas.drawRoundRect(RectF(64f, 64f, size - 64f, size - 64f), 26f, 26f, rugPrimary)
                canvas.drawRoundRect(RectF(82f, 82f, size - 82f, size - 82f), 18f, 18f, rugAccent)

                val cx = size / 2f
                val cy = size / 2f
                canvas.drawCircle(cx, cy, 138f, rugAccent)
                canvas.drawCircle(cx, cy, 98f, rugPrimary)
                canvas.drawCircle(cx, cy, 42f, rugAccent)

                // 罗盘八向射线
                for (deg in 0 until 360 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    val x1 = (cx + cos(rad) * 42f).toFloat()
                    val y1 = (cy + sin(rad) * 42f).toFloat()
                    val x2 = (cx + cos(rad) * 138f).toFloat()
                    val y2 = (cy + sin(rad) * 138f).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, rugPrimary)
                }
            }

            else -> {
                // 书桌/台面：胡桃木纹/拉丝金属光泽 + 双层内嵌金线与包角
                val tableBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, size.toFloat(), size.toFloat(),
                        intArrayOf(
                            0xFF422C1A.toInt(),
                            blendColor(0xFF2D1D11.toInt(), palette.surfaceTintHex.toInt(), 0.25f),
                            0xFF191009.toInt()
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), tableBg)

                // 细腻拉丝木纹条带
                val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x26000000
                    strokeWidth = 3f
                }
                val highlightGrain = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primaryHex.toInt()
                    alpha = 28
                    strokeWidth = 1.5f
                }
                for (i in 1..22) {
                    val y = i * 22f
                    canvas.drawLine(0f, y, size.toFloat(), y, grainPaint)
                    if (i % 2 == 0) {
                        canvas.drawLine(0f, y + 3f, size.toFloat(), y + 3f, highlightGrain)
                    }
                }

                // 双层内嵌金线与边角雕花
                val outerInlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.primaryHex.toInt()
                    alpha = 195
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                }
                val innerInlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.accentHex.toInt()
                    alpha = 145
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                }
                canvas.drawRoundRect(RectF(18f, 18f, size - 18f, size - 18f), 18f, 18f, outerInlay)
                canvas.drawRoundRect(RectF(34f, 34f, size - 34f, size - 34f), 12f, 12f, innerInlay)
            }
        }
        return bmp
    }

    private fun blendColor(c1: Int, c2: Int, ratio: Float): Int {
        val r = ((c1 shr 16 and 0xFF) * (1 - ratio) + (c2 shr 16 and 0xFF) * ratio).toInt()
        val g = ((c1 shr 8 and 0xFF) * (1 - ratio) + (c2 shr 8 and 0xFF) * ratio).toInt()
        val b = ((c1 and 0xFF) * (1 - ratio) + (c2 and 0xFF) * ratio).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
