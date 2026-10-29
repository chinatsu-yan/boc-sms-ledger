plugins { id("com.android.application"); kotlin("android") }
android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    useLibrary("android.test.mock")
    namespace = "local.boc.ledger"
    compileSdk = 35
    defaultConfig {
        applicationId = "local.boc.ledger"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.1.7"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    signingConfigs { getByName("debug") { storeFile = rootProject.file("private/debug.keystore") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
    sourceSets["main"].assets.srcDir(rootProject.file("fixtures/synthetic"))
    lint { abortOnError = true }
}
dependencies { implementation(project(":core")) }

val debugKey = rootProject.file("private/debug.keystore")
val prepareDebugKeystore by tasks.registering(Exec::class) {
    description = "Create a local debug-only signing key outside version control"
    onlyIf { !debugKey.exists() }
    doFirst { debugKey.parentFile.mkdirs() }
    commandLine(File(System.getProperty("java.home"), "bin/keytool").absolutePath,
        "-genkeypair", "-noprompt", "-keystore", debugKey.absolutePath,
        "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android",
        "-dname", "CN=Android Debug,O=Android,C=US", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000")
}
tasks.matching { it.name == "validateSigningDebug" }.configureEach { dependsOn(prepareDebugKeystore) }
