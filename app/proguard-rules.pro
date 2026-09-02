# Keep Gson model classes (parsed from bundled JSON asset by field name).
-keep class com.bmw.assistant.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Room generates implementations reflectively at runtime for the @Database class.
-keep class com.bmw.assistant.data.db.** { *; }

# Keep the transport class names readable in crash reports: an obfuscated stack trace from a
# failed coding write is useless for support.
-keepnames class com.bmw.assistant.core.ecu.** { *; }

# The bundled JSON asset is parsed by field name, so the model classes must not be renamed.
-keepclassmembers class com.bmw.assistant.data.model.** {
    <fields>;
}
