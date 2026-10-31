package app.volna.player

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Громкость того, что играет: под неё «дышат» линии на фоне, как в веб-версии.
 * Считаем по звуку на выходе ExoPlayer, поэтому разрешение на микрофон не нужно.
 */
object AudioLevel : TeeAudioProcessor.AudioBufferSink {
    /** 0 — тишина, 1 — громко; та же шкала, что у веб-плеера. */
    @Volatile var level = 0f
        private set

    @Volatile private var encoding = C.ENCODING_INVALID

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.encoding = encoding
        level = 0f
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        var sum = 0.0
        var count = 0
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val samples = data.asShortBuffer()
                var i = 0
                while (i < samples.limit()) { // каждый четвёртый отсчёт: для громкости хватает
                    val v = samples[i] / 32768.0
                    sum += v * v
                    count++
                    i += 4
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                val samples = data.asFloatBuffer()
                var i = 0
                while (i < samples.limit()) {
                    val v = samples[i].toDouble()
                    sum += v * v
                    count++
                    i += 4
                }
            }
            else -> return
        }
        if (count > 0) level = min(1.0, sqrt(sum / count) * 3.2).toFloat()
    }

    fun silence() {
        level = 0f
    }
}
