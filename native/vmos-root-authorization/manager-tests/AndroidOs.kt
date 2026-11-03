package android.os

object Process { fun myUid(): Int = 10048 }
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
