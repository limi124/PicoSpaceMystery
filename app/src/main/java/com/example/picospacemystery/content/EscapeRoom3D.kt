package com.example.picospacemystery.content

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import com.example.picospacemystery.agents.ArtTextureAgent
import com.example.picospacemystery.agents.OrchestrationResult
import com.example.picospacemystery.model.ThemePalette
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3

/**
 * 360° 全闭合 3D 空间搜证密室构建器：
 * 1) 四面环绕墙体（含身后 180° 后墙 backWallEntity）+ 3 根顶部横梁 + 书桌复古台灯 + USDZ 3D 宝箱点击交互；
 * 2) 三间房差异化 3D 专属家具组（Room 0 书架与后墙画框 / Room 1 药剂架与双联机柜 / Room 2 对称能量图腾巨柱与祭坛）；
 * 3) 360° 分散藏匿线索（含后墙）+ 默认隐藏防剧透、点击搜获后才浮现的全息悬浮立牌 + 物理浮起/推门反馈；
 * 4) 4 机位空间瞬移探索（地面 3D 瞬移光环 + setViewpointOffset 全场景平滑位移）。
 */
class EscapeRoom3DScene {
    var floorEntity: Entity? = null
    var frontWallEntity: Entity? = null
    var backWallEntity: Entity? = null
    var leftWallEntity: Entity? = null
    var rightWallEntity: Entity? = null
    var tableEntity: Entity? = null
    var doorEntity: Entity? = null

    val pillarEntities = mutableListOf<Entity>()
    val ceilingBeamEntities = mutableListOf<Entity>()
    val deskLampEntities = mutableListOf<Entity>()
    val teleportRingEntities = mutableListOf<Entity>()

    // 三间房差异化专属 3D 家具组
    val room0Props = mutableListOf<Entity>()
    val room1Props = mutableListOf<Entity>()
    val room2Props = mutableListOf<Entity>()

    val clueEntities = mutableMapOf<Int, Entity>()
    val clueSignEntities = mutableMapOf<Int, Entity>()
    var suspectPedestalEntity: Entity? = null
    var suspectEntity: Entity? = null
    var suspectSpeechBubbleEntity: Entity? = null
    var usdzBoxEntity: Entity? = null

    data class SuspectSpatialSpot(
        val label: String,
        val pedestalPos: Vector3,
        val suspectPos: Vector3,
        val bubblePos: Vector3,
        val rotation: EulerAngles,
        val directionAudioHint: String
    )

    // 360° 空间随机出现/巡逻点位：嫌疑人进入房间或巡逻时会随机出现在不同角落（右侧墙角、左侧书架/门廊旁、身后暗角、书桌右后侧、左后方柱旁）
    val suspectSpawnSpots: List<SuspectSpatialSpot> = listOf(
        SuspectSpatialSpot(
            label = "右侧暗角",
            pedestalPos = Vector3(1.68f, 0.41f, -1.55f),
            suspectPos = Vector3(1.68f, 1.30f, -1.55f),
            bubblePos = Vector3(1.56f, 1.88f, -1.48f),
            rotation = EulerAngles(0f, -32f, 0f),
            directionAudioHint = "right"
        ),
        SuspectSpatialSpot(
            label = "左侧廊柱旁",
            pedestalPos = Vector3(-1.68f, 0.41f, -1.35f),
            suspectPos = Vector3(-1.68f, 1.30f, -1.35f),
            bubblePos = Vector3(-1.54f, 1.88f, -1.28f),
            rotation = EulerAngles(0f, 36f, 0f),
            directionAudioHint = "left"
        ),
        SuspectSpatialSpot(
            label = "身后右后方",
            pedestalPos = Vector3(1.32f, 0.41f, 0.95f),
            suspectPos = Vector3(1.32f, 1.30f, 0.95f),
            bubblePos = Vector3(1.20f, 1.88f, 0.85f),
            rotation = EulerAngles(0f, -142f, 0f),
            directionAudioHint = "behind"
        ),
        SuspectSpatialSpot(
            label = "身后左后方",
            pedestalPos = Vector3(-1.28f, 0.41f, 0.92f),
            suspectPos = Vector3(-1.28f, 1.30f, 0.92f),
            bubblePos = Vector3(-1.16f, 1.88f, 0.82f),
            rotation = EulerAngles(0f, 142f, 0f),
            directionAudioHint = "behind"
        ),
        SuspectSpatialSpot(
            label = "中央书桌旁",
            pedestalPos = Vector3(0.96f, 0.41f, -1.25f),
            suspectPos = Vector3(0.96f, 1.30f, -1.25f),
            bubblePos = Vector3(0.92f, 1.88f, -1.18f),
            rotation = EulerAngles(0f, -18f, 0f),
            directionAudioHint = "center"
        )
    )

