package io.github.takusan23.internalaudiorecorder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val MEDIA_PROJECTION_REQUEST_CODE = 4545

    private lateinit var projectionManager: MediaProjectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val permissionButton = findViewById<Button>(R.id.activity_main_permission_button)
        val recordButton = findViewById<Button>(R.id.activity_main_record_button)
        val stopButton = findViewById<Button>(R.id.activity_main_stop_button)

        // MediaProjectionを始めるためのMediaProjection（録音自体はServiceへ）
        projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // 権限ボタン押したら権限求める
        permissionButton.setOnClickListener {
            if (!isGetRecordPermission()) {
                // なければリクエスト
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            }
        }

        // 録画ボタン
        recordButton.setOnClickListener {
            if(isGetRecordPermission()){
                // ユーザーに画面録画していいか聞く
                startActivityForResult(projectionManager.createScreenCaptureIntent(),MEDIA_PROJECTION_REQUEST_CODE)
            }
        }

        // 録画停止。今回はServiceを止めることで再現
        stopButton.setOnClickListener {
            val intent = Intent(this, InternalAudioRecorderService::class.java)
            stopService(intent)
        }

    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 成功＋結果が画面録画の物か
        if (resultCode == Activity.RESULT_OK && requestCode == MEDIA_PROJECTION_REQUEST_CODE) {
            // Service起動
            // Manifestに「android:foregroundServiceType="mediaProjection"」を付け足しておく
            val intent = Intent(this, InternalAudioRecorderService::class.java)
            intent.putExtra("code", resultCode) // 必要なのは結果。startActivityForResultのrequestCodeではない。
            intent.putExtra("data", data)
            startForegroundService(intent)
        }

    }

    /** RECORD_AUDIO パーミッションが有るかどうか */
    private fun isGetRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

}