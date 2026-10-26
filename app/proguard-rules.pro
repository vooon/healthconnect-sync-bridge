# Project-specific R8 rules. Health Connect, WorkManager, OkHttp and DataStore ship their own
# consumer rules, so only what this app itself does reflectively is listed here.

# SyncWorker is instantiated by WorkManager through reflection from its class name.
-keep class ru.vehq.hcsync.sync.SyncWorker { *; }

# Keep source file names and line numbers so crash stack traces from the phone stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