    var currentSuspectSpotIndex: Int = 0
        private set

    // Entity -> ("clue"|"suspect"|"door"|"teleport", index)
    val interactiveTargetMap = mutableMapOf<Entity, Pair<String, Int>>()
    private val entityModels = mutableMapOf<Entity, ModelComponent>()

    // 维护所有房间 3D 实体的基准Transform（用于 4 机位空间瞬移探索时计算 basePos + currentViewpointOffset）
    private data class BaseTransform(
        val position: Vector3,
        val rotation: EulerAngles,
        val scale: Vector3 = Vector3(1f, 1f, 1f)
    )

    private val basePositions = mutableMapOf<Entity, BaseTransform>()
    private var currentViewpointOffset: Vector3 = Vector3(0f, 0f, 0f)
    private val interactedClueIndices = mutableSetOf<Int>()

    // 3 间密室的 360° 空间隐藏线索坐标（每间房均包含身后后墙 z = +1.48f 的环视线索，迫使玩家 360° 转头搜寻！）
    private val roomClueTransforms: List<List<Pair<Vector3, EulerAngles>>> = listOf(
        // Room 0 布局：右前地面暗格、左墙古老壁画、身后后墙密卷(z=+1.48f)、书桌左侧机关锁匣
        listOf(
            Vector3(1.25f, 0.04f, -0.75f) to EulerAngles(0f, -25f, 0f),
            Vector3(-2.32f, 1.48f, -0.35f) to EulerAngles(0f, 90f, 0f),
            Vector3(0.45f, 1.42f, 1.48f) to EulerAngles(0f, 180f, 0f),
            Vector3(-0.36f, 0.89f, -1.25f) to EulerAngles(0f, 16f, 0f)
        ),
        // Room 1 布局：左前地面立柱旁、身后后墙高处铭文(z=+1.48f)、书桌右侧手札、书桌中央机关锁匣
        listOf(
            Vector3(-1.38f, 0.04f, -1.55f) to EulerAngles(0f, 35f, 0f),
            Vector3(-0.55f, 1.50f, 1.48f) to EulerAngles(0f, 180f, 0f),
            Vector3(0.42f, 0.80f, -1.18f) to EulerAngles(6f, 22f, 0f),
            Vector3(-0.32f, 0.89f, -1.25f) to EulerAngles(0f, -12f, 0f)
        ),
        // Room 2 布局：书桌前方祭坛地面、右墙浮雕铭文、身后后墙核心暗格(z=+1.48f)、书桌终极密码宝箱
        listOf(
            Vector3(0.05f, 0.04f, -0.65f) to EulerAngles(0f, 10f, 0f),
            Vector3(2.32f, 1.52f, -0.45f) to EulerAngles(0f, -90f, 0f),
            Vector3(0.0f, 1.46f, 1.48f) to EulerAngles(0f, 180f, 0f),
            Vector3(-0.36f, 0.89f, -1.25f) to EulerAngles(0f, 18f, 0f)
        )
    )

