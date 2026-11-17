# WorkManager's Room database is created by reflection; R8 would strip its constructor and crash at startup.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
