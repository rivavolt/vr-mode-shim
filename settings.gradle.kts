pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The classic Xposed API (de.robv.android.xposed:api) lives only here.
        // compileOnly, so none of it ships in the module APK — the framework
        // provides XposedBridge at runtime.
        maven { url = uri("https://api.xposed.info/") }
    }
}

rootProject.name = "vr-mode-shim"
include(":app")
