package com.example.picospacemystery.agents

import com.example.picospacemystery.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OrchestrationResult(
    val script: CaseScript,
    val visualAssets: GeneratedVisualAssets,
    val anchors: Map<String, PlacedEntityAnchor>
)

/**
 * 多 Agent 协同编排引擎（多房间二次推理链 + 工具破译 + 审讯破防 + 真凶指认版）：
 * 自动编排 3 个递进密室房间（前厅 -> 暗室 -> 核心真相室），每个房间均需“工具显影 + 证物审讯换算”方能推导密码。
 */
class MultiAgentOrchestrator(
    private val artAgent: ArtTextureAgent = ArtTextureAgent()
) {

    suspend fun runPipeline(
        gameConfig: CustomGameConfig,
        room: RoomLayoutData,
        config: LlmConfig,
        useRemoteImageApi: Boolean,
        onProgress: (List<AgentStepLog>) -> Unit
    ): OrchestrationResult = coroutineScope {
        val worldview = gameConfig.worldview.ifBlank { "民国雨夜古宅" }
        val roleNote = if (gameConfig.customRoles.isBlank()) "AI 自动随机角色" else "自定义角色：${gameConfig.customRoles.take(20)}"
        val logs = mutableListOf(
            AgentStepLog(AgentRole.DIRECTOR, AgentRunState.RUNNING, "正在编排「$worldview · ${gameConfig.plotStyle}」三层二次推理密室与证物链（$roleNote）…"),
            AgentStepLog(AgentRole.ART_TEXTURE, AgentRunState.IDLE, "等待 3 个房间的道具与材质清单…"),
            AgentStepLog(AgentRole.SPATIAL_LAYOUT, AgentRunState.IDLE, "已就绪 ${room.surfaces.size} 个房间基准平面"),
            AgentStepLog(AgentRole.SUSPECT_ROLEPLAY, AgentRunState.IDLE, "等待各房间驻场角色与审讯台词注入…")
        )
        onProgress(logs.toList())

        val script = generateCaseScript(gameConfig, room, config)
        val aiBadge = if (script.isLiveAiGenerated) "✨ AI 实时原创剧本 (${config.chatModel})" else "📚 内置精编剧本"
        logs[0] = AgentStepLog(
            AgentRole.DIRECTOR,
            AgentRunState.DONE,
            "[$aiBadge] 已生成《${script.caseTitle}》：3 间递进密室、12 条推理线索与动态密码"
        )
        logs[1] = AgentStepLog(AgentRole.ART_TEXTURE, AgentRunState.RUNNING, "正在为 3 个密室房间绘制墙地材质、3D 线索立牌与角色立绘…")
        logs[2] = AgentStepLog(AgentRole.SPATIAL_LAYOUT, AgentRunState.RUNNING, "正在计算 3 个房间的空间锚点、3D 线索浮标与密室大门…")
        onProgress(logs.toList())

        val artDeferred = async { artAgent.generateAssets(script, config, useRemoteImageApi) }
        val layoutDeferred = async { computeSpatialAnchors(script, room) }

        val visualAssets = artDeferred.await()
        val anchors = layoutDeferred.await()

        logs[1] = AgentStepLog(AgentRole.ART_TEXTURE, AgentRunState.DONE, visualAssets.textureSourceNote)
        logs[2] = AgentStepLog(AgentRole.SPATIAL_LAYOUT, AgentRunState.DONE, "已完成 ${script.rooms.size} 间密室的 3D 线索牌与大门锚定")
        logs[3] = AgentStepLog(AgentRole.SUSPECT_ROLEPLAY, AgentRunState.RUNNING, "正在将嫌疑人及审讯证物链分配至各密室房间…")
        onProgress(logs.toList())

        delay(300)
        logs[3] = AgentStepLog(
            AgentRole.SUSPECT_ROLEPLAY,
            AgentRunState.DONE,
            "${script.suspects.joinToString("、") { it.name }} 已在各自密室就位"
        )
        onProgress(logs.toList())

        OrchestrationResult(script, visualAssets, anchors)
    }

    private fun computeSpatialAnchors(
        script: CaseScript,
        room: RoomLayoutData
    ): Map<String, PlacedEntityAnchor> {
        val surfaceMap = room.surfaces.associateBy { it.id }
        val result = mutableMapOf<String, PlacedEntityAnchor>()

        script.clues.forEach { clue ->
            val s = surfaceMap[clue.surfaceId] ?: room.surfaces.first()
            val worldPos = Vector3Data(
                s.center.x + clue.offsetFromCenter.x,
                s.center.y + clue.offsetFromCenter.y,
                s.center.z + clue.offsetFromCenter.z
            )
            result[clue.clueId] = PlacedEntityAnchor(clue.clueId, worldPos, s.id)
        }

        script.suspects.forEachIndexed { idx, suspect ->
            val s = surfaceMap[suspect.surfaceId] ?: room.surfaces.last()
            val xOff = if (idx % 2 == 0) -1.5f else 1.5f
            val worldPos = Vector3Data(xOff, 1.28f, -1.5f)
            result[suspect.suspectId] = PlacedEntityAnchor(suspect.suspectId, worldPos, s.id)
        }
        return result
    }

    private suspend fun generateCaseScript(
        gameConfig: CustomGameConfig,
        room: RoomLayoutData,
        config: LlmConfig
    ): CaseScript = withContext(Dispatchers.IO) {
        if (config.apiKey.isNotBlank()) {
            val liveScript = runCatching {
                callLlmForFullMultiRoomScript(gameConfig, config)
            }.getOrNull()
            if (liveScript != null) {
                return@withContext liveScript
            }
        }
        delay(350)
        buildMultiRoomScript(gameConfig)
    }

    /**
     * 100% 真实调用大模型 (ecnu-plus / ecnu-max) 现场生成全新三层连环密室剧本、随机密码与每件道具的 AI 绘画 Prompt
     */
    private fun callLlmForFullMultiRoomScript(
        gameConfig: CustomGameConfig,
        config: LlmConfig
    ): CaseScript? {
        val worldview = gameConfig.worldview.ifBlank { "民国雨夜古宅" }
        val rnd = java.util.Random()
        val codes = List(3) { "${rnd.nextInt(8) + 1}${rnd.nextInt(9)}${rnd.nextInt(8) + 1}" }
        val roleReq = if (gameConfig.customRoles.isNotBlank()) {
            "3间房的驻场嫌疑人请依次使用这些角色名：${gameConfig.customRoles}"
        } else {
            "请为3间房分别原创1名符合「$worldview」世界观的驻场嫌疑人"
        }

        val systemPrompt = """
你是顶尖密室逃脱与剧本杀总导演。请根据玩家指定的世界观「$worldview」和风格「${gameConfig.plotStyle}」，原创一个3层递进连环密室推理剧本。
$roleReq。
三间密室的3位数字机关密码已经随机指定为：
- 第1间密码：${codes[0]}（百位=${codes[0][0]}，十位=${codes[0][1]}，个位=${codes[0][2]}）
- 第2间密码：${codes[1]}（百位=${codes[1][0]}，十位=${codes[1][1]}，个位=${codes[1][2]}）
- 第3间密码：${codes[2]}（百位=${codes[2][0]}，十位=${codes[2][1]}，个位=${codes[2][2]}）

每间密室必须严格遵循【三元二次推理链】（严禁在任何线索标题或shortHint里直接写出完整3位密码！）：
1) 勘查工具(tool)：玩家在地面捡到的勘查工具，其 tool_hint 隐含百位数字（如“量程刻度：【${codes[0][0]}】档”）；
2) 加密痕迹(enc)：墙面上的模糊痕迹，用工具显影后 enc_hint 揭示十位数字（如“显影波峰：第【${codes[0][1]}】轨”）；
3) 关键物证(ev)：桌上的核心证物，ev_hint 给出一个需要经过嫌疑人口供修正的原始数字（如个位数字+1或-1的原始值），玩家向嫌疑人出示该物证后，嫌疑人的 breakthrough_reply 交代出个位数字的修正规则（得到个位=${codes[0][2]}）以及“百位-十位-个位”的排列规则；
4) 机关锁(lock)：桌上的三位拨轮锁，不要在 lock_title 中包含密码数字。

请只输出合法的 JSON 对象（不要加 markdown 解释），字段结构严格如下：
{
  "case_title": "剧本标题",
  "opening_narration": "70字以内的悬疑开场白",
  "truth_summary": "120字以内的全案真相还原",
  "culprit_question": "终局真凶与动机指认问题",
  "culprit_options": ["A. 选项1", "B. 选项2", "C. 选项3"],
  "correct_culprit_index": 0,
  "rooms": [
    {
      "room_name": "第1间：房间名",
      "room_ambience": "25字环境氛围描写",
      "door_prompt": "25字本房间解谜目标",
      "wall_image_prompt": "English prompt for room wall texture painting, dark atmospheric game background",
      "tool_title": "勘查工具名称",
      "tool_hint": "首位参量提示（含百位数字，不写完整密码）",
      "tool_content": "工具描述与百位数字来源说明",
      "tool_image_prompt": "English prompt for the inspection tool prop icon, game art",
      "enc_title": "墙面加密痕迹名称",
      "enc_hint": "显影后中位参量提示（含十位数字）",
      "enc_content": "工具显影后看到的详细描述",
      "enc_image_prompt": "English prompt for mysterious glowing wall rune or cipher painting",
      "ev_title": "关键物证名称",
      "ev_hint": "末位原始观测值提示",
      "ev_content": "关键物证描述，提示需拿去质问本房嫌疑人",
      "ev_image_prompt": "English prompt for secret document or evidence prop, game art",
      "lock_title": "机关锁名称",
      "lock_content": "机关锁描述与推理汇总提示",
      "suspect_name": "嫌疑人姓名",
      "suspect_greeting": "嫌疑人警惕的开场白",
      "q1": "快捷审讯问题1",
      "a1": "嫌疑人回答1",
      "q2": "快捷审讯问题2",
      "a2": "嫌疑人回答2",
      "confront_q": "出示关键物证时的质问语",
      "breakthrough_reply": "被证物击破防线后的口供，明确说明个位数字如何修正得到以及按什么顺序排列三位密码（不要直接写出完整三位数字）",
      "suspect_image_prompt": "English prompt for mystery character portrait, half body, dramatic lighting"
    }
  ]
}
        """.trimIndent()

        val payload = JSONObject().apply {
            put("model", config.chatModel)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", "请立即为世界观「$worldview」（风格：${gameConfig.plotStyle}）生成完整的3间密室JSON剧本。"))
            })
        }

        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 28000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) return null

        val respStr = conn.inputStream.bufferedReader().readText()
        val rawContent = JSONObject(respStr)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .removePrefix("```json")
            .removePrefix("```")
            .substringBeforeLast("```")
            .trim()

        val root = JSONObject(rawContent)
        val roomsArr = root.getJSONArray("rooms")
        if (roomsArr.length() < 3) return null

        val levels = (0 until 3).map { idx ->
            val r = roomsArr.getJSONObject(idx)
            val code = codes[idx]
            val rPrefix = "r${idx + 1}"
            val toolId = "${rPrefix}_c1"
            val encId = "${rPrefix}_c2"
            val evId = "${rPrefix}_c3"
            val lockId = "${rPrefix}_c4"

            val clues = listOf(
                ClueItem(
                    clueId = toolId,
                    title = r.optString("tool_title", "勘查工具 #${idx + 1}"),
                    surfaceId = "floor-1",
                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                    visualType = "footprint",
                    shortHint = r.optString("tool_hint", "首位参量：【${code[0]}】"),
                    content = r.optString("tool_content", "工具上刻着首位数字参量 ${code[0]}。"),
                    isTool = true,
                    imagePrompt = r.optString("tool_image_prompt", "Mystery detective tool in $worldview, game prop art")
                ),
                ClueItem(
                    clueId = encId,
                    title = r.optString("enc_title", "加密痕迹 #${idx + 1}"),
                    surfaceId = "wall-1",
                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                    visualType = "rune",
                    shortHint = r.optString("enc_hint", "中位参量：【${code[1]}】"),
                    content = r.optString("enc_content", "显影后浮现出中位参量 ${code[1]}。"),
                    requiredToolId = toolId,
                    encryptedHint = "❓ 痕迹模糊（需先拾取本房勘查工具显影）",
                    encryptedContent = "表面覆盖着特殊干扰层，必须先在房间地面找到勘查工具并点击「使用工具显影破译」才能读取。",
                    imagePrompt = r.optString("enc_image_prompt", "Glowing mysterious wall cipher rune in $worldview, game texture")
                ),
                ClueItem(
                    clueId = evId,
                    title = r.optString("ev_title", "关键物证 #${idx + 1}"),
                    surfaceId = "table-1",
                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                    visualType = "note",
                    shortHint = r.optString("ev_hint", "关键物证（可向嫌疑人出示对质）"),
                    content = r.optString("ev_content", "记录了关键线索，拿去向本房间嫌疑人出示可击破其心理防线。"),
                    imagePrompt = r.optString("ev_image_prompt", "Secret evidence dossier in $worldview, tabletop prop")
                ),
                ClueItem(
                    clueId = lockId,
                    title = r.optString("lock_title", "三位拨轮机关锁"),
                    surfaceId = "table-1",
                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                    visualType = "box",
                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                    content = r.optString("lock_content", "集齐勘查工具、显影痕迹并向嫌疑人出示物证后，拨动三位转轮解锁。"),
                    isLocked = true,
                    unlockCode = code,
                    imagePrompt = "Ornate mechanical combination lock box in $worldview, 3D game asset"
                )
            )

            val suspect = SuspectItem(
                suspectId = "${rPrefix}_s1",
                name = r.optString("suspect_name", "神秘人 #${idx + 1}"),
                surfaceId = if (idx % 2 == 0) "wall-2" else "wall-3",
                personaPrompt = "你是《$worldview》${r.optString("room_name")}的驻场嫌疑人${r.optString("suspect_name")}。",
                initialGreeting = r.optString("suspect_greeting", "你为什么会闯进这里？我什么都不会说的。"),
                quickQuestions = listOf(
                    r.optString("q1", "你在这一层密室做什么？") to r.optString("a1", "我只是奉命看守这里，别问太多。"),
                    r.optString("q2", "墙上的痕迹怎么看不清？") to r.optString("a2", "没有地面上的专门勘查工具，肉眼是看不清墙面暗记的。"),
                    "怎么解开桌上的机关锁？" to "光有前两个数字没用，除非你找到了桌上的关键物证拿给我看！"
                ),
                keyEvidenceClueId = evId,
                evidenceConfrontQuestion = r.optString("confront_q", "出示关键物证，逼其交代末尾密码换算规则！"),
                breakthroughReply = r.optString("breakthrough_reply", "好吧我交代！最后一位数字其实是 ${code[2]}，按百位-十位-个位顺序拨动转轮就能开门！"),
                imagePrompt = r.optString("suspect_image_prompt", "Mystery suspect character portrait in $worldview, dramatic rim light")
            )

            EscapeRoomLevel(
                roomIndex = idx,
                roomName = r.optString("room_name", "第${idx + 1}间：密室"),
                roomAmbience = r.optString("room_ambience", "幽暗神秘的密室空间"),
                doorUnlockCode = code,
                doorPrompt = r.optString("door_prompt", "搜齐3处线索并破防嫌疑人，解开机关门锁"),
                clues = clues,
                suspects = listOf(suspect),
                wallImagePrompt = r.optString("wall_image_prompt", "Atmospheric interior wall texture of $worldview, game environment")
            )
        }

        val optsArr = root.optJSONArray("culprit_options")
        val opts = if (optsArr != null && optsArr.length() >= 3) {
            (0 until optsArr.length()).map { optsArr.getString(it) }
        } else {
            listOf(
                "A. ${levels[0].suspects.first().name} —— 试图掩盖实验失控真相",
                "B. ${levels[1].suspects.first().name} —— 借机窃取核心机密",
                "C. ${levels[2].suspects.first().name} —— 设下三层试炼寻找破局者"
            )
        }

        return CaseScript(
            worldview = worldview,
            caseTitle = root.optString("case_title", "《$worldview》三层诡影"),
            openingNarration = root.optString("opening_narration", "密室大门在身后轰然落锁，三道连环谜题正等待你揭开真相。"),
            rooms = levels,
            spatialAudioEvents = listOf(
                SpatialAudioEvent("r1_c1", "footsteps", "behind"),
                SpatialAudioEvent("r2_c2", "whisper", "left")
            ),
            truthSummary = root.optString("truth_summary", "你成功解开三层连环密室，揭开了隐藏在表象之下的终极真相。"),
            culpritQuestion = root.optString("culprit_question", "综合三间密室的物证，谁才是幕后主使及其真实目的？"),
            culpritOptions = opts,
            correctCulpritIndex = root.optInt("correct_culprit_index", 0).coerceIn(0, opts.lastIndex),
            isLiveAiGenerated = true
        )
    }

    suspend fun askSuspect(
        suspect: SuspectItem,
        script: CaseScript,
        history: List<Pair<String, String>>,
        question: String,
        config: LlmConfig
    ): String = withContext(Dispatchers.IO) {
        // 优先匹配预设快捷审讯问题或证物破防对质
        suspect.quickQuestions.firstOrNull { (q, _) -> q == question || question.contains(q.take(6)) }?.let { (_, reply) ->
            return@withContext reply
        }
        if (suspect.evidenceConfrontQuestion.isNotBlank() &&
            (question == suspect.evidenceConfrontQuestion || question.contains("出示") || question.contains("证物") || question.contains("拆穿"))
        ) {
            if (suspect.breakthroughReply.isNotBlank()) {
                return@withContext suspect.breakthroughReply
            }
        }

        if (config.apiKey.isNotBlank()) {
            val remote = runCatching {
                val messages = JSONArray().apply {
                    put(
                        JSONObject().put("role", "system")
                            .put(
                                "content",
                                "${suspect.personaPrompt}\n案件：《${script.caseTitle}》(${script.worldview}) - ${script.openingNarration}\n注意：严禁直接说出三位密码数字，只能引导玩家寻找房间内的勘查工具并拿关键物证向你对质。"
                            )
                    )
                    history.forEach { (r, t) -> put(JSONObject().put("role", r).put("content", t)) }
                    put(JSONObject().put("role", "user").put("content", question))
                }
                val payload = JSONObject().put("model", config.chatModel).put("messages", messages)
                val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                if (conn.responseCode in 200..299) {
                    val resp = conn.inputStream.bufferedReader().readText()
                    JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                } else null
            }.getOrNull()
            if (!remote.isNullOrBlank()) return@withContext remote
        }
        when {
            question.contains("工具") || question.contains("模糊") || question.contains("看不清") ->
                "（${suspect.name}瞥了你一眼）墙上的痕迹加了特殊遮蔽，你得先在【地面】找到本房间的勘查工具，再去扫描墙面。"
            question.contains("哪") || question.contains("线索") || question.contains("找") ->
                "（${suspect.name}压低声音）先去地面拾取【勘查工具】，再用它破译【左侧墙面】的加密痕迹，最后把【中央书桌】上的关键物证拿来给我看！"
            question.contains("门") || question.contains("密码") || question.contains("下一") ->
                "（${suspect.name}摇了摇头）光看表面数字可解不开门锁！先把书桌上的关键物证找到并出示给我，我才告诉你真正的换算口诀。"
            question.contains("真相") || question.contains("凶手") || question.contains("谁") ->
                "（${suspect.name}神色凝重）每个人都有隐瞒的动机，等你突破第 3 间核心密室的锁匣，自然要在我们三人之中指认真正的幕后黑手。"
            else ->
                "（${suspect.name}打量着你）你可以直接点击下方的快捷审讯问题盘问我，或者去 3D 房间搜齐物证再来拆穿我。"
        }
    }

    fun randomIdea(): CustomGameConfig {
        val pool = listOf(
            CustomGameConfig("赛博朋克地下诊所", "悬疑烧脑", "义体医生K、黑客零号、安保主管维克"),
            CustomGameConfig("深海三千米科考艇", "微恐惊悚", "声呐员陆沉、大副沈默、深潜学者"),
            CustomGameConfig("魔法学院禁书地窖", "奇幻推理", "幽灵教授、炼金学徒、画像骑士"),
            CustomGameConfig("民国雨夜古宅", "硬核推理", "守门人林姨、神秘学者周先生、密室主人陆教授")
        )
        return pool.random()
    }

    private fun resolveRoleNames(defaults: List<String>, customRoles: String): List<String> {
        if (customRoles.isBlank()) return defaults
        val parts = customRoles.split("、", ",", "，", ";", "；", "\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return defaults.mapIndexed { idx, def -> parts.getOrNull(idx)?.take(14) ?: def }
    }

    /**
     * 为 4 大主题生成包含 3 个递进房间、二次推理链（工具显影 + 证物破防换算）与终局真凶三选一的完整剧本
     */
    private fun buildMultiRoomScript(gameConfig: CustomGameConfig): CaseScript {
        val worldview = gameConfig.worldview.ifBlank { "民国雨夜古宅" }
        val w = worldview.lowercase()

        return when {
            // ==================== 主题 1：赛博朋克 ====================
            w.contains("赛博") || w.contains("cyber") || w.contains("未来") || w.contains("诊所") -> {
                val names = resolveRoleNames(listOf("义体医生 K", "黑客 零号", "安保主管 维克"), gameConfig.customRoles)
                CaseScript(
                    worldview = worldview,
                    caseTitle = "2077 神经密钥三层潜行",
                    openingNarration = "荒坂塔底层警报拉响，「原型神经密钥」离奇失窃。你必须在电磁封锁前依次突破「外围义体诊所」「暗网服务器机房」与「主控脑机金库」，揪出内鬼。",
                    rooms = listOf(
                        EscapeRoomLevel(
                            roomIndex = 0,
                            roomName = "第1间：外围义体诊所",
                            roomAmbience = "霓虹灯管忽明忽暗，手术台旁散落着改装工具与加密病历",
                            doorUnlockCode = "409",
                            doorPrompt = "拾取紫外线手电破译墙面频谱，并持加密病历向义体医生对质，推导 3 位门禁码",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r1_c1",
                                    title = "紫外线战术手电（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🔦 勘查工具 · 机身烙印【IV 型】",
                                    content = "你拾起了遗落在冷却液旁的【紫外线战术手电】（已激活本房破译能力）。手电金属机身上清晰烙印着罗马批次号：【IV 型（即首位参量 4）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r1_c2",
                                    title = "隐形霓虹频谱墙",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "📡 显影频谱 · 零点基准线",
                                    content = "用紫外线战术手电照射后，墙面浮现出脑机校准波形：中心波峰死死锁定在【零点基准线（即中位参量 0）】。",
                                    requiredToolId = "r1_c1",
                                    encryptedHint = "❓ 墙面涂层反光（需先拾取地面紫外线手电）",
                                    encryptedContent = "墙面涂有反光学迷彩涂料，肉眼只能看到杂乱的霓虹光斑。请先在房间地面找到【紫外线战术手电】再来扫描。"
                                ),
                                ClueItem(
                                    clueId = "r1_c3",
                                    title = "篡改的手术台病历",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "📋 关键物证 · 标称第 10 号通道",
                                    content = "手术台上的电子病历记载：『紧急转移走第 10 号满载神经通道』。页脚有一行红字批注：『标称通道号均为虚假掩护，真实尾号需向主刀医生核对偏移量』。"
                                ),
                                ClueItem(
                                    clueId = "r1_c4",
                                    title = "诊所电磁门禁终端",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "门禁终端提示：请按【手电批次号 - 墙面频谱基准线 - 医生修正后的真实通道尾号】依次拨入 3 位数字，开启通往暗网机房的防爆门。",
                                    isLocked = true,
                                    unlockCode = "409"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r1_s1",
                                    name = names[0],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是驻守第1间诊所的${names[0]}，外表冷漠但替潜入者打掩护。",
                                    initialGreeting = "别碰我的手术刀！除非你能找出我留在诊所里的三样东西，否则你连机房大门都摸不到。",
                                    quickQuestions = listOf(
                                        "案发时你在诊所做什么？" to "我在给一名重伤的夜游鬼做义体排异手术，突然机房那边传来过载警报，电磁门就自动锁死了。",
                                        "墙上的霓虹频谱怎么看不清？" to "那是防监控的隐形荧光涂层，我把【紫外线战术手电】丢在右前方地板上了，你捡起来照一下就能显影。",
                                        "门禁密码的三位数字怎么排？" to "前两位分别是手电的【罗马型号】和墙面频谱的【基准线】，至于最后一位……你先把桌上的病历拿来给我看。"
                                    ),
                                    keyEvidenceClueId = "r1_c3",
                                    evidenceConfrontQuestion = "📢 出示《篡改的手术台病历》追问真实通道尾号",
                                    breakthroughReply = "（${names[0]}压低声音）既然你拿到了病历，我就不装了！为防安保窃听，病历上写的『10 号通道』必须【减 1】才是真实尾号！记住口诀：【手电罗马号(IV) -> 频谱零点基准 -> 10减1】，去终端拨入这三位数字开门吧！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 1,
                            roomName = "第2间：暗网服务器机房",
                            roomAmbience = "深紫激光网格交错，刀片服务器机柜发出高频嗡鸣",
                            doorUnlockCode = "731",
                            doorPrompt = "用电磁频谱仪破译黑客涂鸦，并拿离线磁带质问黑客，推算金库气闸门密码",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r2_c1",
                                    title = "便携电磁频谱仪（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "📟 勘查工具 · 锁定第 7 扇区",
                                    content = "你从烧焦线缆旁拾起【便携电磁频谱仪】（已激活本房破译能力）。屏幕主读数固定在：【核心机柜第 7 扇区（首位参量）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r2_c2",
                                    title = "全息乱码涂鸦墙",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "⚡ 解密协议 · 三叉戟图层",
                                    content = "用电磁频谱仪滤除噪波后，涂鸦墙显现出三叉戟黑客徽记与一行铭文：【协议层级：三阶握手（即中位参量 3）】。",
                                    requiredToolId = "r2_c1",
                                    encryptedHint = "❓ 高频电磁乱码（需先拾取电磁频谱仪）",
                                    encryptedContent = "墙面全息投影受到强烈电磁干扰，画面剧烈抖动无法辨认。请先在机房地面找到【便携电磁频谱仪】进行滤波。"
                                ),
                                ClueItem(
                                    clueId = "r2_c3",
                                    title = "加密离线数据磁带",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "💾 关键物证 · 冗余校验值 [4]",
                                    content = "磁带外壳贴着封条：『金库气闸校验基数为 4，但实际输入尾数必须扣除三叉戟协议层数，详情询问当值黑客』。"
                                ),
                                ClueItem(
                                    clueId = "r2_c4",
                                    title = "金库气闸门控制台",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "气闸控制台要求按【机柜扇区号 - 握手协议阶数 - 磁带修正尾数】输入 3 位通行码，以解锁第 3 间「主控脑机金库」。",
                                    isLocked = true,
                                    unlockCode = "731"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r2_s1",
                                    name = names[1],
                                    surfaceId = "wall-3",
                                    personaPrompt = "你是潜伏在第2间机房的${names[1]}，技术高超但嘴硬。",
                                    initialGreeting = "嘘！小声点，主脑的巡逻ICE还在扫描这一层。没拿到磁带证据前别问我核心金库的门怎么开。",
                                    quickQuestions = listOf(
                                        "你为什么会潜伏在机房里？" to "有人花重金雇我切断外围监控日志，但我进来时发现核心金库已经被内部最高权限动过了！",
                                        "墙上的全息涂鸦藏着什么？" to "那是我留下的后门标记，你先去地上捡起我的【便携电磁频谱仪】，一对准墙面就能滤掉乱码看到协议阶数。",
                                        "谁有权限打开第三间脑机金库？" to "只有安保主管维克拥有金库的物理覆写权！你想进去查证，先把桌上的离线数据磁带找来。"
                                    ),
                                    keyEvidenceClueId = "r2_c3",
                                    evidenceConfrontQuestion = "📢 出示《加密离线数据磁带》追问尾数换算公式",
                                    breakthroughReply = "（${names[1]}打了个响指）眼力不错！磁带上的基数是 4，扣掉墙上三叉戟的 3 阶协议，【4 减 3】就是末尾校验码！把【频谱仪扇区号 - 协议阶数 - 修正尾数】连起来输入控制台，金库大门就开了！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 2,
                            roomName = "第3间：主控脑机金库",
                            roomAmbience = "赤金警戒灯光笼罩着中央量子保险箱，空气中弥漫着臭氧味",
                            doorUnlockCode = "999",
                            doorPrompt = "使用生物激光扫描仪显影主脑铭牌，并拿创始人芯片揭穿安保主管，开启终极量子箱",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r3_c1",
                                    title = "生物激光扫描仪（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🔬 勘查工具 · 9 级最高安保",
                                    content = "你在重型外骨骼脚印旁拾起【生物激光扫描仪】（已激活本房破译能力）。仪器权限槽显示：【最高安保代号：9 级（首位参量）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r3_c2",
                                    title = "量子主脑核心铭牌",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🧠 激光显影 · 九重神经矩阵",
                                    content = "经生物激光扫描仪激发，主脑铭牌底层浮现出立体字样：【九重突触矩阵同步率（即中位参量 9）】。",
                                    requiredToolId = "r3_c1",
                                    encryptedHint = "❓ 纳米防伪暗纹（需先拾取生物激光扫描仪）",
                                    encryptedContent = "主脑铭牌表面经过纳米级防伪蚀刻，普通光线下完全平滑。请先在金库地面拾取【生物激光扫描仪】读取暗纹。"
                                ),
                                ClueItem(
                                    clueId = "r3_c3",
                                    title = "安保主管的私密芯片",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "💽 关键物证 · 3×3 终极覆写指令",
                                    content = "芯片内记录着一笔暗网交易：『安保主管维克监守自盗，利用 3 组 3 阶矩阵乘积指令锁死了量子保险箱，企图嫁祸给诊所医生与黑客』。"
                                ),
                                ClueItem(
                                    clueId = "r3_c4",
                                    title = "量子核心保险箱",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "终极量子保险箱：请综合【安保等级 - 神经矩阵重数 - 芯片矩阵乘积尾数】拨入 3 位终极覆写码，取出失窃的神经密钥并指认幕后黑手！",
                                    isLocked = true,
                                    unlockCode = "999"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r3_s1",
                                    name = names[2],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是守在最终金库的${names[2]}，表面秉公执法，实则是监守自盗的真凶。",
                                    initialGreeting = "站住！金库重地已被我接管。外面的医生和黑客串通盗走密钥，我正要把量子保险箱彻底销毁！",
                                    quickQuestions = listOf(
                                        "为什么只有你一个人提前在金库里？" to "我是安保主管，警报一响我就走专用通道进来了，绝对不是为了私吞神经密钥！",
                                        "主脑铭牌上的防伪暗纹怎么读？" to "地上的【生物激光扫描仪】是我刚才巡检掉的，你拿去扫一下主脑铭牌就能看到矩阵重数。",
                                        "你为什么急着销毁量子保险箱？" to "我……我是为了防止机密外泄！你少血口喷人，除非你能在金库桌上找出直接指向我的铁证！"
                                    ),
                                    keyEvidenceClueId = "r3_c3",
                                    evidenceConfrontQuestion = "📢 出示《安保主管的私密芯片》当面揭穿监守自盗",
                                    breakthroughReply = "（${names[2]}脸色惨白，后退半步）该死……这枚私密交易芯片怎么落到你手里了？！好吧，是我用最高覆写码锁死了保险箱！最后一位就是芯片里的【3 乘以 3】，和前两位的安保等级、九重矩阵一样全是同一个最大数字！打开箱子做最后裁决吧！"
                                )
                            )
                        )
                    ),
                    spatialAudioEvents = listOf(
                        SpatialAudioEvent("r1_c1", "electric_buzz", "right"),
                        SpatialAudioEvent("r2_c2", "whisper", "left"),
                        SpatialAudioEvent("r3_c3", "footsteps", "behind")
                    ),
                    truthSummary = "案件真相水落石出：${names[2]}利用最高安保权限监守自盗，将原型神经密钥锁入量子保险箱并买通暗网买家，企图把罪名推给外围诊所的${names[0]}与机房的${names[1]}。你通过三层密室的紫外线显影、电磁滤波与芯片对质，成功夺回神经密钥并锁定真凶！",
                    culpritQuestion = "综合三间密室的物证与芯片交易记录，谁才是盗走神经密钥并嫁祸他人的真凶？",
                    culpritOptions = listOf(
                        "${names[0]} —— 借排异手术之名在诊所调包了密钥",
                        "${names[1]} —— 侵入暗网机房远程盗走了核心数据",
                        "${names[2]} —— 利用最高安保覆写权监守自盗并栽赃嫁祸"
                    ),
                    correctCulpritIndex = 2
                )
            }

            // ==================== 主题 2：深海科考艇 / 克苏鲁 ====================
            w.contains("深海") || w.contains("科考") || w.contains("潜艇") || w.contains("克苏鲁") -> {
                val names = resolveRoleNames(listOf("声呐员 陆沉", "大副 沈默", "深潜学者 顾教授"), gameConfig.customRoles)
                CaseScript(
                    worldview = worldview,
                    caseTitle = "深渊三千米：苍白海沟失联案",
                    openingNarration = "深海科考艇「苍穹号」在马里亚纳海沟 3000 米处突然动力停摆，三道水密舱门全部锁死。你必须依次排查「声呐监听舱」「高压减压舱」与「深渊反应堆舱」，查明潜艇被困的真相。",
                    rooms = listOf(
                        EscapeRoomLevel(
                            roomIndex = 0,
                            roomName = "第1间：声呐监听舱",
                            roomAmbience = "幽绿声呐波纹在舱壁回荡，舷窗外隐约掠过巨大的深海黑影",
                            doorUnlockCode = "362",
                            doorPrompt = "拾取频闪声呐探头显影海图，并持异常航海日志质问声呐员，推算水密门密码",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r1_c1",
                                    title = "频闪声呐探头（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "📡 勘查工具 · 水深 3 千米档位",
                                    content = "你在潮湿的舱底甲板拾起【频闪声呐探头】（已激活本房破译能力）。探头刻度盘死死卡在：【水深 3 千米（首位参量 3）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r1_c2",
                                    title = "海沟等深线荧光图",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🌊 声呐显影 · 第 6 号海沟裂隙",
                                    content = "用频闪声呐探头回波扫描后，海图暗层浮现出红色警示圈：【目标遗迹位于第 6 号海沟裂隙（中位参量 6）】。",
                                    requiredToolId = "r1_c1",
                                    encryptedHint = "❓ 海图水雾凝结（需先拾取频闪声呐探头）",
                                    encryptedContent = "防爆海图玻璃内侧结满深海冷凝水雾，无法看清暗层坐标。请先在甲板地面拾取【频闪声呐探头】进行回波显影。"
                                ),
                                ClueItem(
                                    clueId = "r1_c3",
                                    title = "撕毁的声呐值班日志",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "📓 关键物证 · 记录 5 组异常回波",
                                    content = "台面上的值班日志写着：『凌晨共捕获 5 组低频呼唤回波，但其中混入了机械伪造信号，真实尾数需找声呐员剔除伪造组数』。"
                                ),
                                ClueItem(
                                    clueId = "r1_c4",
                                    title = "第一道水密舱门转轮锁",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "水密门机械锁提示：请按【下潜千米数 - 海沟裂隙编号 - 剔除伪造后的真实回波数】输入 3 位数字，开启减压舱大门。",
                                    isLocked = true,
                                    unlockCode = "362"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r1_s1",
                                    name = names[0],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是驻守声呐舱的${names[0]}，神情紧张，知道回波中有3组是人为伪造的。",
                                    initialGreeting = "别敲舷窗！外面的东西会被声音吸引过来的……你想去后面的减压舱？先自己在舱里搜查看看吧。",
                                    quickQuestions = listOf(
                                        "潜艇为什么会突然停在三千米海沟？" to "半小时前主引擎被人为切到了静默模式，紧接着声呐就收到了来自海沟底部的诡异低频信号。",
                                        "墙上的海沟海图怎么一片模糊？" to "舱内温差太大结了雾，我把【频闪声呐探头】掉在右前方甲板上了，你捡起来扫一下海图就能显影。",
                                        "水密门转轮锁的三位密码是什么？" to "前两位是探头上的【下潜千米数】和海图上的【裂隙编号】，最后一位写在桌上的值班日志里，你拿来我再告诉你怎么算。"
                                    ),
                                    keyEvidenceClueId = "r1_c3",
                                    evidenceConfrontQuestion = "📢 出示《撕毁的声呐值班日志》追问伪造回波数量",
                                    breakthroughReply = "（${names[0]}擦了擦冷汗）你看出来了？日志上记的 5 组回波里，其实有【3 组】是有人用艇内发射器伪造的！【5 减去 3】才是真实的深海回波尾数！按【千米数 - 裂隙号 - 真实回波数】拨轮就能打开水密门！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 1,
                            roomName = "第2间：高压减压舱",
                            roomAmbience = "红色减压指示灯旋转，高压管道喷出丝丝白雾",
                            doorUnlockCode = "584",
                            doorPrompt = "使用热成像仪探测管壁铭文，并拿潜水服气阀记录质问大副，解锁反应堆舱",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r2_c1",
                                    title = "深海热成像仪（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🌡️ 勘查工具 · 5 兆帕恒定气压",
                                    content = "你在减压阀旁拾起【深海热成像仪】（已激活本房破译能力）。仪表侧面的气压基准刻度指向：【5 兆帕（首位参量 5）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r2_c2",
                                    title = "高压冷凝管壁刻痕",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🐙 热成像显影 · 八腕深渊图腾",
                                    content = "透过深海热成像仪观察，冰冷的管壁内侧显影出余温刻画的八腕海兽图腾与数字：【第 8 号供氧回路（中位参量 8）】。",
                                    requiredToolId = "r2_c1",
                                    encryptedHint = "❓ 高压白雾遮挡（需先拾取深海热成像仪）",
                                    encryptedContent = "高压蒸汽泄漏形成的浓密白雾完全遮挡了管壁。请先在减压舱地面找到【深海热成像仪】穿透白雾。"
                                ),
                                ClueItem(
                                    clueId = "r2_c3",
                                    title = "重型潜水服气阀清单",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "🤿 关键物证 · 备用气瓶总数 [6]",
                                    content = "清单显示：『减压舱原配 6 支高纯氧瓶，但刚才有人私自拆卸了数支带入反应堆舱，剩余在架气瓶数需向大副核实』。"
                                ),
                                ClueItem(
                                    clueId = "r2_c4",
                                    title = "反应堆隔舱气密锁",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "隔舱气密锁要求依次输入【兆帕气压值 - 供氧回路号 - 剩余在架气瓶数】，方可打开通往深渊反应堆舱的防辐射门。",
                                    isLocked = true,
                                    unlockCode = "584"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r2_s1",
                                    name = names[1],
                                    surfaceId = "wall-3",
                                    personaPrompt = "你是看守减压舱的${names[1]}，为人刚直但隐瞒了有人偷走两支氧气瓶的事。",
                                    initialGreeting = "减压舱气压还没平衡，谁也不准乱动气密锁！除非你能搞清楚舱里少了什么。",
                                    quickQuestions = listOf(
                                        "你在减压舱发现了什么异常？" to "十分钟前有人穿着外骨骼潜水服经过这里，把通往反应堆的隔舱门从里面反锁了。",
                                        "管道白雾后面刻着什么符号？" to "蒸汽太浓肉眼看不见，你去甲板上捡起【深海热成像仪】照一下左墙管壁，就能看清热源刻痕。",
                                        "谁带走了潜水装备进入反应堆？" to "你先去工作台上把《重型潜水服气阀清单》拿过来，我再告诉你还剩几支气瓶！"
                                    ),
                                    keyEvidenceClueId = "r2_c3",
                                    evidenceConfrontQuestion = "📢 出示《重型潜水服气阀清单》追问失踪气瓶数量",
                                    breakthroughReply = "（${names[1]}叹了口气）好吧，清单上原本有 6 支气瓶，但顾教授强行带走了【2 支】去反应堆舱做深渊样本培养！所以架子上只剩【6 减 2】支！按【兆帕数 - 回路号 - 剩余气瓶数】输入就能打开隔舱门！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 2,
                            roomName = "第3间：深渊反应堆舱",
                            roomAmbience = "幽蓝切伦科夫辐射光照亮中央培养罐，诡异的珊瑚菌丝缠绕着控制台",
                            doorUnlockCode = "917",
                            doorPrompt = "用盖革计数器破译辐射铭牌，并拿深渊实验手稿拆穿学者阴谋，重启潜艇反应堆",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r3_c1",
                                    title = "高频盖革计数器（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "☢️ 勘查工具 · 9 级辐射峰值",
                                    content = "你在反应堆底座拾起【高频盖革计数器】（已激活本房破译能力）。液晶屏闪烁着最高读数：【9 级切伦科夫辐射（首位参量 9）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r3_c2",
                                    title = "反应堆铅封警戒墙",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "⚛️ 辐射显影 · 1 号主冷却堆芯",
                                    content = "用高频盖革计数器贴近定位后，铅封墙内侧的同位素涂层显现出：【唯一存活回路：1 号主冷却堆芯（中位参量 1）】。",
                                    requiredToolId = "r3_c1",
                                    encryptedHint = "❓ 铅封同位素暗码（需先拾取高频盖革计数器）",
                                    encryptedContent = "厚重的防辐射铅板表面没有任何可见文字，内部却有同位素脉冲。请先在地面拾取【高频盖革计数器】探测。"
                                ),
                                ClueItem(
                                    clueId = "r3_c3",
                                    title = "深渊古菌共生手稿",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "📜 关键物证 · 献祭倒计时 [10-3]",
                                    content = "手稿狂热地写道：『是我切断了潜艇动力并伪造了 3 组声呐回波！只要维持反应堆停摆满 10 小时（已过去 3 小时），深渊古菌就能吞噬全艇完成进化！』"
                                ),
                                ClueItem(
                                    clueId = "r3_c4",
                                    title = "反应堆紧急重启终端",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "紧急重启终端：请按【辐射峰值等级 - 主堆芯编号 - 手稿剩余倒计时小时数】拨入 3 位重启码，恢复潜艇动力并指认罪魁祸首！",
                                    isLocked = true,
                                    unlockCode = "917"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r3_s1",
                                    name = names[2],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是驻留在反应堆舱的${names[2]}，痴迷深海古生物以致疯狂，是切断潜艇动力的真凶。",
                                    initialGreeting = "嘘……听见海沟深处的脉搏了吗？反应堆一旦重启，这株沉睡千万年的深渊古菌就会死去，我绝不会让你重启它！",
                                    quickQuestions = listOf(
                                        "你为什么独自锁在反应堆舱里？" to "只有这里的切伦科夫辐射能唤醒海沟古菌！外面的声呐员和大副根本不懂这项伟大的发现！",
                                        "铅封墙上的主堆芯编号怎么看？" to "地上的【高频盖革计数器】能感应同位素射线，你拿它扫一下左墙铅板就能读出堆芯编号。",
                                        "是不是你切断了潜艇的主动力？" to "你没有证据证明是我干的！除非你看到了我放在实验台上的那份共生手稿……"
                                    ),
                                    keyEvidenceClueId = "r3_c3",
                                    evidenceConfrontQuestion = "📢 出示《深渊古菌共生手稿》揭露停机献祭阴谋",
                                    breakthroughReply = "（${names[2]}眼神狂热地大笑）没错！是我伪造了 3 组声呐信号把潜艇骗进海沟，也是我关停了主引擎！10 小时孵化期已经过去了 3 小时，【10 减去 3】就是最后一位重启码！就算你输入密码上浮，深渊的印记也已经烙在我们每个人身上了！"
                                )
                            )
                        )
                    ),
                    spatialAudioEvents = listOf(
                        SpatialAudioEvent("r1_c1", "electric_buzz", "left"),
                        SpatialAudioEvent("r2_c2", "whisper", "behind"),
                        SpatialAudioEvent("r3_c3", "footsteps", "right")
                    ),
                    truthSummary = "深渊真相大白：${names[2]}为了利用反应堆辐射孵化海沟深处的远古共生菌，伪造了 3 组深海声呐回波诱使潜艇下潜至三千米海沟，并偷走减压舱氧气瓶、强行关停主引擎。你成功破译三舱线索重启反应堆，带领科考艇脱离深渊！",
                    culpritQuestion = "综合声呐伪造记录、失窃气瓶与共生手稿，谁才是令科考艇深陷三千米海沟的真凶？",
                    culpritOptions = listOf(
                        "${names[0]} —— 误判了深海回波导致潜艇撞入海沟裂隙",
                        "${names[1]} —— 私自泄压破坏了减压舱供氧回路",
                        "${names[2]} —— 伪造声呐信号并关停反应堆以孵化深渊古菌"
                    ),
                    correctCulpritIndex = 2
                )
            }

            // ==================== 主题 3：魔法学院禁书地窖 ====================
            w.contains("魔法") || w.contains("学院") || w.contains("禁书") || w.contains("地窖") || w.contains("奇幻") -> {
                val names = resolveRoleNames(listOf("幽灵教授 梅林", "炼金学徒 菲尔", "画像骑士 罗兰"), gameConfig.customRoles)
                CaseScript(
                    worldview = worldview,
                    caseTitle = "星辰学院：禁书地窖午夜失窃夜",
                    openingNarration = "午夜钟声敲响，魔法学院最高机密《星界契约卷轴》在禁书地窖被盗，三重魔力封印随之启动。你必须依次闯过「魔药熬制室」「星象炼金室」与「禁书封印核心」，找出窃取卷轴的黑手。",
                    rooms = listOf(
                        EscapeRoomLevel(
                            roomIndex = 0,
                            roomName = "第1间：魔药熬制室",
                            roomAmbience = "翠绿坩埚冒着星点气泡，羽毛笔在半空中自动疾书",
                            doorUnlockCode = "625",
                            doorPrompt = "拾取真视水晶镜显影黑板魔纹，并持魔药配方质问幽灵教授，解开魔力符文锁",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r1_c1",
                                    title = "真视水晶单眼镜（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🔮 勘查工具 · 六芒星镜框[6]",
                                    content = "你在洒落的荧光药粉旁拾起【真视水晶单眼镜】（已激活本房破译能力）。银质镜框雕刻着：【六芒星第一环（首位参量 6）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r1_c2",
                                    title = "隐形夜光魔纹墙",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "✨ 真视显影 · 双月交汇之相",
                                    content = "透过真视水晶单眼镜凝视，石墙上浮现出银色月相星轨：【双月同辉之刻（即中位参量 2）】。",
                                    requiredToolId = "r1_c1",
                                    encryptedHint = "❓ 幻术障眼法（需先拾取真视水晶单眼镜）",
                                    encryptedContent = "石墙被施了初级幻术障眼法，肉眼看去只是普通的青苔砖墙。请先在地面找到【真视水晶单眼镜】破除幻术。"
                                ),
                                ClueItem(
                                    clueId = "r1_c3",
                                    title = "滴落夜骐血的魔药手札",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "🧪 关键物证 · 记载 7 滴龙血基数",
                                    content = "魔药手札记载：『开启炼金室需调配隐形显影剂，基础配方为 7 滴火龙血，但午夜月相下必须扣减挥发滴数，去问幽灵教授』。"
                                ),
                                ClueItem(
                                    clueId = "r1_c4",
                                    title = "魔药室秘银符文匣",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "秘银符文匣提示：请按【镜框星芒数 - 墙面月相数 - 教授修正后的龙血滴数】拨入 3 位符文数字，解开第一重结界。",
                                    isLocked = true,
                                    unlockCode = "625"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r1_s1",
                                    name = names[0],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是飘浮在魔药室的${names[0]}，记性迷糊但对魔药挥发率了如指掌。",
                                    initialGreeting = "哎呀，又是哪个不遵守宵禁的小巫师？我的真视眼镜刚才受惊掉在地上啦，没它可看不清墙上的月相！",
                                    quickQuestions = listOf(
                                        "今晚是谁闯进了禁书地窖？" to "我正飘在天花板上打盹，突然看见一道披着斗篷的影子用了隐形药水溜进去了！",
                                        "墙上的幻术障眼法怎么破除？" to "去右前方地板上把我的【真视水晶单眼镜】捡起来，戴上它再看左墙，就能瞧见今晚是几轮月亮啦。",
                                        "秘银符文匣的第三位数字怎么算？" to "那得看桌上《魔药手札》里写的火龙血滴数！你先把那本手札拿给我瞧瞧。"
                                    ),
                                    keyEvidenceClueId = "r1_c3",
                                    evidenceConfrontQuestion = "📢 出示《滴落夜骐血的魔药手札》追问龙血挥发滴数",
                                    breakthroughReply = "（${names[0]}恍然大悟地拍了拍半透明的脑袋）对极了！手札上写着 7 滴火龙血，但在双月之夜会固定挥发掉【2 滴】，所以实际生效的是【7 减 2】滴！快按【六芒星 - 双月 - 修正滴数】去开符文匣吧！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 1,
                            roomName = "第2间：星象炼金室",
                            roomAmbience = "黄铜浑天仪缓缓自转，地面的炼金法阵闪烁着琥珀色微光",
                            doorUnlockCode = "843",
                            doorPrompt = "使用贤者之石碎片激活星图，并持残缺契约质问炼金学徒，开启核心封印门",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r2_c1",
                                    title = "贤者之石共鸣碎片（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "💎 勘查工具 · 八面体结晶[8]",
                                    content = "你在法阵边缘拾起【贤者之石共鸣碎片】（已激活本房破译能力）。赤红结晶呈现出完美的：【正八面体棱角（首位参量 8）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r2_c2",
                                    title = "古代炼金四元素壁画",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🔥 共鸣显影 · 四大古典元素",
                                    content = "当贤者之石碎片靠近时，壁画上的水、火、土、风四座圣杯同时亮起：【四元素平衡之数（中位参量 4）】。",
                                    requiredToolId = "r2_c1",
                                    encryptedHint = "❓ 元素魔力沉睡（需先拾取贤者之石共鸣碎片）",
                                    encryptedContent = "壁画上的元素符文处于魔力沉寂状态，黯淡无光。请先在炼金室地面找到【贤者之石共鸣碎片】引发共鸣。"
                                ),
                                ClueItem(
                                    clueId = "r2_c3",
                                    title = "烧焦的炼金转换契约",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "📜 关键物证 · 记载 9 枚星界银币",
                                    content = "契约残页写着：『以 9 枚星界银币作为等价交换祭品，按三等分献给三座守护雕像，每座雕像分得之数即为封印尾码』。"
                                ),
                                ClueItem(
                                    clueId = "r2_c4",
                                    title = "星象浑天仪机关锁",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "浑天仪机关要求依次拨入【结晶面数 - 元素圣杯数 - 每座雕像分得的银币数】，以打开通往禁书封印核心的暗门。",
                                    isLocked = true,
                                    unlockCode = "843"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r2_s1",
                                    name = names[1],
                                    surfaceId = "wall-3",
                                    personaPrompt = "你是留在炼金室的${names[1]}，贪吃又胆小，被人利用帮忙画了传送阵但不是主谋。",
                                    initialGreeting = "别把我交给戒律院！我只是进来偷烤松饼吃的，绝对没有偷《星界契约卷轴》！",
                                    quickQuestions = listOf(
                                        "你大半夜在炼金室鬼鬼祟祟做什么？" to "是有人答应给我一袋金币，让我帮他在地上描了一圈星界共鸣粉，我哪知道他要偷禁书啊！",
                                        "墙上的炼金壁画怎么没有反应？" to "你得先把掉在地上的【贤者之石共鸣碎片】捡起来，靠近左墙壁画就能点亮元素圣杯了。",
                                        "到底是谁雇你描的法阵？" to "那人一直躲在画框阴影里说话！你先把桌上的《烧焦的炼金转换契约》找来，我教你解开浑天仪！"
                                    ),
                                    keyEvidenceClueId = "r2_c3",
                                    evidenceConfrontQuestion = "📢 出示《烧焦的炼金转换契约》追问等价交换尾数",
                                    breakthroughReply = "（${names[1]}哭丧着脸）契约上写得明明白白：9 枚星界银币平均献给 3 座雕像，【9 除以 3】就是最后一位数字！把【八面结晶 - 四大元素 - 均分银币数】填进浑天仪就能进核心密室了，真凶就在里面！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 2,
                            roomName = "第3间：禁书封印核心",
                            roomAmbience = "紫色奥术锁链悬空交织，中央石台供奉着封印禁书的黄金圣匣",
                            doorUnlockCode = "759",
                            doorPrompt = "用奥术星盘显影穹顶星图，并持松节油画布残片揭穿画像骑士，开启黄金圣匣",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r3_c1",
                                    title = "奥术寻迹星盘（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🧭 勘查工具 · 北斗七星主针[7]",
                                    content = "你在祭坛台阶拾起【奥术寻迹星盘】（已激活本房破译能力）。星盘天枢指针指向：【北斗七星主垣（首位参量 7）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r3_c2",
                                    title = "穹顶古精灵封印碑",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🌟 星盘显影 · 五叶世界树徽记",
                                    content = "将奥术寻迹星盘对准石碑后，星光投射出古精灵王室印记：【五叶世界树圣徽（中位参量 5）】。",
                                    requiredToolId = "r3_c1",
                                    encryptedHint = "❓ 星光折射缺失（需先拾取奥术寻迹星盘）",
                                    encryptedContent = "古精灵封印碑需要特定角度的星光折射才能显现碑文。请先在地面拾取【奥术寻迹星盘】校准星光。"
                                ),
                                ClueItem(
                                    clueId = "r3_c3",
                                    title = "沾有松节油的画布残片",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "🖼️ 关键物证 · 骑士誓言第 [4+5] 条",
                                    content = "黄金圣匣旁留着一片油画布残角与松节油气味，背面写着：『只要毁掉星界契约，我就能挣脱画框束缚重获肉身！圣匣尾码为第 4 条与第 5 条骑士誓言之和』。"
                                ),
                                ClueItem(
                                    clueId = "r3_c4",
                                    title = "禁书黄金封印圣匣",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "黄金封印圣匣：请按【北斗星数 - 世界树叶数 - 骑士誓言叠加尾数】输入 3 位真言数字，夺回《星界契约卷轴》并指认盗贼！",
                                    isLocked = true,
                                    unlockCode = "759"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r3_s1",
                                    name = names[2],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是挂在封印核心墙上的${names[2]}，渴望解除画框灵魂绑定重获肉身，是策划盗窃卷轴的真凶。",
                                    initialGreeting = "以骑士之名起誓！我一直忠实地守卫在画框里，绝不可能走下画布去碰中央的黄金圣匣！",
                                    quickQuestions = listOf(
                                        "既然你一直守在这里，卷轴怎么会失窃？" to "肯定、肯定是外面的炼金学徒趁我闭眼冥想时溜进来锁进黄金圣匣里的！",
                                        "封印碑上的古精灵铭文怎么激活？" to "祭坛地上有一枚【奥术寻迹星盘】，你捡起来对准石碑折射星光，就能看清世界树有几片圣叶。",
                                        "为什么圣匣旁边会有油画松节油的味道？" to "胡说！画框里的颜料早就干了几百年了，你休想凭空污蔑一名荣耀骑士！"
                                    ),
                                    keyEvidenceClueId = "r3_c3",
                                    evidenceConfrontQuestion = "📢 出示《沾有松节油的画布残片》拆穿画中人跨界窃书",
                                    breakthroughReply = "（${names[2]}铠甲铿锵作响，声音颤抖）你竟然连画布残角都找到了……被困在画框里三百年，我太想重获自由了！没错，是我收买学徒画阵、趁夜跨出画框封存了卷轴！最后一位就是第 4 与第 5 条誓言之和【4 加 5】！打开圣匣吧！"
                                )
                            )
                        )
                    ),
                    spatialAudioEvents = listOf(
                        SpatialAudioEvent("r1_c1", "whisper", "left"),
                        SpatialAudioEvent("r2_c2", "electric_buzz", "right"),
                        SpatialAudioEvent("r3_c3", "footsteps", "behind")
                    ),
                    truthSummary = "魔法地窖谜案告破：被封印在油画中三百年的${names[2]}为了挣脱画框灵魂契约，用金币诱骗${names[1]}绘制共鸣阵，并趁${names[0]}打盹时跨出画框盗走《星界契约卷轴》。你凭借真视水晶、贤者之石与奥术星盘连破三重结界，成功护住学院秘宝！",
                    culpritQuestion = "综合夜骐血手札、转换契约与松节油画布残片，谁才是盗走《星界契约卷轴》的幕后真凶？",
                    culpritOptions = listOf(
                        "${names[0]} —— 监守自盗，利用幻术障眼法藏匿了卷轴",
                        "${names[1]} —— 为换取金币独立策划并窃取了禁书卷轴",
                        "${names[2]} —— 诱骗学徒布阵并跨出油画框盗走契约以解除灵魂束缚"
                    ),
                    correctCulpritIndex = 2
                )
            }

            // ==================== 主题 4：民国雨夜古宅（及通用悬疑兜底） ====================
            else -> {
                val names = resolveRoleNames(listOf("守门人 林姨", "神秘学者 周先生", "密室主人 陆教授"), gameConfig.customRoles)
                CaseScript(
                    worldview = worldview,
                    caseTitle = "《$worldview》雨夜三层连环密室",
                    openingNarration = "暴雨倾盆的午夜，古宅铜门在身后轰然反锁，传世国宝《青铜河图手稿》在密室中不翼而飞。你必须依次勘破「迷雾前厅」「机关书房」与「禁忌藏宝阁」，在三人之中找出自导自演的幕后操纵者。",
                    rooms = listOf(
                        EscapeRoomLevel(
                            roomIndex = 0,
                            roomName = "第1间：迷雾前厅",
                            roomAmbience = "昏黄壁灯映照着潮湿青砖，留声机缓缓空转发出沙沙声",
                            doorUnlockCode = "271",
                            doorPrompt = "拾取黄铜放大镜显影古画暗码，并持访客手札质问守门人，推算书房铜锁密码",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r1_c1",
                                    title = "黄铜西洋放大镜（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🔎 勘查工具 · 双狮戏珠柄[2]",
                                    content = "你在泥泞青砖旁拾起【黄铜西洋放大镜】（已激活本房破译能力）。手柄浮雕刻着：【双狮戏珠纹（即首位参量 2）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r1_c2",
                                    title = "前厅山水古画框",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🖼️ 微距显影 · 七星伴月落款",
                                    content = "用黄铜西洋放大镜凑近检视，画卷落款印章的微雕针孔排列成：【七星伴月之数（即中位参量 7）】。",
                                    requiredToolId = "r1_c1",
                                    encryptedHint = "❓ 微雕印章极小（需先拾取黄铜西洋放大镜）",
                                    encryptedContent = "古画右下角的朱砂印章内藏有发丝般细微的针孔刻痕，肉眼难以分辨。请先在地面拾取【黄铜西洋放大镜】观察。"
                                ),
                                ClueItem(
                                    clueId = "r1_c3",
                                    title = "浸湿的雨夜访客手札",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "📖 关键物证 · 记载今夜共 4 位来客",
                                    content = "访客手札记载：『今夜入宅登记共 4 人，但其中数人已提前退席离开，留在前厅的真实人数即为书房门锁尾数，需向守门人核实』。"
                                ),
                                ClueItem(
                                    clueId = "r1_c4",
                                    title = "前厅紫铜机关锁匣",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "紫铜机关锁提示：请按【放大镜瑞兽数 - 古画微雕星数 - 实际留厅访客数】依次拨入 3 位数字，开启通往机关书房的暗门。",
                                    isLocked = true,
                                    unlockCode = "271"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r1_s1",
                                    name = names[0],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是看守古宅前厅的${names[0]}，表面谨慎守口如瓶，知道有3位挂名访客其实并未留宿。",
                                    initialGreeting = "外头雨大，老爷吩咐今晚不见外客。你想进书房查手稿失窃的事，得先凭本事解开前厅的铜锁。",
                                    quickQuestions = listOf(
                                        "手稿失窃时前厅有什么动静？" to "十点整停了一回电，我摸黑去点煤油灯时，听见书房暗门砰地一声合上了。",
                                        "墙上那幅山水古画藏着什么玄机？" to "画框印章里有微雕暗码，我老眼昏花看不清，把【黄铜西洋放大镜】掉在右前方地砖上了，你捡起来照着看吧。",
                                        "紫铜机关锁的最后一位数字是什么？" to "那跟今晚实际留宿的访客人数有关，你先把桌上那本《浸湿的雨夜访客手札》拿来给我过目。"
                                    ),
                                    keyEvidenceClueId = "r1_c3",
                                    evidenceConfrontQuestion = "📢 出示《浸湿的雨夜访客手札》追问实际留厅人数",
                                    breakthroughReply = "（${names[0]}叹了口气）既然你翻到了手札，我便实话实说：登记簿上虽写了 4 人，但其中【3 人】只是挂名掩护并未入宅，实际进来的访客只有【4 减 3】人！按【双狮 - 七星 - 实际访客数】去开紫铜锁吧！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 1,
                            roomName = "第2间：机关书房",
                            roomAmbience = "幽蓝烛光摇曳，紫檀书架间弥漫着陈年墨香与火漆味",
                            doorUnlockCode = "518",
                            doorPrompt = "使用紫光验钞灯显影星象罗盘，并持火漆密信质问学者，开启禁忌藏宝阁",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r2_c1",
                                    title = "古董紫光萤石灯（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🏮 勘查工具 · 五行八卦底座[5]",
                                    content = "你在书房地毯暗格旁拾起【古董紫光萤石灯】（已激活本房破译能力）。青铜灯座铸有：【五行攒簇铭文（首位参量 5）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r2_c2",
                                    title = "墙面天罡星象罗盘",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🧭 萤石显影 · 北极紫微一星",
                                    content = "用古董紫光萤石灯映照罗盘，药水绘就的暗纹指向唯一不动的帝星：【紫微独曜（即中位参量 1）】。",
                                    requiredToolId = "r2_c1",
                                    encryptedHint = "❓ 罗盘药水隐匿（需先拾取古董紫光萤石灯）",
                                    encryptedContent = "星象罗盘盘面涂有特制白矾隐形药水，烛光下空无一字。请先在书房地面找到【古董紫光萤石灯】照射显影。"
                                ),
                                ClueItem(
                                    clueId = "r2_c3",
                                    title = "半封火漆密信",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "✉️ 关键物证 · 标明亥时 [10] 减去更数",
                                    content = "书桌上的火漆密信写着：『约定于第 10 刻度开启藏宝阁暗道，但需扣除今晚已敲过的更梆数，具体更数询问书房学者』。"
                                ),
                                ClueItem(
                                    clueId = "r2_c4",
                                    title = "书房博古架暗道锁",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "博古架暗道锁要求按【灯座五行数 - 罗盘紫微星数 - 扣除更数后的时辰刻度】输入 3 位密码，推开第三间藏宝阁。",
                                    isLocked = true,
                                    unlockCode = "518"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r2_s1",
                                    name = names[1],
                                    surfaceId = "wall-3",
                                    personaPrompt = "你是受邀来到第2间书房的${names[1]}，受人所托鉴定古籍，并非盗宝真凶。",
                                    initialGreeting = "嘘，别碰乱了书架上的孤本！我只是受邀来鉴宝的学者，可不想背上盗窃《青铜河图手稿》的黑锅。",
                                    quickQuestions = listOf(
                                        "你为什么会被锁在第二间书房里？" to "陆教授约我今晚在书房核对手稿拓片，谁知我刚进来暗门就落了锁，手稿则被带进了最里面的藏宝阁！",
                                        "墙上的星象罗盘怎么一个刻度都没有？" to "那是明代传下来的白矾隐形墨，你去地毯边捡起【古董紫光萤石灯】一照，紫微星位就显出来了。",
                                        "博古架暗道锁的末尾刻度怎么推算？" to "秘密全在书桌那封《半封火漆密信》上，你先去把密信取来，我再告知你刚才敲了几更天。"
                                    ),
                                    keyEvidenceClueId = "r2_c3",
                                    evidenceConfrontQuestion = "📢 出示《半封火漆密信》追问今夜已敲更数",
                                    breakthroughReply = "（${names[1]}推了推眼镜）密信上写的基数是 10，而刚才宅外恰好敲过了【二更天（2）】，所以真实尾数便是【10 减去 2】！将【五行 - 紫微一星 - 修正刻度】依次拨入博古架铜锁，就能找里面的陆教授当面对质了！"
                                )
                            )
                        ),
                        EscapeRoomLevel(
                            roomIndex = 2,
                            roomName = "第3间：禁忌藏宝阁",
                            roomAmbience = "绯红八卦法阵在地面微光流转，中央供奉着蟠龙青铜宝箱",
                            doorUnlockCode = "864",
                            doorPrompt = "使用青铜司南校准家徽浮雕，并持保单契约揭穿密室主人，开启蟠龙宝箱",
                            clues = listOf(
                                ClueItem(
                                    clueId = "r3_c1",
                                    title = "战国青铜司南（勘查工具）",
                                    surfaceId = "floor-1",
                                    offsetFromCenter = Vector3Data(0.6f, 0.04f, -0.8f),
                                    visualType = "footprint",
                                    shortHint = "🧭 勘查工具 · 八卦方位盘[8]",
                                    content = "你在祭坛石阶拾起【战国青铜司南】（已激活本房破译能力）。司南铜盘边缘镌刻着：【乾坤八卦总纲（首位参量 8）】。",
                                    isTool = true
                                ),
                                ClueItem(
                                    clueId = "r3_c2",
                                    title = "穹顶陆氏家徽浮雕",
                                    surfaceId = "wall-1",
                                    offsetFromCenter = Vector3Data(0.75f, 1.6f, -2.45f),
                                    visualType = "rune",
                                    shortHint = "🐉 司南磁感 · 六合蟠龙暗扣",
                                    content = "用战国青铜司南靠近家徽浮雕，磁勺受暗扣吸引指向核心铭文：【六合归一之枢（中位参量 6）】。",
                                    requiredToolId = "r3_c1",
                                    encryptedHint = "❓ 磁石机关隐蔽（需先拾取战国青铜司南）",
                                    encryptedContent = "家徽浮雕内部嵌有天然慈石暗扣，单凭肉眼无法判断机括走向。请先在地面拾取【战国青铜司南】进行磁感定位。"
                                ),
                                ClueItem(
                                    clueId = "r3_c3",
                                    title = "巨额古董失窃保单与忏悔录",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(0.32f, 0.82f, -1.2f),
                                    visualType = "note",
                                    shortHint = "📜 关键物证 · 四季分期赔付 [2×2]",
                                    content = "桌上的洋行保单与手稿写着：『陆氏家族债台高筑，唯有趁雨夜自导自演手稿失窃案，方可骗取洋行分 2 年、每年 2 期（共 2×2 期）的巨额保险金』。"
                                ),
                                ClueItem(
                                    clueId = "r3_c4",
                                    title = "蟠龙青铜真相宝箱",
                                    surfaceId = "table-1",
                                    offsetFromCenter = Vector3Data(-0.32f, 0.88f, -1.2f),
                                    visualType = "box",
                                    shortHint = "🔒 三位拨轮机关锁（需集齐线索推理）",
                                    content = "蟠龙青铜宝箱：请综合【司南八卦数 - 家徽六合数 - 保单总赔付期数】输入 3 位终极密码，取出《青铜河图手稿》并指认策划者！",
                                    isLocked = true,
                                    unlockCode = "864"
                                )
                            ),
                            suspects = listOf(
                                SuspectItem(
                                    suspectId = "r3_s1",
                                    name = names[2],
                                    surfaceId = "wall-2",
                                    personaPrompt = "你是古宅主人${names[2]}，表面痛心疾首，实则是为了巨额保险金自导自演手稿失窃案的真凶。",
                                    initialGreeting = "家门不幸啊！祖传的《青铜河图手稿》竟在今晚被人锁进了蟠龙箱，连我也打不开这最后一道祖训锁！",
                                    quickQuestions = listOf(
                                        "既然你是古宅主人，怎会打不开自家宝箱？" to "这蟠龙箱设的是先祖磁机关，我方才一时急火攻心，竟忘了最后一位祖训尾数……",
                                        "墙上的陆氏家徽浮雕该如何定位？" to "石阶上落着那尊【战国青铜司南】，你捡起来靠近家徽浮雕，磁勺自然会指出六合中位之数。",
                                        "手稿失窃对你来说真的只是损失吗？" to "你这话是什么意思？！手稿乃陆家命脉，除非你能拿出我借失窃牟利的铁证！"
                                    ),
                                    keyEvidenceClueId = "r3_c3",
                                    evidenceConfrontQuestion = "📢 出示《巨额古董失窃保单与忏悔录》揭穿自盗骗保",
                                    breakthroughReply = "（${names[2]}长叹一声，颓然跌坐在太师椅上）罢了……连洋行保单都被你搜出来了。陆家亏空太深，我只能趁周先生来访之夜自导自演这出失窃案，好拿保金赎回祖宅！最后一位正是【2 乘以 2】的四期保单之数！打开蟠龙箱吧，手稿就在里面！"
                                )
                            )
                        )
                    ),
                    spatialAudioEvents = listOf(
                        SpatialAudioEvent("r1_c1", "footsteps", "behind"),
                        SpatialAudioEvent("r2_c2", "whisper", "left"),
                        SpatialAudioEvent("r3_c3", "electric_buzz", "right")
                    ),
                    truthSummary = "雨夜古宅奇案水落石出：所谓《青铜河图手稿》失窃，实则是古宅主人${names[2]}因家族债务危机而自导自演的骗保苦肉计——他故意在${names[1]}来访之夜拉闸锁门，将手稿藏入第三间藏宝阁的蟠龙箱中。你通过西洋放大镜、紫光萤石灯与青铜司南连破三室机关，完美还原真相！",
                    culpritQuestion = "综合三间密室的访客手札、火漆密信与洋行保单，谁才是《青铜河图手稿》失窃案的幕后策划者？",
                    culpritOptions = listOf(
                        "${names[0]} —— 趁停电摸黑潜入书房盗走手稿变卖",
                        "${names[1]} —— 借鉴宝之名用拓片调包了河图真迹",
                        "${names[2]} —— 为偿还家族巨债而自导自演密室失窃骗取洋行保金"
                    ),
                    correctCulpritIndex = 2
                )
            }
        }
    }
}
