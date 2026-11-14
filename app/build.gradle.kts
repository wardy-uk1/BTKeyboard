plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wardy.btkeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wardy.btkeyboard"
        minSdk = 28          // Android 9: first version with the Bluetooth HID device API
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs straight away.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

/**
 * Copies the "What's new" section of README.md into the app's assets at build time, so the
 * About page always shows the same notes as GitHub. Update the README and the app follows.
 */
abstract class WhatsNewTask : DefaultTask() {
    @get:InputFile abstract val readme: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val lines = readme.get().asFile.readLines()
        val start = lines.indexOfFirst { it.trim() == "## What's new" }
        val body = if (start < 0) emptyList() else lines.drop(start + 1).takeWhile { !it.startsWith("## ") }
        val out = outputDir.get().asFile
        out.mkdirs()
        out.resolve("whatsnew.md").writeText(body.joinToString("\n").trim())
    }
}

val generateWhatsNew = tasks.register<WhatsNewTask>("generateWhatsNew") {
    readme.set(rootProject.layout.projectDirectory.file("README.md"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateWhatsNew, WhatsNewTask::outputDir)
    }
}