    suspend fun buildInitialWhiteboxRoom(content: SpatialViewContent, initialPalette: ThemePalette) {
        basePositions.clear()
        interactiveTargetMap.clear()
        entityModels.clear()
        interactedClueIndices.clear()
        currentViewpointOffset = Vector3(0f, 0f, 0f)

        val art = ArtTextureAgent()
        val floorBmp = art.synthesizeRoomSurfaceBitmap("floor", "第1间", initialPalette)
        val wallBmp = art.synthesizeRoomSurfaceBitmap("wall", "第1间", initialPalette)
        val tableBmp = art.synthesizeRoomSurfaceBitmap("table", "第1间", initialPalette)
        val doorBmp = art.synthesizeDoorBitmap("第1间", "第2间", "???", false, initialPalette)
        val signPlaceholderBmp = art.synthesizeClueSignboardBitmap("空间线索", "点击搜证", false, initialPalette)

        val lampGlowBmp = synthesizeSolidGlowBitmap(0xFFFFE082.toInt(), 0xFFF59E0B.toInt())
        val cyanGlowBmp = synthesizeSolidGlowBitmap(0xFF4DEEEA.toInt(), 0xFF0284C7.toInt())
        val purpleGlowBmp = synthesizeSolidGlowBitmap(0xFFD8B4FE.toInt(), 0xFF7E22CE.toInt())
        val emeraldGlowBmp = synthesizeSolidGlowBitmap(0xFF6EE7B7.toInt(), 0xFF059669.toInt())

        // ① 360° 全闭合空间架构：地板 + 前、后、左、右四面环绕墙体 + 4 根角落立柱 + 顶部 3 根横向天花板横梁
        floorEntity = createTexturedBox(
            size = Vector3(4.8f, 0.04f, 4.2f),
            position = Vector3(0f, 0.0f, -0.5f),
            bitmap = floorBmp
        ).also { content.addEntity(it) }

        frontWallEntity = createTexturedBox(
            size = Vector3(4.8f, 2.7f, 0.08f),
            position = Vector3(0f, 1.35f, -2.55f),
            bitmap = wallBmp
        ).also { content.addEntity(it) }

        backWallEntity = createTexturedBox(
            size = Vector3(4.8f, 2.7f, 0.08f),
            position = Vector3(0f, 1.35f, 1.60f),
            bitmap = wallBmp
        ).also { content.addEntity(it) }

        leftWallEntity = createTexturedBox(
            size = Vector3(0.08f, 2.7f, 4.2f),
            position = Vector3(-2.4f, 1.35f, -0.5f),
            bitmap = wallBmp
        ).also { content.addEntity(it) }

        rightWallEntity = createTexturedBox(
            size = Vector3(0.08f, 2.7f, 4.2f),
            position = Vector3(2.4f, 1.35f, -0.5f),
            bitmap = wallBmp
        ).also { content.addEntity(it) }

        // 四角建筑立柱
        pillarEntities.clear()
        listOf(
            Vector3(-2.22f, 1.35f, -2.38f),
            Vector3(2.22f, 1.35f, -2.38f),
            Vector3(-2.22f, 1.35f, 1.42f),
            Vector3(2.22f, 1.35f, 1.42f)
        ).forEach { pos ->
            val pillar = createTexturedBox(Vector3(0.24f, 2.7f, 0.24f), pos, tableBmp)
            pillarEntities.add(pillar)
            content.addEntity(pillar)
        }

        // 顶部 3 根横向天花板横梁（Ceiling Beams）
        ceilingBeamEntities.clear()
        listOf(-1.8f, -0.5f, 0.8f).forEach { zPos ->
            val beam = createTexturedBox(
                size = Vector3(4.8f, 0.14f, 0.22f),
                position = Vector3(0f, 2.65f, zPos),
                bitmap = tableBmp
            )
            ceilingBeamEntities.add(beam)
            content.addEntity(beam)
        }

        // 正面墙左侧：3D 密室过道大门 + 门框
        val doorFrame = createTexturedBox(
            size = Vector3(1.12f, 1.98f, 0.06f),
            position = Vector3(-1.05f, 1.02f, -2.48f),
            bitmap = tableBmp
        )
        content.addEntity(doorFrame)

        doorEntity = createInteractiveBox(
            size = Vector3(0.98f, 1.86f, 0.08f),
            position = Vector3(-1.05f, 1.0f, -2.45f),
            rotation = EulerAngles(0f, 0f, 0f),
            bitmap = doorBmp
        ).also {
            interactiveTargetMap[it] = "door" to 0
            content.addEntity(it)
        }

        // 中央书桌 + 4 根圆柱桌腿
        tableEntity = createTexturedBox(
            size = Vector3(1.38f, 0.07f, 0.78f),
            position = Vector3(0f, 0.74f, -1.28f),
            bitmap = tableBmp
        ).also { content.addEntity(it) }

        listOf(
            Vector3(-0.58f, 0.37f, -0.95f),
            Vector3(0.58f, 0.37f, -0.95f),
            Vector3(-0.58f, 0.37f, -1.55f),
            Vector3(0.58f, 0.37f, -1.55f)
        ).forEach { legPos ->
            val leg = createTexturedCylinder(
                radius = 0.035f,
                height = 0.74f,
                position = legPos,
                bitmap = tableBmp
            )
            content.addEntity(leg)
        }

        // 书桌右后角复古台灯（圆柱底座 + 灯柱 + 暖光球体灯罩）
        deskLampEntities.clear()
        val lampBase = createTexturedCylinder(0.09f, 0.04f, Vector3(0.52f, 0.79f, -1.48f), tableBmp)
        val lampStem = createTexturedCylinder(0.022f, 0.24f, Vector3(0.52f, 0.91f, -1.48f), tableBmp)
        val lampShade = createTexturedSphere(0.11f, Vector3(0.52f, 1.06f, -1.48f), lampGlowBmp)
        listOf(lampBase, lampStem, lampShade).forEach {
            deskLampEntities.add(it)
            content.addEntity(it)
        }

        // ② 三间房差异化 3D 专属家具组（通过 Entity.setEnabled 随房间切换）
        buildRoomSpecificProps(content, tableBmp, wallBmp, cyanGlowBmp, purpleGlowBmp, emeraldGlowBmp)

        // ③ 4 个分散隐藏在 360° 空间里的 3D 线索实体 + 悬浮在道具上方的全息立牌（默认 setEnabled(false) 防剧透！）
        val initialTransforms = roomClueTransforms[0]
        val c0 = createInteractiveBox(
            size = Vector3(0.46f, 0.04f, 0.46f),
            position = initialTransforms[0].first,
            rotation = initialTransforms[0].second,
            bitmap = floorBmp
        )
        val c1 = createInteractiveBox(
            size = Vector3(0.72f, 0.52f, 0.06f),
            position = initialTransforms[1].first,
            rotation = initialTransforms[1].second,
            bitmap = wallBmp
        )
        val c2 = createInteractiveBox(
            size = Vector3(0.42f, 0.34f, 0.06f),
            position = initialTransforms[2].first,
            rotation = initialTransforms[2].second,
            bitmap = tableBmp
        )
        val c3 = createInteractiveBox(
            size = Vector3(0.30f, 0.24f, 0.30f),
            position = initialTransforms[3].first,
            rotation = initialTransforms[3].second,
            bitmap = tableBmp
        )

        listOf(c0, c1, c2, c3).forEachIndexed { idx, e ->
            clueEntities[idx] = e
            interactiveTargetMap[e] = "clue" to idx
            content.addEntity(e)

            val (signPos, signRot) = computeSignTransformForClue(initialTransforms[idx].first, initialTransforms[idx].second)
            val signEntity = createInteractiveBox(
                size = Vector3(0.54f, 0.26f, 0.02f),
                position = signPos,
                rotation = signRot,
                bitmap = signPlaceholderBmp
            ).apply {
                setEnabled(false)
            }
            clueSignEntities[idx] = signEntity
            interactiveTargetMap[signEntity] = "clue" to idx
            content.addEntity(signEntity)
        }

        // 书桌中央额外放置一个立体 3D 机关宝箱盖（与 clue 3 同步贴上 AI 生成的宝箱贴图）
        val chestTop = createInteractiveBox(
            size = Vector3(0.32f, 0.22f, 0.28f),
            position = Vector3(0f, 0.88f, -1.38f),
            rotation = EulerAngles(0f, 18f, 0f),
            bitmap = tableBmp
        )
        interactiveTargetMap[chestTop] = "clue" to 3
        usdzBoxEntity = chestTop
        content.addEntity(chestTop)

        // 房间驻场 AI 嫌疑人：立式底座 + 竖幅角色立牌 + 头顶 3D 实时对话气泡（支持 360° 随机出现与巡逻走动）
        val initialSpot = suspectSpawnSpots.first()
        suspectPedestalEntity = createTexturedBox(
            size = Vector3(0.48f, 0.82f, 0.24f),
            position = initialSpot.pedestalPos,
            bitmap = tableBmp,
            rotation = initialSpot.rotation
        ).also { content.addEntity(it) }

        suspectEntity = createInteractiveBox(
            size = Vector3(0.60f, 0.76f, 0.05f),
            position = initialSpot.suspectPos,
            rotation = initialSpot.rotation,
            bitmap = wallBmp
        ).also {
            interactiveTargetMap[it] = "suspect" to 0
            content.addEntity(it)
        }

        val initialBubbleBmp = art.synthesizeSuspectSpeechBubbleBitmap(
            suspectName = "神秘驻场人",
            spotLabel = initialSpot.label,
            dialogue = "你有什么事？点击我或气泡即可现场对话盘问。",
            palette = initialPalette
        )
        suspectSpeechBubbleEntity = createInteractiveBox(
            size = Vector3(0.64f, 0.30f, 0.02f),
            position = initialSpot.bubblePos,
            rotation = initialSpot.rotation,
            bitmap = initialBubbleBmp
        ).also {
            interactiveTargetMap[it] = "suspect" to 0
            content.addEntity(it)
        }

        // ④ 地面 4 个 3D 空间瞬移光环（点击地面光环即可瞬移到对应机位近距离搜证）
        teleportRingEntities.clear()
        val ringPositions = listOf(
            Vector3(0f, 0.03f, -0.35f),    // 机位 0：房间中央全景
            Vector3(0f, 0.03f, -0.82f),    // 机位 1：走近中央书桌
            Vector3(-1.05f, 0.03f, -1.75f),// 机位 2：走近左墙与密室大门
            Vector3(0.85f, 0.03f, 0.65f)   // 机位 3：走近右墙与身后后墙
        )
        ringPositions.forEachIndexed { idx, pos ->
            val ring = createInteractiveCylinder(
                radius = 0.24f,
                height = 0.02f,
                position = pos,
                bitmap = if (idx == 0) emeraldGlowBmp else cyanGlowBmp
            )
            teleportRingEntities.add(ring)
            interactiveTargetMap[ring] = "teleport" to idx
            content.addEntity(ring)
        }
    }

