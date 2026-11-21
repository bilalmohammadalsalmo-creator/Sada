plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.sadasync"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.sadasync"
        minSdk = 26
        targetSdk = 34
        // يمكن تجاوزهما من سطر الأوامر (يفعل ذلك سير العمل على GitHub عند نشر إصدار): -PverCode=2 -PverName=1.2
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("verName") as String?) ?: "0.1.0"

        // غيّر هذين السطرين إلى عنوان خادمك واسم بوت الدعم عندك
        buildConfigField("String", "API_BASE", "\"https://example.com/api\"")
        buildConfigField("String", "SUPPORT_BOT", "\"@your_support_bot\"")

        // رابط ملف التحديث (update.json في المستودع). غيّر اسم المستخدم/المستودع إن لزم.
        buildConfigField(
            "String", "UPDATE_URL",
            "\"https://raw.githubusercontent.com/bilalmohammadalsalmo-creator/Sada/main/update.json\""
        )
        // وسائل الدعم: اتركها فارغة لإخفائها. مثال واتساب: https://wa.me/9627XXXXXXXX  تيليغرام: https://t.me/username
        buildConfigField("String", "SUPPORT_WHATSAPP", "\"https://wa.me/962782154659\"")
        buildConfigField("String", "SUPPORT_TELEGRAM", "\"https://t.me/BilalAlsalmo\"")
        // بلاغات الدعم تصل إلى تيليغرام عبر بوت: ضع هنا توكن البوت ورقم المحادثة (انظر الشرح). اتركهما فارغين لتعطيل الإرسال.
        buildConfigField("String", "TG_BOT_TOKEN", "\"9245294979:AAFMJu3obXbsVI38Bq810PMTGiYzny3Cgyo\"")
        buildConfigField("String", "TG_CHAT_ID", "\"1797006796\"")
        buildConfigField("String", "SUPPORT_PHONE", "\"+962 78 215 4659\"")
    }

    // مفتاح توقيع ثابت: بدونه لا يقبل أندرويد تحديث التطبيق فوق نسخة سابقة. يُمرَّر من أسرار GitHub (انظر README).
    val ksPath: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("sada") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { if (ksPath != null) signingConfig = signingConfigs.getByName("sada") }
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("sada")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
}
