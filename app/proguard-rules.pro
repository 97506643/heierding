# ===== 黑耳钉 heiErDing =====
# 模块整体保活：入口、Feature、设置页、Hook、反射桥、事件总线等
# 因为大量逻辑通过反射/Xposed Hook 调用宿主，被裁掉会出现运行时 NoSuchMethodError。
-keep class h.heiErDing.** { *; }
-keepclassmembers class h.heiErDing.** { *; }
-keepnames class h.heiErDing.** { *; }

# ===== Xposed =====
-keep class de.robv.android.xposed.** { *; }
-keep class de.robv.android.xposed.callbacks.** { *; }

# ===== DexKit（AAR 自带 proguard.txt 也有一条 native 保活，这里再加保险）=====
-keep class org.luckypray.dexkit.** { *; }
-keepclasseswithmembers,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
}

# ===== KavaRef（反射封装，不能混淆其成员）=====
-keep class com.highcapable.kavaref.** { *; }

# ===== FastKV（本地存储）=====
-keep class io.github.billywei01.fastkv.** { *; }

# ===== 系统 Xml API：XmlPullParser 实现由系统返回，接口 ABI 不可收窄 =====
-keep class org.xmlpull.v1.** { *; }
-keep class android.content.res.** { *; }

# ===== 保留反射方式访问的微信内部字段/方法名 =====
# 这些短名由 XposedHelpers / KavaRef 按字符串查找到，混淆会使其失效。
-keepclassmembers class * {
    <fields>;
    <methods>;
}
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ===== 移除日志噪音 =====
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
