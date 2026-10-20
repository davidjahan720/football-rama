# --- Football Rama : regles R8 ---

# kotlinx.serialization : les regles sont embarquees dans la bibliotheque (META-INF/com.android.tools/r8).
# Ceinture et bretelles pour nos classes @Serializable (persistance matches.json, fixtures.json).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature
-keepclassmembers @kotlinx.serialization.Serializable class fr.footballrama.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class fr.footballrama.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink : annotations et dependances optionnelles absentes a l'execution.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-dontwarn com.google.crypto.tink.integration.android.**

# OkHttp : plateformes TLS optionnelles.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Pas de logs Android en release (aucune donnee sensible n'est loggee, mais on retire tout).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
