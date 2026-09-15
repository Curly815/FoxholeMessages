# android-smsmms
# -keep class android.net.** { *; }
-dontwarn android.net.ConnectivityManager
-dontwarn android.net.LinkProperties

# autodispose
-dontwarn com.uber.autodispose.**

# ez-vcard
-dontwarn ezvcard.**
-dontwarn org.apache.log.**
-dontwarn org.apache.log4j.**
-dontwarn org.python.core.**

# okio
-dontwarn okio.**

# okhttp3
# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

# A resource is loaded with a relative path so the package of this class must be preserved.
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# OkHttp platform used only on JVM and when Conscrypt dependency is available.
-dontwarn okhttp3.internal.platform.ConscryptPlatform

# moshi
# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory

-keepclasseswithmembers class * {
    @com.squareup.moshi.* <methods>;
}

-keep @com.squareup.moshi.JsonQualifier interface *

# Enum field names are used by the integrated EnumJsonAdapter.
# Annotate enums with @JsonClass(generateAdapter = false) to use them with Moshi.
-keepclassmembers @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
}

# The name of @JsonClass types is used to look up the generated adapter.
-keepnames @com.squareup.moshi.JsonClass class *

# Retain generated target class's synthetic defaults constructor and keep DefaultConstructorMarker's
# name. We will look this up reflectively to invoke the type's constructor.
#
# We can't _just_ keep the defaults constructor because Proguard/R8's spec doesn't allow wildcard
# matching preceding parameters.
-keepnames class kotlin.jvm.internal.DefaultConstructorMarker
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>(...);
}

# Retain generated JsonAdapters if annotated type is retained.
-keep class **JsonAdapter {
    <init>(...);
    <fields>;
}

-if @com.squareup.moshi.JsonClass class *
-keep class <1>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*
-keep class <1>_<2>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*
-keep class <1>_<2>_<3>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*$*
-keep class <1>_<2>_<3>_<4>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*$*$*
-keep class <1>_<2>_<3>_<4>_<5>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*$*$*$*$*
-keep class <1>_<2>_<3>_<4>_<5>_<6>JsonAdapter {
    <init>(...);
    <fields>;
}
# Dagger, RxJava2, and androidx.activity.result were all previously blanket-kept here (`-keep
# class dagger.** { *; }`, `-keep class io.reactivex.** { *; }`, etc.) alongside the whole-app
# keep removed above - same over-caution, same effect: these are huge dependencies used
# throughout the app, so keeping them in full was a major contributor to the "Obfuscation (19%)"
# vitals warning staying below Play's 25% threshold even after the whole-app keep was removed.
#
# None of them actually need it. Dagger 2 (unlike reflection-based DI frameworks) resolves
# everything at compile time into generated code that's referenced directly - DaggerAppComponent
# is called by name in AppComponentManager.kt, and the generated Factory classes call @Provides
# methods and Module/Component constructors directly, not via reflection - so R8's normal
# reachability analysis keeps exactly what's used without any help. RxJava2 and
# androidx.activity.result (ActivityResultContracts, referenced directly wherever
# registerForActivityResult is used) are the same story: ordinary compiled calls, no reflection.

# This app used to blanket-keep its entire own package (`-keep class dev.octoshrimpy.quik.** { *; }`)
# alongside a project-wide -dontobfuscate, which together left the app essentially unobfuscated and
# unshrunk (flagged by Play Console's Android vitals as ~1% obfuscation coverage, below its 25%
# threshold, with a Feb 2027 deadline). Narrowed to what's actually known to need it instead of
# assuming the whole app does:
#
# - Realm model classes: Realm's schema/proxy generation reflects on these by exact field/class
#   name. android-smsmms, WorkManager (androidx.work), and this project's other AndroidX/Google
#   library dependencies all ship their own consumer ProGuard rules bundled in their AARs (a
#   standard library practice AGP auto-merges), so Worker subclasses and manifest-declared
#   components (Activities/Services/Receivers) don't need a rule here for that same reason -
#   Realm models are the one case genuinely worth an explicit safety net.
-keep class dev.octoshrimpy.quik.model.** { *; }

# InjectionWorkerFactory rebuilds Workers with Class.forName(workerClassName), using the class
# name WorkManager persisted in its own database back when the work was enqueued. That makes these
# a genuine reflection-by-name dependency, and one that has to stay stable across app updates too:
# an obfuscated name that shifts between builds orphans work the previous version already
# scheduled, which then fails to reconstruct forever. Only a handful of classes, so keeping them
# (plus the exact constructor the factory reflects on) costs almost nothing in coverage.
-keep class * extends androidx.work.Worker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Moshi's reflective Kotlin adapter (KotlinJsonAdapterFactory, registered in AppModule) resolves a
# class's properties through kotlin-reflect at runtime, so whatever property names R8 leaves behind
# are the JSON keys it reads and writes. The Moshi rules near the top of this file only cover the
# codegen path - they keep generated **JsonAdapter classes, and every type below is either
# @JsonClass(generateAdapter = false) or unannotated, so no adapter is generated for it and nothing
# keeps its members. These were all covered by the app-wide `-keep class dev.octoshrimpy.quik.**
# { *; }` removed above, which is why three unrelated features broke in the same release:
#
# - Backup/BackupMetadata/BackupMessage/BackupPart carry no @Json names at all, so their JSON keys
#   are the property names verbatim. Obfuscated, a backup is written with renamed keys that aren't
#   stable between builds, so a backup file can't be restored by a different version of the app.
# - EmojiPatternStrings holds the per-locale reaction regexes. Every field is nullable and every
#   use of it is null-safe, so names that don't resolve yield an all-null object that registers no
#   patterns at all - which logs identically to a successful load, and silently leaves iPhone
#   tapbacks arriving as plain "Liked ..." text instead of being recognised as reactions.
# - Changeset backs the what's-new dialog; its versionName/versionCode are non-null with no
#   defaults, so names that don't resolve throw instead, and the dialog comes up empty.
# Keeping the members above is necessary but, on its own, was not sufficient - the emoji patterns
# still came back empty with it in place. KotlinJsonAdapterFactory only engages for a class that
# still carries its kotlin.Metadata annotation; without it Moshi silently falls back to its
# field-based adapter, and that one cannot see these @Json names at all, because Kotlin puts an
# annotation on a constructor val onto the *parameter* rather than the field. The result is the
# same silent all-null object whatever the fields are called, which is why renaming was only half
# the story.
-keep class kotlin.Metadata { *; }

-keep class dev.octoshrimpy.quik.util.EmojiPatternStrings { *; }
-keep class dev.octoshrimpy.quik.manager.ChangelogManagerImpl$Changeset { *; }
-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl$Backup { *; }
-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl$BackupMetadata { *; }
-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl$BackupMessage { *; }
-keep class dev.octoshrimpy.quik.repository.BackupRepositoryImpl$BackupPart { *; }

# The photoview library's zoom levels are only configurable through package-private setters that
# reject our target values (see GalleryPagerAdapter's onCreateViewHolder) - working around that
# means writing directly to these private fields via reflection, which needs their exact names to
# survive whatever R8 does to this dependency's own code.
-keepclassmembers class com.github.chrisbanes.photoview.PhotoViewAttacher {
    private float mMinScale;
    private float mMidScale;
    private float mMaxScale;
}

