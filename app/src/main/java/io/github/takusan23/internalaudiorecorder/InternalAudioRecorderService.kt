package io.github.takusan23.internalaudiorecorder

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.provider.MediaStore
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.*

class InternalAudioRecorderService : Service() {

    // 内部音声取得で使う
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var projection: MediaProjection
    private lateinit var audioRecord: AudioRecord

    // 音声データをAACへエンコードするのに使う
    lateinit var mediaMuxer: MediaMuxer
    lateinit var mediaCodec: MediaCodec
    private val resultAudioFileName by lazy { "${getExternalFilesDir(null)}/internal.aac" }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        //データ受け取る
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", Activity.RESULT_OK) ?: Activity.RESULT_OK

        //通知を出す。
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        //通知チャンネル
        val channelID = "internal_audio_record_notification"
        //通知チャンネルが存在しないときは登録する
        if (notificationManager.getNotificationChannel(channelID) == null) {
            val channel =
                NotificationChannel(channelID, "内部音声録音通知", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        //通知作成
        val notification = Notification.Builder(applicationContext, channelID)
            .setContentText("収録中です")
            .setContentTitle("端末内録音")
            .setSmallIcon(R.drawable.ic_outline_mic_none_24)    //アイコンはベクターアセットから
            .build()

        startForeground(1, notification)

        // 録音関数
        startInternalAudioRecord(data, code)

        return START_NOT_STICKY
    }

    private fun startInternalAudioRecord(data: Intent?, code: Int) {
        if (data != null) {
            projectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // codeはActivity.RESULT_OKとかが入る。
            projection =
                projectionManager.getMediaProjection(code, data)
            // 内部音声取るのに使う
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44100)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            audioRecord.startRecording()
            // こっからMediaCodecとMediaExtractorの仕事
            recordInternalAudio(audioRecord)
        }
    }

    private fun recordInternalAudio(audioRecord: AudioRecord) {
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)

        mediaMuxer = MediaMuxer(resultAudioFileName, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // mediaFormat
        val mediaFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 196000)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        // せっと
        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        var totalBytesRead = 0
        var mPresentationTime = 0L
        var trackId = 0

        mediaCodec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                // 書き込むばっふぁ
                val codecInputBuffer = codec.getInputBuffer(index) ?: return
                val capacity = codecInputBuffer.capacity()
                // サイズに合わせて作成
                val buffer = ByteArray(capacity)
                // 内部音声を読み取る
                val readBytes = audioRecord.read(buffer, 0, buffer.size)
                // 0以上なら書き込む
                if (readBytes > 0) {
                    // 書き込む。書き込んだデータは[onOutputBufferAvailable]で受け取れる
                    codecInputBuffer.put(buffer, 0, readBytes)
                    codec.queueInputBuffer(index, 0, readBytes, mPresentationTime, 0)
                    // ここはAOSPパクった。経過時間を計算してる
                    totalBytesRead += readBytes
                    mPresentationTime = 1000000L * (totalBytesRead / 2) / 44100
                }
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                // 出力データ
                val buffer = codec.getOutputBuffer(index) ?: return
                mediaMuxer.writeSampleData(trackId, buffer, info)
                // 必須
                codec.releaseOutputBuffer(index, false)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                println("${e}")
                e.printStackTrace()
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                // このタイミングでaddTrackしないと MediaMuxer#stop() 呼べない
                trackId = mediaMuxer.addTrack(codec.outputFormat)
                mediaMuxer.start()
            }
        })
        mediaCodec.start()
    }

    // 終了ボタン押したとき
    override fun onDestroy() {
        super.onDestroy()
        // 終了処理
        projection.stop()
        audioRecord.release()
        mediaCodec.stop()
        mediaCodec.release()
        mediaMuxer.stop()
        mediaMuxer.release()
        // MediaStoreへ保存
        saveMediaStore()
    }

    // MediaStoreへ保存する。
    private fun saveMediaStore() {
        val fileDate = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        // MediaStoreへ保存
        val value = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "内部音声:${fileDate}.aac")
        }
        // ここに追加する
        val audioCollection =
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 追加すると返り値にUriが来るので控える
        val uri = contentResolver.insert(audioCollection, value) ?: return
        // OutputStreamを開く
        val outputStream = contentResolver.openOutputStream(uri, "w")
        // コピー
        Files.copy(File(resultAudioFileName).toPath(), outputStream)
        outputStream?.close()
        // 消す
        File(resultAudioFileName).delete()
    }

}
