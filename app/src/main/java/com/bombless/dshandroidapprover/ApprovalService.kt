package com.bombless.dshandroidapprover
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ApprovalService:Service(){
    override fun onCreate(){
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"DSH approvals",NotificationManager.IMPORTANCE_HIGH))
        ApproverConnection.onApprovalRequested={approval->
            val text="${approval.toolName}: ${approval.reason ?: "permission required"}"
            val notification=NotificationCompat.Builder(this,CHANNEL)
                .setContentTitle("DSH permission requested").setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${approval.toolName}\n${approval.reason ?: "Permission required"}"))
                .setSmallIcon(android.R.drawable.ic_lock_lock).setAutoCancel(true).build()
            manager.notify(approval.id.hashCode(),notification)
        }
        startForeground(NOTIFICATION_ID,persistentNotification())
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        val endpoint=intent?.getStringExtra(EXTRA_ENDPOINT);val token=intent?.getStringExtra(EXTRA_TOKEN)
        if(!endpoint.isNullOrBlank()&&!token.isNullOrBlank())ApproverConnection.configure(endpoint,token)
        ApproverConnection.connect();return START_STICKY
    }
    private fun persistentNotification():Notification=NotificationCompat.Builder(this,CHANNEL).setContentTitle("DSH Approver").setContentText("Waiting for DSH approvals").setSmallIcon(android.R.drawable.ic_lock_lock).setOngoing(true).build()
    override fun onDestroy(){ApproverConnection.onApprovalRequested=null;ApproverConnection.disconnect();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{const val EXTRA_ENDPOINT="endpoint";const val EXTRA_TOKEN="token";private const val CHANNEL="dsh-approvals";private const val NOTIFICATION_ID=38741}
}
