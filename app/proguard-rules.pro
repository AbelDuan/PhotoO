# PhotoO 混淆规则

# Compose 运行时依赖反射较少，默认规则已足够；此处仅保留常见的安全项。
-dontwarn org.jetbrains.annotations.**

# 保留 Kotlin 元数据，便于崩溃栈可读
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable

# Coil 3
-dontwarn okio.**
-dontwarn okhttp3.**

# 应用自身的数据模型（通过反射或序列化访问时需要）
-keep class com.abel.photoo.model.** { *; }
