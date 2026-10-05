package com.bombless.dshandroidapprover

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()){}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(Build.VERSION.SDK_INT>=33)notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent{
            val vm:ApproverViewModel=viewModel()
            val state by vm.state.collectAsState()
            MaterialTheme{
                ApproverScreen(state,vm::setEndpoint,vm::setToken,vm::connect,vm::disconnect,vm::decide)
            }
        }
    }
}

@Composable
private fun ApproverScreen(
    state:ApproverState,
    onEndpoint:(String)->Unit,
    onToken:(String)->Unit,
    onConnect:()->Unit,
    onDisconnect:()->Unit,
    onDecision:(String,Boolean)->Unit
){
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("DSH Approver",style=MaterialTheme.typography.headlineMedium)
        OutlinedTextField(value=state.endpoint,onValueChange=onEndpoint,modifier=Modifier.fillMaxWidth(),label={Text("WebSocket endpoint")},singleLine=true)
        OutlinedTextField(value=state.token,onValueChange=onToken,modifier=Modifier.fillMaxWidth(),label={Text("Access token")},singleLine=true)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Button(onClick=onConnect){Text("Connect")}
            TextButton(onClick=onDisconnect){Text("Disconnect")}
            Text(if(state.connected)"Connected" else state.error?:"Disconnected")
        }
        Text("Active tasks (${state.tasks.size})",style=MaterialTheme.typography.titleLarge)
        LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){
            items(state.tasks,key={it.id}){task->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp)){
                        Text(task.cwd?:task.id)
                        Text("Session: ${task.sessionId}")
                        Text("Pending approvals: ${task.pendingApprovals}")
                    }
                }
            }
            items(state.approvals,key={it.id}){approval->
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        Text("Permission requested",style=MaterialTheme.typography.titleMedium)
                        Text(approval.toolName)
                        if(!approval.reason.isNullOrBlank())Text(approval.reason!!)
                        Text("Task: ${approval.taskId}")
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            Button(onClick={onDecision(approval.id,true)}){Text("Allow once")}
                            TextButton(onClick={onDecision(approval.id,false)}){Text("Reject")}
                        }
                    }
                }
            }
        }
    }
}
