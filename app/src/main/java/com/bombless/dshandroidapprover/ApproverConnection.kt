package com.bombless.dshandroidapprover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder

data class TaskItem(val id:String,val sessionId:String,val cwd:String?,val pendingApprovals:Int)
data class ApprovalItem(val id:String,val taskId:String,val sessionId:String,val toolName:String,val reason:String?)
data class ApproverState(val endpoint:String="",val token:String="",val connected:Boolean=false,val error:String?=null,val tasks:List<TaskItem> = emptyList(),val approvals:List<ApprovalItem> = emptyList())

object ApproverConnection {
    private val client=OkHttpClient()
    private var socket:WebSocket?=null
    private val _state=MutableStateFlow(ApproverState())
    val state:StateFlow<ApproverState> = _state
    var onApprovalRequested:((ApprovalItem)->Unit)?=null

    fun configure(endpoint:String,token:String){ _state.value=_state.value.copy(endpoint=endpoint,token=token) }

    fun connect(){
        disconnect()
        val s=_state.value
        if(s.endpoint.isBlank()||s.token.isBlank()){_state.value=s.copy(error="Endpoint and token are required");return}
        val sep=if(s.endpoint.contains("?")) "&" else "?"
        val url=s.endpoint+sep+"token="+URLEncoder.encode(s.token,"UTF-8")
        socket=client.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response){_state.value=_state.value.copy(connected=true,error=null)}
            override fun onMessage(webSocket:WebSocket,text:String)=parse(text)
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){_state.value=_state.value.copy(connected=false,error=t.message?:"connection failed")}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){_state.value=_state.value.copy(connected=false)}
        })
    }

    private fun parse(text:String){
        runCatching{
            val root=JSONObject(text)
            when(root.optString("type")){
                "approval/requested"->{
                    val a=root.getJSONObject("approval")
                    onApprovalRequested?.invoke(ApprovalItem(a.getString("id"),a.getString("taskId"),a.getString("sessionId"),a.getString("toolName"),a.optString("reason").takeIf{it.isNotBlank()}))
                }
                "snapshot"->{
                    val tj=root.optJSONArray("tasks"); val aj=root.optJSONArray("approvals")
                    val tasks=buildList{for(i in 0 until (tj?.length()?:0)){val t=tj!!.getJSONObject(i);add(TaskItem(t.getString("id"),t.getString("sessionId"),t.optString("cwd").takeIf{it.isNotBlank()},t.optInt("pendingApprovals")))}}
                    val approvals=buildList{for(i in 0 until (aj?.length()?:0)){val a=aj!!.getJSONObject(i);add(ApprovalItem(a.getString("id"),a.getString("taskId"),a.getString("sessionId"),a.getString("toolName"),a.optString("reason").takeIf{it.isNotBlank()}))}}
                    _state.value=_state.value.copy(tasks=tasks,approvals=approvals)
                }
            }
        }
    }

    fun decide(id:String,allow:Boolean){socket?.send(JSONObject().put("type","approval/decision").put("approvalId",id).put("decision",if(allow)"allow-once" else "reject").toString())}
    fun disconnect(){socket?.close(1000,"user disconnected");socket=null;_state.value=_state.value.copy(connected=false)}
}
