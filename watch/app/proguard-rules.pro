   # Ghar Watch — R8 rules
   -keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

   # Samsung Health Sensor SDK (used from Sprint 3; harmless now)
   -keep class com.samsung.android.service.health.tracking.** { *; }
   -dontwarn com.samsung.android.service.health.tracking.**
   # WorkManager's Room database (created by reflection at startup)
   -keep class androidx.work.impl.WorkDatabase_Impl { *; }
   -keep class * extends androidx.room.RoomDatabase { <init>(); }
   -keep class androidx.work.** { *; }
   -keep class androidx.health.services.client.** { *; }
   -keep class androidx.health.platform.client.** { *; }
   -keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
   -keep class dev.ghar.watch.health.** { *; }
   -keep class androidx.wear.protolayout.proto.** { *; }
   -keep class androidx.wear.tiles.proto.** { *; }
   -keep class dev.ghar.watch.tile.** { *; }
   -keep class dev.ghar.watch.workout.** { *; }
   -keep class dev.ghar.watch.complication.** { *; }
   -keep class androidx.wear.watchface.complications.** { *; }
