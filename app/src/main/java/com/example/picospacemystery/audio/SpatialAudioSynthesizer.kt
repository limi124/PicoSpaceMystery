package com.example.picospacemystery.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * 原生 PCM 双声道空间音频合成器（零外部资源依赖，基于 AudioTrack MODE_STATIC 实时生成立体声声场）
 */
object SpatialAudioSynthesizer {

    private const val SAMPLE_RATE = 24000
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 发现线索音效：根据方位标签（left/right/behind/center）计算双耳声道增益差，合成双音阶水晶泛音
     */
    fun playClueDiscovered(directionHint: String = "center") {
        val dir = directionHint.lowercase()
        val (leftGain, rightGain) = when {
            dir.contains("left") || dir.contains("左") -> 0.95f to 0.25f
            dir.contains("right") || dir.contains("右") -> 0.25f to 0.95f
            dir.contains("behind") || dir.contains("后") -> 0.65f to 0.65f
            else -> 0.85f to 0.85f
        }
        playStereoBuffer(durationMs = 420) { i, total ->
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            val freq = if (progress < 0.45) 587.33 else 880.0 // D5 -> A5
            val env = exp(-4.5 * (progress % 0.5)) * (1.0 - progress * 0.35)
            val wave = (sin(2.0 * PI * freq * t) * 0.7 + sin(2.0 * PI * (freq * 2.0) * t) * 0.3) * env * 0.55
            Pair((wave * leftGain).toFloat(), (wave * rightGain).toFloat())
        }
    }

    /**
     * 勘查工具显影破译音效：高频科技/魔法扫描显影琶音
     */
    fun playToolDecrypt() {
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50, 1318.51) // C5 E5 G5 C6 E6
        playStereoBuffer(durationMs = 560) { i, total ->
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            val idx = ((progress * notes.size).toInt()).coerceIn(0, notes.lastIndex)
            val freq = notes[idx]
            val localProg = (progress * notes.size) - idx
            val env = exp(-3.2 * localProg)
            val pan = (progress * 0.8 + 0.1).toFloat()
            val sig = (sin(2.0 * PI * freq * t) * 0.75 + sin(2.0 * PI * freq * 1.5 * t) * 0.25) * env * 0.5
            Pair((sig * (1.1f - pan * 0.5f)).toFloat(), (sig * (0.6f + pan * 0.5f)).toFloat())
        }
    }

    /**
     * 机械密码拨轮咔哒声：根据当前数字微调谐振频率
     */
    fun playDialClick(digit: Int = 5) {
        val baseFreq = 260.0 + (digit.coerceIn(0, 9) * 28.0)
        playStereoBuffer(durationMs = 75) { i, total ->
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            val env = exp(-18.0 * progress)
            val click = (sin(2.0 * PI * baseFreq * t) * 0.7 + (Random.nextDouble() - 0.5) * 0.3) * env * 0.65
            val v = click.toFloat()
            Pair(v, v)
        }
    }

    /**
     * 机关解锁成功音效：低频石门/液压轰鸣 + C 大调通关和弦琶音
     */
    fun playUnlockSuccess() {
        val chord = doubleArrayOf(261.63, 329.63, 392.00, 523.25, 659.25) // C4 E4 G4 C5 E5
        playStereoBuffer(durationMs = 820) { i, total ->
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            val rumble = sin(2.0 * PI * 72.0 * t) * exp(-2.5 * progress) * 0.35
            val noteIdx = ((progress * chord.size).toInt()).coerceIn(0, chord.lastIndex)
            val freq = chord[noteIdx]
            val arp = (sin(2.0 * PI * freq * t) * 0.7 + sin(2.0 * PI * freq * 2.0 * t) * 0.3) *
                exp(-1.8 * progress) * 0.48
            val mix = (rumble + arp).toFloat()
            Pair(mix * 0.95f, mix * 0.95f)
        }
    }

    /**
     * 密码错误警告音：双促低频失谐蜂鸣
     */
    fun playUnlockError() {
        playStereoBuffer(durationMs = 360) { i, total ->
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            val gate = if (progress < 0.42 || progress > 0.55) 1.0 else 0.08
            val env = exp(-2.0 * progress) * gate
            val wave = (sin(2.0 * PI * 155.56 * t) * 0.6 + sin(2.0 * PI * 164.81 * t) * 0.4) * env * 0.6
            val v = wave.toFloat()
            Pair(v, v)
        }
    }

    /**
     * 进入新密室房间的空间低频环境共鸣
     */
    fun playRoomTransition(roomIndex: Int) {
        val rootFreq = 110.0 + (roomIndex.coerceAtLeast(0) * 22.0)
        playStereoBuffer(durationMs = 680) { i, total ->
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            val swell = sin(PI * progress)
            val drone = (sin(2.0 * PI * rootFreq * t) * 0.6 + sin(2.0 * PI * (rootFreq * 1.498) * t) * 0.4) *
                swell * 0.48
            val shimmer = sin(2.0 * PI * (rootFreq * 4.0) * t) * swell * 0.14
            Pair((drone + shimmer * 0.8).toFloat(), (drone + shimmer * 1.2).toFloat())
        }
    }

    /**
     * 3D 机位瞬移音效：轻快空间气流扫频声
     */
    fun playTeleport() {
        playStereoBuffer(durationMs = 240) { i, total ->
            val t = i.toDouble() / SAMPLE_RATE
            val progress = i.toDouble() / total
            val sweepFreq = 320.0 + 620.0 * progress
            val env = sin(PI * progress) * exp(-1.5 * progress)
            val whoosh = (sin(2.0 * PI * sweepFreq * t) * 0.65 + (Random.nextDouble() - 0.5) * 0.25) * env * 0.45
            val left = (whoosh * (1.0 - progress * 0.4)).toFloat()
            val right = (whoosh * (0.6 + progress * 0.4)).toFloat()
            Pair(left, right)
        }
    }

    private fun playStereoBuffer(
        durationMs: Int,
        generator: (frameIndex: Int, totalFrames: Int) -> Pair<Float, Float>
    ) {
        audioScope.launch {
            runCatching {
                val totalFrames = (SAMPLE_RATE * durationMs) / 1000
                if (totalFrames <= 0) return@runCatching
                val pcm = ShortArray(totalFrames * 2)
                for (i in 0 until totalFrames) {
                    val (l, r) = generator(i, totalFrames)
                    pcm[i * 2] = (l.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                    pcm[i * 2 + 1] = (r.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                }
                val byteCount = pcm.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(byteCount)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(pcm, 0, pcm.size)
                track.play()
                delay(durationMs.toLong() + 90L)
                runCatching {
                    track.stop()
                }
                track.release()
            }
        }
    }
}