    /**
     * 构建三间密室各自专属的 3D 空间家具组
     */
    private fun buildRoomSpecificProps(
        content: SpatialViewContent,
        tableBmp: Bitmap,
        wallBmp: Bitmap,
        cyanGlowBmp: Bitmap,
        purpleGlowBmp: Bitmap,
        emeraldGlowBmp: Bitmap
    ) {
        room0Props.clear()
        room1Props.clear()
        room2Props.clear()

        // --- Room 0 专属：左墙多层 3D 书架立柜（背板 + 3 层隔板 + 书本方块）+ 后墙装饰画框 ---
        val shelfBack = createTexturedBox(Vector3(0.08f, 1.75f, 1.15f), Vector3(-2.28f, 0.95f, -1.45f), tableBmp)
        room0Props.add(shelfBack)
        listOf(0.45f, 0.95f, 1.45f).forEach { y ->
            val plank = createTexturedBox(Vector3(0.28f, 0.04f, 1.12f), Vector3(-2.18f, y, -1.45f), tableBmp)
            room0Props.add(plank)
        }
        val bookBlock1 = createTexturedBox(Vector3(0.18f, 0.28f, 0.36f), Vector3(-2.16f, 0.62f, -1.62f), cyanGlowBmp)
        val bookBlock2 = createTexturedBox(Vector3(0.18f, 0.30f, 0.42f), Vector3(-2.16f, 1.12f, -1.25f), purpleGlowBmp)
        val backFrameOuter = createTexturedBox(Vector3(1.35f, 0.88f, 0.05f), Vector3(-0.85f, 1.52f, 1.52f), tableBmp)
        val backFrameInner = createTexturedBox(Vector3(1.15f, 0.68f, 0.06f), Vector3(-0.85f, 1.52f, 1.51f), wallBmp)
        room0Props.addAll(listOf(bookBlock1, bookBlock2, backFrameOuter, backFrameInner))
        room0Props.forEach {
            it.setEnabled(true)
            content.addEntity(it)
        }

        // --- Room 1 专属：左墙炼金/医疗药剂架（隔板 + 多个圆柱药剂瓶）+ 右墙双联大型服务器/动力机柜 ---
        listOf(0.95f, 1.42f).forEach { y ->
            val rack = createTexturedBox(Vector3(0.26f, 0.04f, 1.20f), Vector3(-2.22f, y, 0.15f), tableBmp)
            room1Props.add(rack)
        }
        listOf(-0.25f, 0.05f, 0.35f, 0.58f).forEachIndexed { i, z ->
            val bottleBmp = if (i % 2 == 0) cyanGlowBmp else emeraldGlowBmp
            val bottle = createTexturedCylinder(0.055f, 0.24f, Vector3(-2.18f, 1.10f, z), bottleBmp)
            val upperBottle = createTexturedCylinder(0.05f, 0.22f, Vector3(-2.18f, 1.55f, z), purpleGlowBmp)
            room1Props.add(bottle)
            room1Props.add(upperBottle)
        }
        val serverCabinetA = createTexturedBox(Vector3(0.42f, 1.95f, 0.68f), Vector3(2.12f, 0.98f, -0.35f), wallBmp)
        val serverCabinetB = createTexturedBox(Vector3(0.42f, 1.95f, 0.68f), Vector3(2.12f, 0.98f, 0.45f), wallBmp)
        val serverLightA = createTexturedBox(Vector3(0.44f, 0.12f, 0.52f), Vector3(2.10f, 1.55f, -0.35f), cyanGlowBmp)
        val serverLightB = createTexturedBox(Vector3(0.44f, 0.12f, 0.52f), Vector3(2.10f, 1.25f, 0.45f), emeraldGlowBmp)
        room1Props.addAll(listOf(serverCabinetA, serverCabinetB, serverLightA, serverLightB))
        room1Props.forEach {
            it.setEnabled(false)
            content.addEntity(it)
        }

        // --- Room 2 专属：左右对称图腾能量巨柱（createCylinder + createSphere 核心球）+ 中央祭坛底座 ---
        val leftTotemPillar = createTexturedCylinder(0.18f, 2.5f, Vector3(-1.55f, 1.25f, -0.55f), tableBmp)
        val rightTotemPillar = createTexturedCylinder(0.18f, 2.5f, Vector3(1.55f, 1.25f, -0.55f), tableBmp)
        val leftTotemOrb = createTexturedSphere(0.22f, Vector3(-1.55f, 1.55f, -0.55f), purpleGlowBmp)
        val rightTotemOrb = createTexturedSphere(0.22f, Vector3(1.55f, 1.55f, -0.55f), cyanGlowBmp)
        val altarPedestal = createTexturedCylinder(0.68f, 0.10f, Vector3(0f, 0.05f, -1.28f), emeraldGlowBmp)
        room2Props.addAll(listOf(leftTotemPillar, rightTotemPillar, leftTotemOrb, rightTotemOrb, altarPedestal))
        room2Props.forEach {
            it.setEnabled(false)
            content.addEntity(it)
        }
    }

