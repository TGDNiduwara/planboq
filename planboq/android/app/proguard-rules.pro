# Keep OpenCV native bindings
-keep class org.opencv.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class com.planboq.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# PDFBox
-dontwarn org.apache.pdfbox.**
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.commons.logging.**
