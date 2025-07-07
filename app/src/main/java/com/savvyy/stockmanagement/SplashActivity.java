package com.savvyy.stockmanagement;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Start main activity
        startActivity(new Intent(SplashActivity.this, MainActivity.class));
        // Close splash activity
        finish();
    }
}