    /**
     * 切换到指定房间（Room 0 / Room 1 / Room 2）：
     * - 切换墙面、地面、后墙、横梁与大门材质；
     * - 切换三间房专属 3D 家具组（room0Props / room1Props / room2Props）；
     * - 将 4 个 3D 线索重新藏匿到该房间对应的 360° 坐标（含后墙），并将线索上方悬浮立牌默认隐藏（setEnabled(false)）严防剧透；
     * - 同步大门推开/闭合旋转动画。
     */
    fun applyRoomStage(result: OrchestrationResult, roomIndex: Int, isDoorUnlocked: Boolean) {
        val assets = result.visualAssets
        val roomLevel = result.script.rooms.getOrNull(roomIndex) ?: result.script.rooms.first()
        val bundle = assets.roomBundles.getOrNull(roomIndex) ?: assets.roomBundles.first()
        val transforms = roomClueTransforms.getOrNull(roomIndex) ?: roomClueTransforms.first()

        interactedClueIndices.clear()

        applyBitmapToEntity(floorEntity, bundle.floorBitmap)
        applyBitmapToEntity(frontWallEntity, bundle.wallBitmap)
        applyBitmapToEntity(backWallEntity, bundle.wallBitmap)
        applyBitmapToEntity(leftWallEntity, bundle.wallBitmap)
        applyBitmapToEntity(rightWallEntity, bundle.wallBitmap)
        applyBitmapToEntity(tableEntity, bundle.tableBitmap)
        pillarEntities.forEach { applyBitmapToEntity(it, bundle.tableBitmap) }
        ceilingBeamEntities.forEach { applyBitmapToEntity(it, bundle.tableBitmap) }

        // 切换三间房差异化专属 3D 家具组
        room0Props.forEach { it.setEnabled(roomIndex == 0) }
        room1Props.forEach { it.setEnabled(roomIndex == 1) }
        room2Props.forEach { it.setEnabled(roomIndex == 2) }

        applyBitmapToEntity(
            doorEntity,
            if (isDoorUnlocked) bundle.doorUnlockedBitmap else bundle.doorLockedBitmap
        )
        animateDoorOpen(isDoorUnlocked)

        roomLevel.clues.forEachIndexed { idx, clue ->
            val entity = clueEntities[idx] ?: return@forEachIndexed
            val propBmp = assets.clueBitmaps[clue.clueId] ?: bundle.tableBitmap
            applyBitmapToEntity(entity, propBmp)
            if (idx == 3 || clue.isLocked || clue.visualType == "box") {
                applyBitmapToEntity(usdzBoxEntity, propBmp)
            }

            val (pos, rot) = transforms.getOrNull(idx) ?: (Vector3(0f, 0.8f, -1.2f) to EulerAngles(0f, 0f, 0f))
            // 后墙上的线索调整为竖幅壁挂尺寸，地面/桌面保持原有自然比例
            val currentScale = Vector3(1f, 1f, 1f)
            registerTransform(entity, pos, rot, currentScale)

            // 预置线索上方的全息立牌：进入房间时默认隐藏（setEnabled(false)），绝不提前剧透！
            val signEntity = clueSignEntities[idx]
            if (signEntity != null) {
                val signBmp = assets.clueSignBitmaps[clue.clueId]
                if (signBmp != null) {
                    applyBitmapToEntity(signEntity, signBmp)
                }
                val (signPos, signRot) = computeSignTransformForClue(pos, rot)
                registerTransform(signEntity, signPos, signRot, Vector3(1f, 1f, 1f))
                signEntity.setEnabled(false)
            }
        }

        val suspect = roomLevel.suspects.firstOrNull()
        if (suspect != null) {
            val supBmp = assets.suspectBitmaps[suspect.suspectId] ?: bundle.wallBitmap
            applyBitmapToEntity(suspectEntity, supBmp)
            applyBitmapToEntity(suspectPedestalEntity, bundle.tableBitmap)
            relocateSuspectRandomly(
                suspectName = suspect.name,
                dialogue = suspect.initialGreeting,
                palette = assets.palette
            )
        }
    }

