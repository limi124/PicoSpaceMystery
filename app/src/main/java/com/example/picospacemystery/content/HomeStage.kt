package com.example.picospacemystery.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.picospacemystery.agents.ArtTextureAgent
import com.example.picospacemystery.agents.MultiAgentOrchestrator
import com.example.picospacemystery.agents.OrchestrationResult
import com.example.picospacemystery.audio.SpatialAudioSynthesizer
import com.example.picospacemystery.data.RoomScanner
import com.example.picospacemystery.model.*
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.launch

private enum class StagePhase { ONBOARDING, ORCHESTRATING, PLAYING }

@Composable
fun HomeStage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val orchestrator = remember { MultiAgentOrchestrator() }
    val artAgent = remember { ArtTextureAgent() }
    val scanner = remember { RoomScanner() }
    val room3D = remember { EscapeRoom3DScene() }
    val audioSynth = SpatialAudioSynthesizer

    var phase by remember { mutableStateOf(StagePhase.ONBOARDING) }
    var gameConfig by remember { mutableStateOf(CustomGameConfig()) }
    var useRemoteImageApi by remember { mutableStateOf(true) }
    var showApiSettings by remember { mutableStateOf(false) }
    var llmConfig by remember { mutableStateOf(LlmConfig()) }

    var agentLogs by remember { mutableStateOf<List<AgentStepLog>>(emptyList()) }
    var orchestrationResult by remember { mutableStateOf<OrchestrationResult?>(null) }

    // 多房间递进与空间机位状态
    var currentRoomIndex by remember { mutableStateOf(0) }
    var unlockedRooms by remember { mutableStateOf(setOf(0)) }
    var unlockedDoorForRooms by remember { mutableStateOf(emptySet<Int>()) }
    var currentViewpointIndex by remember { mutableStateOf(0) }
    var sceneSyncToken by remember { mutableStateOf(0) }
    var renderedSyncToken by remember { mutableStateOf(-1) }
    var hudCollapsed by remember { mutableStateOf(false) }

    // 搜证、工具破译、拟物拨轮与终局推理状态
    var selectedClue by remember { mutableStateOf<ClueItem?>(null) }
    var selectedSuspect by remember { mutableStateOf<SuspectItem?>(null) }
    var floatingClueToast by remember { mutableStateOf<ClueItem?>(null) }
    var floatingSuspectDialog by remember { mutableStateOf<SuspectItem?>(null) }
    var suspectSpotLabel by remember { mutableStateOf("右侧暗角") }
    var discoveredClueIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var decryptedClueIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeToolId by remember { mutableStateOf<String?>(null) }
    var confrontedSuspectIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var dialDigits by remember { mutableStateOf(listOf(0, 0, 0)) }
    var passwordFeedback by remember { mutableStateOf("") }
    var audioBanner by remember { mutableStateOf<String?>(null) }

    var selectedCulpritIndex by remember { mutableStateOf<Int?>(null) }
    var culpritFeedback by remember { mutableStateOf("") }
    var caseFullySolved by remember { mutableStateOf(false) }

    val suspectChats = remember { mutableStateMapOf<String, List<Pair<String, String>>>() }
    var chatInput by remember { mutableStateOf("") }
    var isAskingSuspect by remember { mutableStateOf(false) }

    fun switchViewpoint(vpIdx: Int) {
        val vp = SpatialViewpoint.PRESETS.getOrNull(vpIdx) ?: SpatialViewpoint.PRESETS.first()
        currentViewpointIndex = vp.index
        room3D.setViewpointOffset(Vector3(vp.offsetX, vp.offsetY, vp.offsetZ))
        audioSynth.playTeleport()
        audioBanner = "👣 已移动至机位：${vp.label}"
    }

    fun enterRoom(roomIdx: Int, res: OrchestrationResult) {
        res.script.rooms.getOrNull(roomIdx) ?: return
        currentRoomIndex = roomIdx
        unlockedRooms = unlockedRooms + roomIdx
        selectedSuspect = null
        selectedClue = null
        floatingClueToast = null
        floatingSuspectDialog = null
        dialDigits = listOf(0, 0, 0)
        passwordFeedback = ""
        currentViewpointIndex = 0
        room3D.setViewpointOffset(Vector3(0f, 0f, 0f))
        audioSynth.playRoomTransition(roomIdx)
        sceneSyncToken += 1
    }

    fun triggerSuspectRandomPatrol(res: OrchestrationResult) {
        val roomLevel = res.script.rooms.getOrNull(currentRoomIndex) ?: return
        val suspect = roomLevel.suspects.firstOrNull() ?: return
        val latestReply = suspectChats[suspect.suspectId]?.lastOrNull()?.second ?: suspect.initialGreeting
        val spot = room3D.relocateSuspectRandomly(
            suspectName = suspect.name,
            dialogue = latestReply,
            palette = res.visualAssets.palette
        )
        suspectSpotLabel = spot.label
        audioSynth.playClueDiscovered(spot.directionAudioHint)
        audioBanner = "👣 【${suspect.name}】在密室中随机走动，现身于【${spot.label}】！转头寻找并与TA对话。"
    }

    fun decryptClueWithTool(clue: ClueItem, res: OrchestrationResult) {
        val roomLevel = res.script.rooms.getOrNull(currentRoomIndex) ?: return
        val clueIdx = roomLevel.clues.indexOfFirst { it.clueId == clue.clueId }
        decryptedClueIds = decryptedClueIds + clue.clueId
        audioSynth.playToolDecrypt()
        audioBanner = "🔦 已使用勘查工具显影破译【${clue.title}】！"
        if (clueIdx >= 0) {
            val signBmp = res.visualAssets.clueSignBitmaps[clue.clueId]
            room3D.onClueInteracted(clueIdx, signBmp)
        }
    }

    fun handleClueClicked(clue: ClueItem, res: OrchestrationResult, from3DSpace: Boolean) {
        val roomLevel = res.script.rooms.getOrNull(currentRoomIndex) ?: return
        val clueIdx = roomLevel.clues.indexOfFirst { it.clueId == clue.clueId }
        discoveredClueIds = discoveredClueIds + clue.clueId

        if (clue.isTool) {
            activeToolId = clue.clueId
        }
        // 若点击的是加密线索且当前已持有对应工具，可自动或一键显影
        val needsTool = clue.requiredToolId != null && !decryptedClueIds.contains(clue.clueId)
        val hasRequiredTool = clue.requiredToolId != null && discoveredClueIds.contains(clue.requiredToolId)

        val audioEvt = res.script.spatialAudioEvents.firstOrNull { it.triggerClueId == clue.clueId }
        val dirHint = audioEvt?.relativeDirection ?: when (clueIdx) {
            0 -> "right"
            1 -> "left"
            2 -> "behind"
            else -> "center"
        }
        audioSynth.playClueDiscovered(dirHint)

        if (clueIdx >= 0) {
            val showSignBmp = if (!needsTool) res.visualAssets.clueSignBitmaps[clue.clueId] else null
            room3D.onClueInteracted(clueIdx, showSignBmp)
        }

        audioBanner = when {
            clue.isTool -> "🧰 已拾取并装备勘查工具【${clue.title}】！"
            needsTool && hasRequiredTool -> "🔍 发现加密痕迹【${clue.title}】，点击「使用工具显影」即可破译！"
            needsTool -> "⚠️ 【${clue.title}】表面模糊，请先在房间内寻找勘查工具！"
            else -> "🔊 已搜获空间线索【${clue.title}】（声源方位：$dirHint）"
        }

        selectedSuspect = null
        selectedClue = clue
        floatingSuspectDialog = null

        // 每次玩家搜获新线索时，驻场嫌疑人也有概率在 3D 房间内随机巡逻走动并刷新头顶 3D 气泡反应！
        val suspect = roomLevel.suspects.firstOrNull()
        if (suspect != null && from3DSpace) {
            val reaction = when {
                clue.isTool -> "你居然找到了${clue.title}？小心别乱照墙面！"
                needsTool -> "光凭肉眼你是看不穿那层伪装的…"
                else -> "咳…没想到连${clue.title}都被你翻出来了！"
            }
            val spot = room3D.relocateSuspectRandomly(
                suspectName = suspect.name,
                dialogue = reaction,
                palette = res.visualAssets.palette
            )
            suspectSpotLabel = spot.label
        }

        // 关键体验优化：在 3D 环顾模式下点击普通/工具线索，不强弹全屏大面板糊脸，而是弹出底部轻量拾取卡！
        if (from3DSpace && hudCollapsed && !clue.isLocked) {
            floatingClueToast = clue
        } else {
            floatingClueToast = null
            hudCollapsed = false
        }
    }

    fun selectSuspectById(suspect: SuspectItem, from3DSpace: Boolean = false) {
        selectedClue = null
        floatingClueToast = null
        selectedSuspect = suspect
        if (!suspectChats.containsKey(suspect.suspectId)) {
            suspectChats[suspect.suspectId] = listOf(
                "assistant" to "（${suspect.name}警惕地打量着你）${suspect.initialGreeting}"
            )
        }
        val latestReply = suspectChats[suspect.suspectId]?.lastOrNull()?.second ?: suspect.initialGreeting
        orchestrationResult?.let { res ->
            room3D.updateSuspectSpeechBubble(suspect.name, latestReply, res.visualAssets.palette)
            suspectSpotLabel = room3D.getCurrentSuspectSpot().label
        }
        if (from3DSpace && hudCollapsed) {
            floatingSuspectDialog = suspect
        } else {
            floatingSuspectDialog = null
            hudCollapsed = false
        }
    }

    fun launchMultiAgentPipeline() {
        phase = StagePhase.ORCHESTRATING
        currentRoomIndex = 0
        unlockedRooms = setOf(0)
        unlockedDoorForRooms = emptySet()
        currentViewpointIndex = 0
        discoveredClueIds = emptySet()
        decryptedClueIds = emptySet()
        activeToolId = null
        confrontedSuspectIds = emptySet()
        dialDigits = listOf(0, 0, 0)
        passwordFeedback = ""
        selectedClue = null
        selectedSuspect = null
        floatingClueToast = null
        audioBanner = null
        selectedCulpritIndex = null
        culpritFeedback = ""
        caseFullySolved = false
        suspectChats.clear()

        scope.launch {
            val room = scanner.scan(useMock = true)
            val result = orchestrator.runPipeline(
                gameConfig = gameConfig,
                room = room,
                config = llmConfig,
                useRemoteImageApi = useRemoteImageApi,
                onProgress = { logs -> agentLogs = logs }
            )
            orchestrationResult = result
            enterRoom(0, result)
            phase = StagePhase.PLAYING
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(orchestrationResult, currentRoomIndex, unlockedDoorForRooms, hudCollapsed, discoveredClueIds, decryptedClueIds) {
                detectSpatialTapGesture(context, targetedToEntity = TargetEntity.any()) { tapValue ->
                    val res = orchestrationResult ?: return@detectSpatialTapGesture
                    val roomLevel = res.script.rooms.getOrNull(currentRoomIndex) ?: return@detectSpatialTapGesture
                    val tapped = tapValue.targetEntity ?: return@detectSpatialTapGesture
                    val target = room3D.interactiveTargetMap[tapped] ?: return@detectSpatialTapGesture
                    when (target.first) {
                        "clue" -> {
                            roomLevel.clues.getOrNull(target.second)?.let {
                                handleClueClicked(it, res, from3DSpace = true)
                            }
                        }
                        "suspect" -> {
                            roomLevel.suspects.firstOrNull()?.let { selectSuspectById(it, from3DSpace = true) }
                        }
                        "teleport" -> {
                            switchViewpoint(target.second)
                        }
                        "door" -> {
                            val doorOpen = unlockedDoorForRooms.contains(currentRoomIndex)
                            if (doorOpen && currentRoomIndex + 1 < res.script.rooms.size) {
                                enterRoom(currentRoomIndex + 1, res)
                                audioBanner = "🚪 已推开密室大门，进入「${res.script.rooms[currentRoomIndex].roomName}」！"
                            } else {
                                val lockClue = roomLevel.clues.lastOrNull()
                                if (lockClue != null) handleClueClicked(lockClue, res, from3DSpace = false)
                                audioSynth.playUnlockError()
                                audioBanner = "🔒 密室大门尚未解锁！请先搜集本房间 3 处线索并解开机关锁。"
                            }
                        }
                    }
                }
            },
        attachments = {
            AttachmentPanel(id = "hud", size = IntSize(1760, 1040)) {
                PicoTheme {
                    if (phase == StagePhase.PLAYING && hudCollapsed) {
                        val res = orchestrationResult
                        val curRoom = res?.script?.rooms?.getOrNull(currentRoomIndex)
                        val foundCount = curRoom?.clues?.count { !it.isLocked && discoveredClueIds.contains(it.clueId) } ?: 0
                        val totalSearchable = curRoom?.clues?.count { !it.isLocked } ?: 3
                        val equippedTool = res?.script?.clues?.firstOrNull { it.clueId == activeToolId }

                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // 3D 连续搜证轻量浮动拾取卡片（不遮挡 3D 房间视野）
                                val toastClue = floatingClueToast
                                if (toastClue != null && res != null) {
                                    val isEncrypted = toastClue.requiredToolId != null && !decryptedClueIds.contains(toastClue.clueId)
                                    val hasTool = toastClue.requiredToolId != null && discoveredClueIds.contains(toastClue.requiredToolId)
                                    Column(
                                        modifier = Modifier
                                            .width(860.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color(0xF20A101F))
                                            .border(1.5.dp, Color(0xFF4DEEEA), RoundedCornerShape(20.dp))
                                            .padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = when {
                                                    toastClue.isTool -> "🧰 拾取并装备工具：${toastClue.title}"
                                                    isEncrypted -> "🔒 发现加密痕迹：${toastClue.title}"
                                                    else -> "✅ 搜获空间线索：${toastClue.title}"
                                                },
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4DEEEA)
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isEncrypted) Color(0x44F59E0B) else Color(0x3334D399))
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    text = if (isEncrypted) toastClue.encryptedHint else toastClue.shortHint,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isEncrypted) Color(0xFFFDE68A) else Color(0xFF6EE7B7)
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (isEncrypted) toastClue.encryptedContent else toastClue.content,
                                            fontSize = 14.sp,
                                            color = Color.White,
                                            lineHeight = 20.sp
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            if (isEncrypted && hasTool) {
                                                val reqTool = res.script.clues.firstOrNull { it.clueId == toastClue.requiredToolId }
                                                SpatialActionButton(
                                                    text = "🔦 使用【${reqTool?.title ?: "勘查工具"}】显影破译",
                                                    accent = Color(0xFFFBBF24),
                                                    filled = true,
                                                    onClick = { decryptClueWithTool(toastClue, res) }
                                                )
                                            }
                                            SpatialActionButton(
                                                text = "📖 打开完整推理面板",
                                                accent = Color(0xFF4DEEEA),
                                                onClick = {
                                                    floatingClueToast = null
                                                    hudCollapsed = false
                                                }
                                            )
                                            SpatialActionButton(
                                                text = "✕ 收起提示 · 继续环顾搜证",
                                                accent = Color(0xFF94A3B8),
                                                onClick = { floatingClueToast = null }
                                            )
                                        }
                                    }
                                }

                                // 3D 空间现场嫌疑人轻量对话与随机巡逻卡片（点击 3D 人物或头顶气泡直接展开对话，不遮挡 3D 视野）
                                val activeFloatingSuspect = floatingSuspectDialog
                                if (activeFloatingSuspect != null && res != null) {
                                    val keyEvidence = curRoom?.clues?.firstOrNull { it.clueId == activeFloatingSuspect.keyEvidenceClueId }
                                    val hasKeyEvidence = keyEvidence != null && discoveredClueIds.contains(keyEvidence.clueId)
                                    val isConfronted = confrontedSuspectIds.contains(activeFloatingSuspect.suspectId)
                                    val lastMsg = suspectChats[activeFloatingSuspect.suspectId]?.lastOrNull()?.second
                                        ?: activeFloatingSuspect.initialGreeting

                                    Column(
                                        modifier = Modifier
                                            .width(920.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(Color(0xF20B1120))
                                            .border(1.5.dp, Color(0xFFA78BFA), RoundedCornerShape(20.dp))
                                            .padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🗣️ 3D 现场对话：${activeFloatingSuspect.name}（当前闪现点位：$suspectSpotLabel）",
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFA78BFA)
                                            )
                                            SpatialActionButton(
                                                text = "🎲 让人物随机走动换位",
                                                accent = Color(0xFF38BDF8),
                                                onClick = { triggerSuspectRandomPatrol(res) }
                                            )
                                        }
                                        Text(
                                            text = "「$lastMsg」",
                                            fontSize = 14.sp,
                                            color = Color.White,
                                            lineHeight = 20.sp
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            activeFloatingSuspect.quickQuestions.take(3).forEach { (qText, aText) ->
                                                SpatialActionButton(
                                                    text = "💬 ${qText.take(12)}",
                                                    accent = Color(0xFF38BDF8),
                                                    onClick = {
                                                        val prev = suspectChats[activeFloatingSuspect.suspectId].orEmpty()
                                                        suspectChats[activeFloatingSuspect.suspectId] = prev + ("user" to qText) + ("assistant" to aText)
                                                        room3D.updateSuspectSpeechBubble(activeFloatingSuspect.name, aText, res.visualAssets.palette)
                                                        audioSynth.playDialClick(3)
                                                    }
                                                )
                                            }
                                            if (hasKeyEvidence && keyEvidence != null) {
                                                SpatialActionButton(
                                                    text = if (isConfronted) "✅ 已破防口供" else "📢 出示铁证【${keyEvidence.title.take(6)}】",
                                                    accent = Color(0xFFFBBF24),
                                                    filled = true,
                                                    onClick = {
                                                        val prev = suspectChats[activeFloatingSuspect.suspectId].orEmpty()
                                                        suspectChats[activeFloatingSuspect.suspectId] = prev +
                                                            ("user" to "📢【出示铁证：${keyEvidence.title}】${activeFloatingSuspect.evidenceConfrontQuestion}") +
                                                            ("assistant" to "💥（心理防线击破）${activeFloatingSuspect.breakthroughReply}")
                                                        confrontedSuspectIds = confrontedSuspectIds + activeFloatingSuspect.suspectId
                                                        room3D.updateSuspectSpeechBubble(
                                                            activeFloatingSuspect.name,
                                                            activeFloatingSuspect.breakthroughReply,
                                                            res.visualAssets.palette
                                                        )
                                                        audioSynth.playToolDecrypt()
                                                    }
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(modifier = Modifier.weight(1f)) {
                                                SpatialInputField("在 3D 房间内直接向 ${activeFloatingSuspect.name} 自由提问…", chatInput) { chatInput = it }
                                            }
                                            SpatialActionButton(
                                                text = if (isAskingSuspect) "思考中…" else "🗣️ 发送提问",
                                                accent = Color(0xFFA78BFA),
                                                filled = true,
                                                onClick = {
                                                    val q = chatInput.trim()
                                                    if (q.isNotBlank() && !isAskingSuspect) {
                                                        chatInput = ""
                                                        val prev = suspectChats[activeFloatingSuspect.suspectId].orEmpty()
                                                        suspectChats[activeFloatingSuspect.suspectId] = prev + ("user" to q)
                                                        isAskingSuspect = true
                                                        scope.launch {
                                                            val reply = orchestrator.askSuspect(activeFloatingSuspect, res.script, prev, q, llmConfig)
                                                            suspectChats[activeFloatingSuspect.suspectId] =
                                                                suspectChats[activeFloatingSuspect.suspectId].orEmpty() + ("assistant" to reply)
                                                            room3D.updateSuspectSpeechBubble(activeFloatingSuspect.name, reply, res.visualAssets.palette)
                                                            isAskingSuspect = false
                                                        }
                                                    }
                                                }
                                            )
                                            SpatialActionButton(
                                                text = "✕ 关闭对话",
                                                accent = Color(0xFF94A3B8),
                                                onClick = { floatingSuspectDialog = null }
                                            )
                                        }
                                    }
                                }

                                // 底部常驻轻量空间探索与瞬移胶囊栏
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(Color(0xF00B1120))
                                        .border(1.5.dp, Color(0xFF4DEEEA), RoundedCornerShape(999.dp))
                                        .padding(horizontal = 22.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "🕵️ ${curRoom?.roomName ?: "密室"} (线索 $foundCount/$totalSearchable)" +
                                            (equippedTool?.let { "｜🧰 ${it.title}" } ?: ""),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    SpatialViewpoint.PRESETS.forEach { vp ->
                                        val active = currentViewpointIndex == vp.index
                                        SpatialActionButton(
                                            text = vp.label,
                                            accent = if (active) Color(0xFF34D399) else Color(0xFF94A3B8),
                                            filled = active,
                                            onClick = { switchViewpoint(vp.index) }
                                        )
                                    }
                                    if (res != null) {
                                        SpatialActionButton(
                                            text = "🎲 人物随机换位 ($suspectSpotLabel)",
                                            accent = Color(0xFFA78BFA),
                                            onClick = { triggerSuspectRandomPatrol(res) }
                                        )
                                    }
                                    SpatialActionButton(
                                        text = "📖 展开控制面板",
                                        accent = Color(0xFF4DEEEA),
                                        filled = true,
                                        onClick = {
                                            floatingClueToast = null
                                            floatingSuspectDialog = null
                                            hudCollapsed = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(26.dp))
                                .backgroundMaterial(style = Material.Thick)
                                .background(Color(0xE6080D1A))
                                .border(1.5.dp, Color(0x554DEEEA), RoundedCornerShape(26.dp))
                        ) {
                            when (phase) {
                                StagePhase.ONBOARDING -> {
                                    OnboardingView(
                                        gameConfig = gameConfig,
                                        onGameConfigChange = { gameConfig = it },
                                        onRandomize = { gameConfig = orchestrator.randomIdea() },
                                        useRemoteImageApi = useRemoteImageApi,
                                        onToggleRemoteImage = { useRemoteImageApi = it },
                                        showApiSettings = showApiSettings,
                                        onToggleApiSettings = { showApiSettings = !showApiSettings },
                                        llmConfig = llmConfig,
                                        onConfigChange = { llmConfig = it },
                                        onStart = { launchMultiAgentPipeline() }
                                    )
                                }

                                StagePhase.ORCHESTRATING -> {
                                    OrchestrationMonitorView(
                                        worldview = gameConfig.worldview,
                                        logs = agentLogs
                                    )
                                }

                                StagePhase.PLAYING -> {
                                    val res = orchestrationResult ?: return@Box
                                    PlayingMysteryView(
                                        result = res,
                                        currentRoomIndex = currentRoomIndex,
                                        unlockedRooms = unlockedRooms,
                                        unlockedDoorForRooms = unlockedDoorForRooms,
                                        currentViewpointIndex = currentViewpointIndex,
                                        selectedClue = selectedClue,
                                        selectedSuspect = selectedSuspect,
                                        discoveredClueIds = discoveredClueIds,
                                        decryptedClueIds = decryptedClueIds,
                                        activeToolId = activeToolId,
                                        confrontedSuspectIds = confrontedSuspectIds,
                                        dialDigits = dialDigits,
                                        onDialChange = { slot, delta ->
                                            val next = (dialDigits[slot] + delta + 10) % 10
                                            dialDigits = dialDigits.toMutableList().also { it[slot] = next }
                                            audioSynth.playDialClick(next)
                                        },
                                        passwordFeedback = passwordFeedback,
                                        audioBanner = audioBanner,
                                        suspectChats = suspectChats,
                                        chatInput = chatInput,
                                        onChatInputChange = { chatInput = it },
                                        isAskingSuspect = isAskingSuspect,
                                        selectedCulpritIndex = selectedCulpritIndex,
                                        culpritFeedback = culpritFeedback,
                                        caseFullySolved = caseFullySolved,
                                        onSwitchRoom = { targetIdx -> enterRoom(targetIdx, res) },
                                        onSwitchViewpoint = { vpIdx -> switchViewpoint(vpIdx) },
                                        onSelectClue = { clue -> handleClueClicked(clue, res, from3DSpace = false) },
                                        onDecryptClue = { clue -> decryptClueWithTool(clue, res) },
                                        onSelectSuspect = { suspect -> selectSuspectById(suspect) },
                                        onQuickQuestion = { suspect, qText, aText ->
                                            val prev = suspectChats[suspect.suspectId].orEmpty()
                                            suspectChats[suspect.suspectId] = prev + ("user" to qText) + ("assistant" to aText)
                                            room3D.updateSuspectSpeechBubble(suspect.name, aText, res.visualAssets.palette)
                                            audioSynth.playDialClick(3)
                                        },
                                        onConfrontSuspect = { suspect, evidenceTitle ->
                                            val prev = suspectChats[suspect.suspectId].orEmpty()
                                            suspectChats[suspect.suspectId] = prev +
                                                ("user" to "📢【出示铁证：$evidenceTitle】${suspect.evidenceConfrontQuestion}") +
                                                ("assistant" to "💥（心理防线击破）${suspect.breakthroughReply}")
                                            confrontedSuspectIds = confrontedSuspectIds + suspect.suspectId
                                            room3D.updateSuspectSpeechBubble(suspect.name, suspect.breakthroughReply, res.visualAssets.palette)
                                            audioSynth.playToolDecrypt()
                                            audioBanner = "💥 已用关键物证击破【${suspect.name}】心理防线，获取核心密码换算规则！"
                                        },
                                        onUnlockAttempt = { code ->
                                            val attempted = dialDigits.joinToString("")
                                            if (attempted == code) {
                                                unlockedDoorForRooms = unlockedDoorForRooms + currentRoomIndex
                                                audioSynth.playUnlockSuccess()
                                                room3D.animateDoorOpen(true)
                                                if (currentRoomIndex + 1 < res.script.rooms.size) {
                                                    unlockedRooms = unlockedRooms + (currentRoomIndex + 1)
                                                    passwordFeedback = "🎉 机关锁破译成功！3D 密室大门已绕轴推开，可点击「🚪 进入下一间密室」！"
                                                } else {
                                                    passwordFeedback = "🎉 终极核心锁匣已开启！请完成下方的【终局真凶裁决】揭晓全案真相！"
                                                }
                                                sceneSyncToken += 1
                                            } else {
                                                audioSynth.playUnlockError()
                                                passwordFeedback = "❌ 密码 [$attempted] 不正确！请核对已破译线索与嫌疑人破防口供中的换算顺序。"
                                            }
                                        },
                                        onSelectCulpritOption = { idx ->
                                            selectedCulpritIndex = idx
                                            if (idx == res.script.correctCulpritIndex) {
                                                caseFullySolved = true
                                                audioSynth.playUnlockSuccess()
                                                culpritFeedback = "🏆 推理完全正确！你已完美侦破三层连环密室奇案！"
                                            } else {
                                                audioSynth.playUnlockError()
                                                culpritFeedback = "⚠️ 推理存在矛盾：再回想一下三间密室里的关键物证与动机，重新指认！"
                                            }
                                        },
                                        onEnterNextRoom = {
                                            if (currentRoomIndex + 1 < res.script.rooms.size) {
                                                enterRoom(currentRoomIndex + 1, res)
                                            }
                                        },
                                        onSendChat = { suspect ->
                                            val q = chatInput.trim()
                                            if (q.isNotBlank() && !isAskingSuspect) {
                                                chatInput = ""
                                                val prev = suspectChats[suspect.suspectId].orEmpty()
                                                suspectChats[suspect.suspectId] = prev + ("user" to q)
                                                isAskingSuspect = true
                                                scope.launch {
                                                    val reply = orchestrator.askSuspect(suspect, res.script, prev, q, llmConfig)
                                                    suspectChats[suspect.suspectId] = suspectChats[suspect.suspectId].orEmpty() + ("assistant" to reply)
                                                    room3D.updateSuspectSpeechBubble(suspect.name, reply, res.visualAssets.palette)
                                                    isAskingSuspect = false
                                                }
                                            }
                                        },
                                        onToggleCollapse = { hudCollapsed = true },
                                        onRestart = { phase = StagePhase.ONBOARDING }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        initial = { content, attachments ->
            attachments.entity("hud")?.let { hudEntity ->
                hudEntity.components.set(
                    TransformComponent(
                        position = Vector3(0f, 1.32f, -1.22f),
                        rotation = EulerAngles(0f, 0f, 0f),
                        scale = Vector3(1.18f, 1.18f, 1.18f)
                    )
                )
                content.addEntity(hudEntity)
            }
            room3D.buildInitialWhiteboxRoom(content, artAgent.resolvePalette(gameConfig.worldview))
        },
        update = { _, _ ->
            val res = orchestrationResult
            if (res != null && sceneSyncToken != renderedSyncToken) {
                val doorUnlocked = unlockedDoorForRooms.contains(currentRoomIndex)
                room3D.applyRoomStage(res, currentRoomIndex, doorUnlocked)
                suspectSpotLabel = room3D.getCurrentSuspectSpot().label
                renderedSyncToken = sceneSyncToken
            }
        }
    )
}

@Composable
private fun OnboardingView(
    gameConfig: CustomGameConfig,
    onGameConfigChange: (CustomGameConfig) -> Unit,
    onRandomize: () -> Unit,
    useRemoteImageApi: Boolean,
    onToggleRemoteImage: (Boolean) -> Unit,
    showApiSettings: Boolean,
    onToggleApiSettings: () -> Unit,
    llmConfig: LlmConfig,
    onConfigChange: (LlmConfig) -> Unit,
    onStart: () -> Unit
) {
    val worldPresets = listOf("赛博朋克实验室", "民国雨夜古宅", "深海克苏鲁", "魔法学院禁书地窖")
    val stylePresets = listOf("悬疑烧脑", "微恐惊悚", "搞笑荒诞", "硬核推理")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "AI 连环实景密室 · 多 Agent 空间导演室",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4DEEEA)
        )
        Text(
            text = "360° 全闭合三层递进密室｜空间瞬移探索｜勘查工具两步显影｜证物破防审讯｜双耳空间音效",
            fontSize = 14.sp,
            color = Color(0xFFE2E8F0)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0x99111827))
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                worldPresets.forEach { preset ->
                    val active = gameConfig.worldview == preset
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Color(0x444DEEEA) else Color(0x1AFFFFFF))
                            .border(1.dp, if (active) Color(0xFF4DEEEA) else Color(0x44FFFFFF), RoundedCornerShape(10.dp))
                            .clickable { onGameConfigChange(gameConfig.copy(worldview = preset)) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(text = preset, fontSize = 13.sp, color = Color.White, maxLines = 1)
                    }
                }
            }

            SpatialInputField(
                label = "自定义世界观（四大预设均含独立三层剧本，也支持输入任意新脑洞）",
                value = gameConfig.worldview,
                onValueChange = { onGameConfigChange(gameConfig.copy(worldview = it)) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                stylePresets.forEach { style ->
                    val active = gameConfig.plotStyle == style
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Color(0x44A78BFA) else Color(0x1AFFFFFF))
                            .border(1.dp, if (active) Color(0xFFA78BFA) else Color(0x44FFFFFF), RoundedCornerShape(10.dp))
                            .clickable { onGameConfigChange(gameConfig.copy(plotStyle = style)) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(text = style, fontSize = 13.sp, color = Color.White, maxLines = 1)
                    }
                }
            }

            SpatialInputField(
                label = "自定义 3 个房间的驻场角色 / 嫌疑人（用顿号分隔，留空则由 AI 自动匹配）",
                value = gameConfig.customRoles,
                onValueChange = { onGameConfigChange(gameConfig.copy(customRoles = it)) }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpatialActionButton(
                text = "✨ 启动多 Agent 生成三层密室",
                accent = Color(0xFF4DEEEA),
                filled = true,
                onClick = onStart
            )
            SpatialActionButton(
                text = "🎲 AI 随机脑洞",
                accent = Color(0xFFA78BFA),
                onClick = onRandomize
            )
            SpatialActionButton(
                text = if (showApiSettings) "▲ 收起 API 配置" else "⚙️ ECNU 模型 (${llmConfig.chatModel})",
                accent = Color(0xFF34D399),
                onClick = onToggleApiSettings
            )
        }

        if (showApiSettings) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xAA0F172A))
                    .border(1.dp, Color(0x334DEEEA), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("✅ 已默认内置 ECNU 校内大模型 Key，可一键切换模型：", fontSize = 12.sp, color = Color(0xFF6EE7B7))
                    listOf("ecnu-plus", "ecnu-max").forEach { m ->
                        SpatialActionButton(
                            text = m,
                            accent = if (llmConfig.chatModel == m) Color(0xFF34D399) else Color(0xFF94A3B8),
                            filled = llmConfig.chatModel == m,
                            onClick = { onConfigChange(llmConfig.copy(chatModel = m)) }
                        )
                    }
                }
                SpatialInputField("Base URL", llmConfig.baseUrl) { onConfigChange(llmConfig.copy(baseUrl = it)) }
                SpatialInputField("API Key", llmConfig.apiKey) { onConfigChange(llmConfig.copy(apiKey = it)) }
                SpatialInputField("Chat Model (ECNU 剧本生成)", llmConfig.chatModel) { onConfigChange(llmConfig.copy(chatModel = it)) }
                SpatialInputField("Image Model (硅基流动生图)", llmConfig.imageModel) { onConfigChange(llmConfig.copy(imageModel = it)) }
                SpatialInputField("Image API Key (硅基流动 Key)", llmConfig.imageApiKey) { onConfigChange(llmConfig.copy(imageApiKey = it)) }
                Row(
                    modifier = Modifier.clickable { onToggleRemoteImage(!useRemoteImageApi) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (useRemoteImageApi) "☑️ 已默认开启：硅基流动 Kolors 实时 AI 文生图贴图" else "⬜ 仅使用本地烘焙光影贴图合成",
                        fontSize = 13.sp,
                        color = Color(0xFF34D399)
                    )
                }
            }
        }
    }
}

