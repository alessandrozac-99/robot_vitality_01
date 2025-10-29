plugins {
    id("com.google.gms.google-services") version "4.4.4" apply false
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}

task<Delete>("clean") {
    delete(rootProject.buildDir)
}