    /**
     * 让驻场嫌疑人在房间 5 个 360° 空间点位中随机出现/走动巡逻，并同步更新其头顶 3D 实时对话气泡！
     */
    fun relocateSuspectRandomly(
        suspectName: String,
        dialogue: String,
        palette: ThemePalette
    ): SuspectSpatialSpot {
        val candidateIndices = suspectSpawnSpots.indices.filter { it != currentSuspectSpotIndex }
        val nextIndex = if (candidateIndices.isNotEmpty()) candidateIndices.random() else 0
        currentSuspectSpotIndex = nextIndex
        val spot = suspectSpawnSpots[nextIndex]

        suspectPedestalEntity?.let { registerTransform(it, spot.pedestalPos, spot.rotation) }
        suspectEntity?.let { registerTransform(it, spot.suspectPos, spot.rotation) }
        suspectSpeechBubbleEntity?.let { registerTransform(it, spot.bubblePos, spot.rotation) }

        updateSuspectSpeechBubble(suspectName, dialogue, palette)
        return spot
    }

    /**
     * 实时刷新 3D 空间内嫌疑人头顶的对话气泡内容
     */
    fun updateSuspectSpeechBubble(
        suspectName: String,
        dialogue: String,
        palette: ThemePalette
    ) {
        val bubble = suspectSpeechBubbleEntity ?: return
        val spot = suspectSpawnSpots.getOrNull(currentSuspectSpotIndex) ?: suspectSpawnSpots.first()
        val art = ArtTextureAgent()
        val bmp = art.synthesizeSuspectSpeechBubbleBitmap(
            suspectName = suspectName,
            spotLabel = spot.label,
            dialogue = dialogue,
            palette = palette
        )
        applyBitmapToEntity(bubble, bmp)
    }

