package com.bombless.dshandroidapprover
import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class ApproverViewModel(app:Application):AndroidViewModel(app){
    private val prefs=app.getSharedPreferences("dsh-approver",0)
    val state:StateFlow<ApproverState> = ApproverConnection.state
    init{ApproverConnection.configure(prefs.getString("endpoint","")?:"",prefs.getString("token","")?:"")}
    fun setEndpoint(v:String){prefs.edit().putString("endpoint",v).apply();ApproverConnection.configure(v,state.value.token)}
    fun setToken(v:String){prefs.edit().putString("token",v).apply();ApproverConnection.configure(state.value.endpoint,v)}
    fun connect(){val s=state.value;ApproverConnection.connect();getApplication<Application>().startForegroundService(Intent(getApplication(),ApprovalService::class.java).putExtra(ApprovalService.EXTRA_ENDPOINT,s.endpoint).putExtra(ApprovalService.EXTRA_TOKEN,s.token))}
    fun disconnect(){ApproverConnection.disconnect();getApplication<Application>().stopService(Intent(getApplication(),ApprovalService::class.java))}
    fun decide(id:String,allow:Boolean)=ApproverConnection.decide(id,allow)
}
