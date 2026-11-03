package com.oforomar.watchbabymonitor.watch

import android.content.Context
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import java.io.Closeable
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Scores a ~1 s clip of 16 kHz mono audio for baby crying, 0..1. */
interface CryDetector : Closeable {
    fun cryScore(samples: FloatArray): Float
}

/**
 * YAMNet (AudioSet, 521 classes) through MediaPipe Tasks.
 * The model file is downloaded into assets at build time (see README).
 */
class YamnetCryDetector(context: Context) : CryDetector {

    private val classifier: AudioClassifier
    private val format = AudioData.AudioDataFormat.builder()
        .setNumOfChannels(1)
        .setSampleRate(SAMPLE_RATE.toFloat())
        .build()

    init {
        val assets = context.assets.list("")?.toList().orEmpty()
        require(MODEL in assets) { "Model $MODEL missing from assets" }

        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .build()
        classifier = AudioClassifier.createFromOptions(context, options)
    }

    override fun cryScore(samples: FloatArray): Float {
        val data = AudioData.create(format, samples.size)
        data.load(samples)
        val result = classifier.classify(data)
        var best = 0f
        for (clip in result.classificationResults()) {
            for (head in clip.classifications()) {
                for (category in head.categories()) {
                    if (category.categoryName() in CRY_LABELS) {
                        best = max(best, category.score())
                    }
                }
            }
        }
        return best
    }

    override fun close() {
        classifier.close()
    }

    companion object {
        const val MODEL = "yamnet.tflite"
        const val SAMPLE_RATE = 16_000

        /** YAMNet's window: 15600 samples = 0.975 s. */
        const val WINDOW_SAMPLES = 15_600

        val CRY_LABELS = setOf("Baby cry, infant cry", "Crying, sobbing")
    }
}

/** RMS level of 16-bit PCM in dBFS (0 = full scale, quiet room is roughly -60 to -70). */
fun rmsDbfs(pcm: ShortArray, length: Int = pcm.size): Float {
    if (length == 0) return -120f
    var sum = 0.0
    for (i in 0 until length) {
        val s = pcm[i] / 32768.0
        sum += s * s
    }
    val rms = sqrt(sum / length)
    return if (rms <= 1e-6) -120f else (20 * log10(rms)).toFloat()
}