    fun getCurrentSuspectSpot(): SuspectSpatialSpot =
        suspectSpawnSpots.getOrNull(currentSuspectSpotIndex) ?: suspectSpawnSpots.first()

    /**
     * 当玩家点击某个 3D 线索时：
     * 1) 让该线索 Entity 向上浮起/滑开位移（y + 0.09f）并微转角度，提供真实物理反馈；
     * 2) 将其上方的 clueSignEntities[clueIdx] 设为 setEnabled(true) 并贴上破译后的 signBitmap！
     */
    fun onClueInteracted(clueIdx: Int, signBitmap: Bitmap?) {
        val clueEntity = clueEntities[clueIdx]
        if (clueEntity != null && !interactedClueIndices.contains(clueIdx)) {
            interactedClueIndices.add(clueIdx)
            val base = basePositions[clueEntity]
            if (base != null) {
                val liftedPos = Vector3(base.position.x, base.position.y + 0.09f, base.position.z)
                val tiltedRot = EulerAngles(0f, 24f, 0f)
                registerTransform(clueEntity, liftedPos, tiltedRot, base.scale)
            }
        }

        val signEntity = clueSignEntities[clueIdx] ?: return
        if (signBitmap != null) {
            applyBitmapToEntity(signEntity, signBitmap)
        }
        signEntity.setEnabled(true)
    }

    /**
     * 当房间机关锁解开时，将 doorEntity 绕左侧门轴旋转推开（EulerAngles(0f, -68f, 0f) 并微调 X/Z 坐标），呈现真实推门效果！
     */
    fun animateDoorOpen(isOpen: Boolean) {
        val door = doorEntity ?: return
        if (isOpen) {
            registerTransform(
                entity = door,
                position = Vector3(-1.36f, 1.0f, -2.12f),
                rotation = EulerAngles(0f, -68f, 0f)
            )
        } else {
            registerTransform(
                entity = door,
                position = Vector3(-1.05f, 1.0f, -2.45f),
                rotation = EulerAngles(0f, 0f, 0f)
            )
        }
    }

    /**
     * 4 机位空间瞬移探索（Spatial Viewpoint Teleportation）：
     * 当切换机位时，统一将所有房间 3D 实体的 TransformComponent 更新为 basePos + offset，
     * 让玩家能真正“走近书桌”、“走近左墙大门”、“走近右墙/后墙”近距离搜证！
     */
    fun setViewpointOffset(offset: Vector3) {
        currentViewpointOffset = offset
        basePositions.forEach { (entity, base) ->
            applyOffsetTransform(entity, base)
        }
    }

    private fun computeSignTransformForClue(cluePos: Vector3, clueRot: EulerAngles): Pair<Vector3, EulerAngles> {
        val signY = (cluePos.y + 0.42f).coerceAtMost(2.25f)
        // 让悬浮立牌稍微向房间中心靠拢避免穿墙，并朝向房间中心方便玩家阅读
        val pullInX = when {
            cluePos.x < -1.8f -> cluePos.x + 0.14f
            cluePos.x > 1.8f -> cluePos.x - 0.14f
            else -> cluePos.x
        }
        val pullInZ = when {
            cluePos.z > 1.2f -> cluePos.z - 0.14f
            cluePos.z < -2.1f -> cluePos.z + 0.14f
            else -> cluePos.z
        }
        val signRot = when {
            cluePos.z > 1.1f -> EulerAngles(0f, 180f, 0f)
            cluePos.x < -1.8f -> EulerAngles(0f, 90f, 0f)
            cluePos.x > 1.8f -> EulerAngles(0f, -90f, 0f)
            else -> clueRot
        }
        return Vector3(pullInX, signY, pullInZ) to signRot
    }

