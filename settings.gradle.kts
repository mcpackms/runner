//settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 添加 Xposed 仓库
        maven {
            url = uri("https://api.xposed.info/repo")
        }
        // 可选：添加 JitPack 以备不时之需
//        maven {
//            url = uri("https://jitpack.io")
//        }
    }
}

rootProject.name = "PacketCaptureXposed"
include(":app")