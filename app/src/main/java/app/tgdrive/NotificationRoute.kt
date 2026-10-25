package app.tgdrive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

internal fun notificationRoute(context:Context,profile:String,route:String):PendingIntent = PendingIntent.getActivity(context,0,
    Intent(context,MainActivity::class.java).setData(Uri.Builder().scheme("tgdrive-notification").authority("open").appendPath(profile).appendPath(route).build())
        .putExtra("openProfile",profile).putExtra("openRoute",route)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
internal fun profileNotificationId(profile:String)=1000+(profile.hashCode().ushr(1)%1_000_000)