@Composable
private fun OrchestrationMonitorView(
    worldview: String,
    logs: List<AgentStepLog>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "多 Agent 正在构建 360° 三层连环密室 · 「$worldview」",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF4DEEEA)
        )
        logs.forEach { log ->
            val stateColor = when (log.state) {
                AgentRunState.DONE -> Color(0xFF34D399)
                AgentRunState.RUNNING -> Color(0xFFFBBF24)
                AgentRunState.IDLE -> Color(0xFF94A3B8)
            }
            val stateLabel = when (log.state) {
                AgentRunState.DONE -> "已完成"
                AgentRunState.RUNNING -> "执行中"
                AgentRunState.IDLE -> "等待中"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0x99111827))
                    .border(1.dp, stateColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                    Text("${log.role.badge} ${log.role.displayName}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(log.message, fontSize = 13.sp, color = Color(0xFFE2E8F0))
                }
                Text(stateLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = stateColor)
            }
        }
    }
}

@Composable
private fun PlayingMysteryView(
    result: OrchestrationResult,
    currentRoomIndex: Int,
    unlockedRooms: Set<Int>,
    unlockedDoorForRooms: Set<Int>,
    currentViewpointIndex: Int,
    selectedClue: ClueItem?,
    selectedSuspect: SuspectItem?,
    discoveredClueIds: Set<String>,
    decryptedClueIds: Set<String>,
    activeToolId: String?,
    confrontedSuspectIds: Set<String>,
    dialDigits: List<Int>,
    onDialChange: (Int, Int) -> Unit,
    passwordFeedback: String,
    audioBanner: String?,
    suspectChats: Map<String, List<Pair<String, String>>>,
    chatInput: String,
    onChatInputChange: (String) -> Unit,
    isAskingSuspect: Boolean,
    selectedCulpritIndex: Int?,
    culpritFeedback: String,
    caseFullySolved: Boolean,
    onSwitchRoom: (Int) -> Unit,
    onSwitchViewpoint: (Int) -> Unit,
    onSelectClue: (ClueItem) -> Unit,
    onDecryptClue: (ClueItem) -> Unit,
    onSelectSuspect: (SuspectItem) -> Unit,
    onQuickQuestion: (SuspectItem, String, String) -> Unit,
    onConfrontSuspect: (SuspectItem, String) -> Unit,
    onUnlockAttempt: (String) -> Unit,
    onSelectCulpritOption: (Int) -> Unit,
    onEnterNextRoom: () -> Unit,
    onSendChat: (SuspectItem) -> Unit,
    onToggleCollapse: () -> Unit,
    onRestart: () -> Unit
) {
    val palette = result.visualAssets.palette
    val script = result.script
    val roomLevel = script.rooms.getOrNull(currentRoomIndex) ?: script.rooms.first()
    val isCurrentDoorUnlocked = unlockedDoorForRooms.contains(currentRoomIndex)
    val equippedTool = script.clues.firstOrNull { it.clueId == activeToolId }

    val nonLockedClues = roomLevel.clues.filter { !it.isLocked }
    val foundCountInRoom = nonLockedClues.count { discoveredClueIds.contains(it.clueId) }
    val allCluesReadyForLock = nonLockedClues.all { c ->
        discoveredClueIds.contains(c.clueId) && (c.requiredToolId == null || decryptedClueIds.contains(c.clueId))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 顶部标题与操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "《${script.caseTitle}》 · ${roomLevel.roomName}",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.primaryColor
                )
                Text(
                    text = "${roomLevel.roomAmbience}｜目标：${roomLevel.doorPrompt}",
                    fontSize = 12.sp,
                    color = Color(0xFFE2E8F0)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpatialActionButton("👁️ 环顾 3D 密室搜证", Color(0xFF4DEEEA), filled = true, onClick = onToggleCollapse)
                SpatialActionButton("🔄 换世界观", Color(0xFF94A3B8), onClick = onRestart)
            }
        }

        // 多房间进度导航 + 4 机位空间瞬移快捷栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x99111827))
                .padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            script.rooms.forEach { r ->
                val isCurrent = r.roomIndex == currentRoomIndex
                val isUnlocked = unlockedRooms.contains(r.roomIndex)
                val isCleared = unlockedDoorForRooms.contains(r.roomIndex)
                val borderCol = when {
                    isCurrent -> Color(0xFF4DEEEA)
                    isCleared -> Color(0xFF34D399)
                    isUnlocked -> Color(0xFFFBBF24)
                    else -> Color(0x33FFFFFF)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCurrent) Color(0x334DEEEA) else Color(0x1AFFFFFF))
                        .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                        .clickable(enabled = isUnlocked) { onSwitchRoom(r.roomIndex) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    val icon = when {
                        isCleared -> "✅ "
                        isUnlocked -> "🚪 "
                        else -> "🔒 "
                    }
                    Text(
                        text = "$icon${r.roomName}",
                        fontSize = 12.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        color = if (isUnlocked) Color.White else Color(0xFF64748B),
                        maxLines = 1
                    )
                }
            }

            // 空间机位瞬移按钮组
            SpatialViewpoint.PRESETS.forEach { vp ->
                val active = currentViewpointIndex == vp.index
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) Color(0x3334D399) else Color(0x1AFFFFFF))
                        .border(1.dp, if (active) Color(0xFF34D399) else Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                        .clickable { onSwitchViewpoint(vp.index) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(vp.label, fontSize = 11.sp, color = Color.White, maxLines = 1)
                }
            }
        }

        if (audioBanner != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33FBBF24))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(audioBanner, fontSize = 12.sp, color = Color(0xFFFDE68A), maxLines = 1)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 左侧：空间搜证背包 + 工具栏 + 驻场嫌疑人
            Column(
                modifier = Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🕵️ 搜证背包 ($foundCountInRoom/${nonLockedClues.size})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.primaryColor
                    )
                    Text(
                        text = equippedTool?.let { "🧰 已装备：${it.title}" } ?: "🧰 未持有勘查工具",
                        fontSize = 11.sp,
                        color = if (equippedTool != null) Color(0xFF34D399) else Color(0xFF94A3B8)
                    )
                }

                roomLevel.clues.forEachIndexed { idx, clue ->
                    val isSelected = selectedClue?.clueId == clue.clueId
                    val isDiscovered = discoveredClueIds.contains(clue.clueId)
                    val isEncrypted = clue.requiredToolId != null && !decryptedClueIds.contains(clue.clueId)
                    val canClickInList = isDiscovered || clue.isLocked

                    val searchZoneHint = when (idx) {
                        0 -> "方位提示：低头搜寻地面或角落（勘查工具）…"
                        1 -> "方位提示：转头检查左右两侧墙壁（加密痕迹）…"
                        2 -> "方位提示：180° 转身检查身后后墙或桌面（关键物证）…"
                        else -> "集齐并破译 3 处线索后可拨轮解锁"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    isSelected -> palette.primaryColor.copy(alpha = 0.28f)
                                    isDiscovered && !isEncrypted -> Color(0x2834D399)
                                    isDiscovered && isEncrypted -> Color(0x28F59E0B)
                                    else -> Color(0x16FFFFFF)
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    isSelected -> palette.primaryColor
                                    isDiscovered && !isEncrypted -> Color(0x8834D399)
                                    clue.isLocked || isEncrypted -> Color(0x88FBBF24)
                                    else -> Color(0x22FFFFFF)
                                },
                                RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = canClickInList) { onSelectClue(clue) }
                            .padding(9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    clue.isLocked && !isCurrentDoorUnlocked -> "🔒 机关锁：${clue.title}"
                                    clue.isLocked && isCurrentDoorUnlocked -> "🔓 已开启：${clue.title}"
                                    !isDiscovered -> "❓ 未发现线索 #${idx + 1}（去 3D 房间搜寻）"
                                    clue.isTool -> "🧰 勘查工具：${clue.title}"
                                    isEncrypted -> "⚠️ 待工具显影：${clue.title}"
                                    else -> "✅ 已破译：${clue.title}"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (canClickInList) Color.White else Color(0xFF94A3B8)
                            )
                            Text(
                                text = when {
                                    clue.isLocked -> clue.shortHint
                                    !isDiscovered -> searchZoneHint
                                    isEncrypted -> clue.encryptedHint
                                    else -> clue.shortHint
                                },
                                fontSize = 11.sp,
                                color = when {
                                    clue.isLocked || isEncrypted -> Color(0xFFFBBF24)
                                    isDiscovered -> Color(0xFF6EE7B7)
                                    else -> Color(0xFF64748B)
                                }
                            )
                        }
                    }
                }

                Text(
                    text = "本房间驻场 AI 嫌疑人（出示物证可击破防线）",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.accentColor,
                    modifier = Modifier.padding(top = 4.dp)
                )
                roomLevel.suspects.forEach { suspect ->
                    val isSelected = selectedSuspect?.suspectId == suspect.suspectId
                    val isConfronted = confrontedSuspectIds.contains(suspect.suspectId)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) palette.accentColor.copy(alpha = 0.25f) else Color(0x22FFFFFF))
                            .border(
                                1.dp,
                                if (isSelected) palette.accentColor else Color(0x22FFFFFF),
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { onSelectSuspect(suspect) }
                            .padding(9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "🗣️ ${suspect.name}" + (if (isConfronted) "（✅ 已突破口供）" else "（点击审讯）"),
                            fontSize = 13.sp,
                            color = Color.White
                        )
                        Text("右侧立牌", fontSize = 11.sp, color = Color(0xFF94A3B8))
                    }
                }
            }

            // 右侧：线索显影 / 拟物拨轮密码锁 / 终局真凶裁决 / 嫌疑人审讯博弈
            Column(
                modifier = Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x99111827))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (selectedClue != null) {
                    val tex = result.visualAssets.clueTextures[selectedClue.clueId]
                    val isEncrypted = selectedClue.requiredToolId != null && !decryptedClueIds.contains(selectedClue.clueId)
                    val hasRequiredTool = selectedClue.requiredToolId != null && discoveredClueIds.contains(selectedClue.requiredToolId)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(selectedClue.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.primaryColor)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isEncrypted) Color(0x33F59E0B) else Color(0x3334D399))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isEncrypted) selectedClue.encryptedHint else selectedClue.shortHint,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEncrypted) Color(0xFFFDE68A) else Color(0xFF6EE7B7)
                            )
                        }
                    }

                    if (tex != null) {
                        Image(
                            bitmap = tex,
                            contentDescription = selectedClue.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(115.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, palette.primaryColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        )
                    }

                    Text(
                        text = if (isEncrypted) selectedClue.encryptedContent else selectedClue.content,
                        fontSize = 13.sp,
                        color = Color.White,
                        lineHeight = 19.sp
                    )

                    if (isEncrypted) {
                        if (hasRequiredTool) {
                            val reqTool = script.clues.firstOrNull { it.clueId == selectedClue.requiredToolId }
                            SpatialActionButton(
                                text = "🔦 使用已装备工具【${reqTool?.title ?: "勘查工具"}】显影破译",
                                accent = Color(0xFFFBBF24),
                                filled = true,
                                onClick = { onDecryptClue(selectedClue) }
                            )
                        } else {
                            Text(
                                text = "⚠️ 你尚未找到本房间的勘查工具，请先在 3D 房间内搜寻第 1 处工具线索！",
                                fontSize = 12.sp,
                                color = Color(0xFFFBBF24)
                            )
                        }
                    }

                    // 拟物 3 位拨轮密码锁（带搜证完备度门禁校验）
                    if (selectedClue.isLocked && !isCurrentDoorUnlocked) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xAA0B1120))
                                .border(1.dp, Color(0x55FBBF24), RoundedCornerShape(14.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "⚙️ 拟物三位机械拨轮锁（无需键盘，点击 ▲ / ▼ 调码）",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFBBF24)
                            )

                            // 本房已搜获线索推理汇总对照
                            nonLockedClues.forEachIndexed { idx, c ->
                                val found = discoveredClueIds.contains(c.clueId)
                                val dec = c.requiredToolId == null || decryptedClueIds.contains(c.clueId)
                                Text(
                                    text = "• 线索 ${idx + 1}：" + if (found && dec) "${c.title} → ${c.shortHint}" else "❓ 尚未搜获或未用工具显影",
                                    fontSize = 12.sp,
                                    color = if (found && dec) Color(0xFF6EE7B7) else Color(0xFF94A3B8)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                (0..2).forEach { slot ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        SpatialActionButton("▲", Color(0xFF4DEEEA), onClick = { onDialChange(slot, 1) })
                                        Box(
                                            modifier = Modifier
                                                .size(width = 64.dp, height = 48.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color(0xFF050811))
                                                .border(1.5.dp, Color(0xFFFBBF24), RoundedCornerShape(10.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = dialDigits[slot].toString(),
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                        SpatialActionButton("▼", Color(0xFF4DEEEA), onClick = { onDialChange(slot, -1) })
                                    }
                                }
                            }

                            if (!allCluesReadyForLock) {
                                Text(
                                    text = "⚠️ 证据链不完整：请先在 3D 房间搜齐全部 3 处线索并用工具破译加密痕迹后，才能启动机关解锁！",
                                    fontSize = 12.sp,
                                    color = Color(0xFFF87171)
                                )
                            } else {
                                SpatialActionButton(
                                    text = "🔓 验证拨轮密码 [${dialDigits.joinToString("")}] 开启机关",
                                    accent = Color(0xFFFBBF24),
                                    filled = true,
                                    onClick = { onUnlockAttempt(selectedClue.unlockCode ?: "") }
                                )
                            }

                            if (passwordFeedback.isNotBlank()) {
                                Text(passwordFeedback, fontSize = 12.sp, color = Color(0xFFFDE68A))
                            }
                        }
                    } else if (selectedClue.isLocked && isCurrentDoorUnlocked) {
                        if (currentRoomIndex + 1 < script.rooms.size) {
                            val nextRoom = script.rooms[currentRoomIndex + 1]
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x3334D399))
                                    .padding(12.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "✅ 本房间机关锁已破译！正面墙上的 3D 密室大门已绕轴推开。",
                                        fontSize = 13.sp,
                                        color = Color(0xFFA7F3D0)
                                    )
                                    SpatialActionButton(
                                        text = "🚪 立即穿越进入「${nextRoom.roomName}」",
                                        accent = Color(0xFF34D399),
                                        filled = true,
                                        onClick = onEnterNextRoom
                                    )
                                }
                            }
                        } else {
                            // 第 3 间终极核心室开箱后：终局真凶与动机裁决闭环
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xAA0B1120))
                                    .border(1.5.dp, Color(0xFF34D399), RoundedCornerShape(14.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "⚖️ 终局真相裁决：${script.culpritQuestion}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4DEEEA)
                                )
                                script.culpritOptions.forEachIndexed { idx, opt ->
                                    val isChosen = selectedCulpritIndex == idx
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isChosen) Color(0x3334D399) else Color(0x1AFFFFFF))
                                            .border(1.dp, if (isChosen) Color(0xFF34D399) else Color(0x44FFFFFF), RoundedCornerShape(10.dp))
                                            .clickable { onSelectCulpritOption(idx) }
                                            .padding(10.dp)
                                    ) {
                                        Text(opt, fontSize = 13.sp, color = Color.White)
                                    }
                                }
                                if (culpritFeedback.isNotBlank()) {
                                    Text(
                                        text = culpritFeedback,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (caseFullySolved) Color(0xFF34D399) else Color(0xFFFBBF24)
                                    )
                                }
                                if (caseFullySolved) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0x3334D399))
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = "🏆 全案卷宗还原：\n${script.truthSummary}",
                                            fontSize = 13.sp,
                                            color = Color(0xFFA7F3D0),
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedSuspect != null) {
                    val portrait = result.visualAssets.suspectPortraits[selectedSuspect.suspectId]
                    val keyEvidence = roomLevel.clues.firstOrNull { it.clueId == selectedSuspect.keyEvidenceClueId }
                    val hasKeyEvidence = keyEvidence != null && discoveredClueIds.contains(keyEvidence.clueId)
                    val isConfronted = confrontedSuspectIds.contains(selectedSuspect.suspectId)

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (portrait != null) {
                            Image(
                                bitmap = portrait,
                                contentDescription = selectedSuspect.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                        Column {
                            Text("审讯台：${selectedSuspect.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = palette.accentColor)
                            Text("驻场位置：${roomLevel.roomName}", fontSize = 12.sp, color = Color(0xFFCBD5E1))
                        }
                    }

                    // 免打字快捷审讯胶囊按钮组 + 出示关键证物破防按钮
                    Text("⚡ 一键审讯提问（免打字）：", fontSize = 12.sp, color = Color(0xFF4DEEEA))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        selectedSuspect.quickQuestions.forEach { (qText, aText) ->
                            SpatialActionButton(
                                text = "💬 $qText",
                                accent = Color(0xFF38BDF8),
                                onClick = { onQuickQuestion(selectedSuspect, qText, aText) }
                            )
                        }

                        if (hasKeyEvidence && keyEvidence != null) {
                            SpatialActionButton(
                                text = if (isConfronted) "✅ 已出示【${keyEvidence.title}】突破口供（点击重听）"
                                else "📢 出示铁证【${keyEvidence.title}】拆穿谎言！",
                                accent = Color(0xFFFBBF24),
                                filled = true,
                                onClick = { onConfrontSuspect(selectedSuspect, keyEvidence.title) }
                            )
                        } else {
                            Text(
                                text = "🔒 提示：在 3D 房间内搜获第 3 处关键物证后，可向其出示铁证击破防线！",
                                fontSize = 11.sp,
                                color = Color(0xFFFBBF24)
                            )
                        }
                    }

                    val msgs = suspectChats[selectedSuspect.suspectId].orEmpty()
                    msgs.takeLast(4).forEach { (role, text) ->
                        val isUser = role == "user"
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isUser) palette.primaryColor.copy(alpha = 0.2f) else Color(0x22FFFFFF))
                                .padding(9.dp)
                        ) {
                            Text(
                                text = (if (isUser) "你：" else "${selectedSuspect.name}：") + text,
                                fontSize = 12.sp,
                                color = Color.White,
                                lineHeight = 18.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            SpatialInputField("或自由输入审讯问题…", chatInput, onChatInputChange)
                        }
                        SpatialActionButton(
                            text = if (isAskingSuspect) "思考中…" else "发送",
                            accent = palette.accentColor,
                            filled = true,
                            onClick = { onSendChat(selectedSuspect) }
                        )
                    }
                } else {
                    // 默认空状态：360° 空间搜证与推理流程引导
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "🕵️ 360° 空间搜证与二次推理指南",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4DEEEA)
                        )
                        Text(
                            text = "本房间共有 3 处关键证物分散在 360° 闭合空间（包含你身后的第 4 面后墙与专属家具）：\n" +
                                "• 第 1 步【找工具】：在地面或角落找到本房间的勘查工具（自动装备）并获取首个参量；\n" +
                                "• 第 2 步【破加密】：找到墙面模糊痕迹，使用勘查工具显影出第二位参量；\n" +
                                "• 第 3 步【破防嫌疑人】：找到第 3 处关键物证，向驻场嫌疑人「📢 出示铁证」逼其交代密码换算与排列公式！\n" +
                                "• 小技巧：点击上方机位按钮或地面发光圆环，可瞬移走近书桌、左墙或后墙近距离搜证。",
                            fontSize = 13.sp,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 20.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SpatialActionButton(
                                text = "👁️ 收起面板 · 开始 360° 空间搜证",
                                accent = Color(0xFF4DEEEA),
                                filled = true,
                                onClick = onToggleCollapse
                            )
                            roomLevel.suspects.firstOrNull()?.let { sup ->
                                SpatialActionButton(
                                    text = "🗣️ 审讯 ${sup.name}",
                                    accent = palette.accentColor,
                                    onClick = { onSelectSuspect(sup) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpatialActionButton(
    text: String,
    accent: Color,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (filled) accent.copy(alpha = 0.28f) else Color(0x331E293B))
            .border(1.2.dp, accent, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1
        )
    }
}

@Composable
private fun SpatialInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE2E8F0))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
            cursorBrush = SolidColor(Color(0xFF4DEEEA)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0x88000000))
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}
