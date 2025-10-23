package com.hklab.hkruler

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.hklab.hkruler.databinding.ActivityResolutionInfoBinding

class ResolutionInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResolutionInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResolutionInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        title = "Resolution Info"
        binding.infoText.text = "카메라에서 지원하는 해상도 정보를 표시합니다."
    }
}