    private fun Entity.setEnabled(enabled: Boolean) {
        entityModels[this]?.isVisible = enabled
        val base = basePositions[this]
        if (base != null) {
            val newScale = if (enabled) Vector3(1f, 1f, 1f) else Vector3(0f, 0f, 0f)
            val updated = base.copy(scale = newScale)
            basePositions[this] = updated
            applyOffsetTransform(this, updated)
        }
    }

    private fun attachInteractiveClueRecursively(entity: Entity, clueIndex: Int) {
        runCatching {
            entity.components.set(InteractableComponent())
            entity.components.set(
                CollisionComponent(
                    collisionShape = listOf(ShapeResource.createBox(Vector3(0.36f, 0.30f, 0.36f))),
                    physicsMaterial = PhysicsMaterialResource()
                )
            )
        }
        interactiveTargetMap[entity] = "clue" to clueIndex
    }

    private fun registerTransform(
        entity: Entity,
        position: Vector3,
        rotation: EulerAngles,
        scale: Vector3 = Vector3(1f, 1f, 1f)
    ) {
        val base = BaseTransform(position, rotation, scale)
        basePositions[entity] = base
        applyOffsetTransform(entity, base)
    }

    private fun applyOffsetTransform(entity: Entity, base: BaseTransform) {
        val shifted = Vector3(
            base.position.x + currentViewpointOffset.x,
            base.position.y + currentViewpointOffset.y,
            base.position.z + currentViewpointOffset.z
        )
        entity.components.set(TransformComponent(shifted, base.rotation, base.scale))
    }

    private fun applyBitmapToEntity(entity: Entity?, bitmap: Bitmap) {
        entity ?: return
        val model = entityModels[entity] ?: return
        val mat = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColorTexture(TextureResource.create(bitmap))
        }
        model.materials[0] = mat
    }

    private fun createTexturedBox(
        size: Vector3,
        position: Vector3,
        bitmap: Bitmap,
        rotation: EulerAngles = EulerAngles(0f, 0f, 0f)
    ): Entity {
        val mat = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColorTexture(TextureResource.create(bitmap))
        }
        val model = ModelComponent(MeshResource.createBox(size, 0f), mat)
        return Entity().apply {
            components.set(model)
            entityModels[this] = model
            registerTransform(this, position, rotation)
        }
    }

    private fun createInteractiveBox(
        size: Vector3,
        position: Vector3,
        rotation: EulerAngles,
        bitmap: Bitmap
    ): Entity {
        return createTexturedBox(size, position, bitmap, rotation).apply {
            components.set(InteractableComponent())
            components.set(
                CollisionComponent(
                    collisionShape = listOf(ShapeResource.createBox(size)),
                    physicsMaterial = PhysicsMaterialResource()
                )
            )
        }
    }

    private fun createTexturedCylinder(
        radius: Float,
        height: Float,
        position: Vector3,
        bitmap: Bitmap,
        rotation: EulerAngles = EulerAngles(0f, 0f, 0f)
    ): Entity {
        val mat = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColorTexture(TextureResource.create(bitmap))
        }
        val model = ModelComponent(MeshResource.createCylinder(radius, height), mat)
        return Entity().apply {
            components.set(model)
            entityModels[this] = model
            registerTransform(this, position, rotation)
        }
    }

    private fun createInteractiveCylinder(
        radius: Float,
        height: Float,
        position: Vector3,
        bitmap: Bitmap
    ): Entity {
        return createTexturedCylinder(radius, height, position, bitmap).apply {
            components.set(InteractableComponent())
            components.set(
                CollisionComponent(
                    collisionShape = listOf(ShapeResource.createBox(Vector3(radius * 2f, height.coerceAtLeast(0.06f), radius * 2f))),
                    physicsMaterial = PhysicsMaterialResource()
                )
            )
        }
    }

    private fun createTexturedSphere(
        radius: Float,
        position: Vector3,
        bitmap: Bitmap
    ): Entity {
        val mat = UnlitMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColorTexture(TextureResource.create(bitmap))
        }
        val model = ModelComponent(MeshResource.createSphere(radius), mat)
        return Entity().apply {
            components.set(model)
            entityModels[this] = model
            registerTransform(this, position, EulerAngles(0f, 0f, 0f))
        }
    }

    private fun synthesizeSolidGlowBitmap(centerColor: Int, edgeColor: Int): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size / 2f, size / 2f, size * 0.55f,
                intArrayOf(centerColor, edgeColor, 0xFF090D16.toInt()),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        return bmp
    }
}
