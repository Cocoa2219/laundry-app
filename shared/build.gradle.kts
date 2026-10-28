plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    android {
        namespace = "dev.laundry.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    // JVM target so drying/ and learning/ tests run without an emulator.
    jvm()

    compilerOptions {
        // Room KMP generates an `actual object` for the database constructor (Beta language feature).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            // DataStore types (DataStore<Preferences>) appear in data/ constructors.
            api(libs.androidx.datastore.preferences.core)
            api(libs.ktor.client.core)
            api(libs.kotlinx.datetime)
            // LaundryDatabase is built by androidApp, so Room's builder types must be visible there.
            api(libs.room.runtime)
            api(libs.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

// Exported schemas are committed, so later migrations can be checked against them.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}
