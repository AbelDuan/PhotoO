pluginManagement {
    repositories {
        // Gradle Plugin Portal 在本环境可达，承载 Kotlin / AGP 插件
        gradlePluginPortal()
        // 阿里云镜像（可达）作为 google() / mavenCentral() 的替代，避免被封主机超时
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        // 国内/可达镜像，覆盖 Google 与 Maven Central 依赖
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // Google 官方 maven2 镜像（已验证可达）兜底
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
    }
}

rootProject.name = "PhotoO"
include(":app")
