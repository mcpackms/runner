// app/src/main/java/com/packetcapture/xposed/service/ControlService.kt
package com.packetcapture.xposed.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.packetcapture.xposed.models.ModificationRule
import de.robv.android.xposed.XposedBridge
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class ControlService : Service() {
    
    companion object {
        private const val TAG = "PacketCapture-Control"
        private const val PORT = 28080
        private const val RULES_FILE = "packet_rules.json"
        
        @JvmStatic
        val rules = CopyOnWriteArrayList<ModificationRule>()
        
        @JvmStatic
        var isEnabled = true
        
        @JvmStatic
        val requestRecords = ConcurrentHashMap<String, com.packetcapture.xposed.models.RequestInfo>()
        
        @JvmStatic
        fun loadRules(context: Context) {
            try {
                val file = File(context.filesDir, RULES_FILE)
                if (!file.exists()) return
                val json = file.readText()
                val type = object : TypeToken<List<ModificationRule>>() {}.type
                val loaded: List<ModificationRule> = Gson().fromJson(json, type)
                rules.clear()
                rules.addAll(loaded)
                XposedBridge.log("[$TAG] 已加载 ${rules.size} 条规则")
            } catch (e: Exception) {
                XposedBridge.log("[$TAG] 加载规则失败: ${e.message}")
            }
        }
        
        @JvmStatic
        fun saveRules(context: Context) {
            try {
                val file = File(context.filesDir, RULES_FILE)
                file.writeText(Gson().toJson(rules))
                XposedBridge.log("[$TAG] 已保存 ${rules.size} 条规则")
            } catch (e: Exception) {
                XposedBridge.log("[$TAG] 保存规则失败: ${e.message}")
            }
        }
    }
    
    private var serverSocket: ServerSocket? = null
    private var running = false
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            startServer()
        }
        return START_STICKY
    }
    
    private fun startServer() {
        thread(name = "PacketCapture-Server") {
            try {
                serverSocket = ServerSocket(PORT)
                XposedBridge.log("[$TAG] 控制服务已启动，端口: $PORT")
                
                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        thread { handleClient(client) }
                    } catch (e: Exception) {
                        if (running) {
                            XposedBridge.log("[$TAG] 接受连接失败: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("[$TAG] 服务启动失败: ${e.message}")
            }
        }
    }
    
    private fun handleClient(client: Socket) {
        var reader: BufferedReader? = null
        var writer: PrintWriter? = null
        
        try {
            reader = BufferedReader(InputStreamReader(client.getInputStream()))
            writer = PrintWriter(client.getOutputStream(), true)
            
            val command = reader.readLine() ?: return
            XposedBridge.log("[$TAG] 收到命令: $command")
            
            val response = processCommand(command)
            writer.println(Gson().toJson(response))
            
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] 处理客户端错误: ${e.message}")
        } finally {
            try {
                reader?.close()
                writer?.close()
                client.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
    
    private fun processCommand(command: String): CommandResponse {
        return try {
            when {
                command == "GET_RULES" -> {
                    CommandResponse(true, Gson().toJson(rules))
                }
                command == "GET_RECORDS" -> {
                    val records = requestRecords.values.sortedByDescending { it.timestamp }.take(100)
                    CommandResponse(true, Gson().toJson(records))
                }
                command == "CLEAR_RECORDS" -> {
                    requestRecords.clear()
                    CommandResponse(true, message = "记录已清空")
                }
                command.startsWith("ADD_RULE:") -> {
                    val json = command.substring(9)
                    val rule = Gson().fromJson(json, ModificationRule::class.java)
                    val newRule = rule.copy(id = System.currentTimeMillis().toString())
                    rules.add(newRule)
                    applicationContext?.let { saveRules(it) }
                    CommandResponse(true, message = "规则已添加")
                }
                command.startsWith("UPDATE_RULE:") -> {
                    val json = command.substring(12)
                    val updatedRule = Gson().fromJson(json, ModificationRule::class.java)
                    val index = rules.indexOfFirst { it.id == updatedRule.id }
                    if (index >= 0) {
                        rules[index] = updatedRule
                        applicationContext?.let { saveRules(it) }
                        CommandResponse(true, message = "规则已更新")
                    } else {
                        CommandResponse(false, message = "规则不存在")
                    }
                }
                command.startsWith("REMOVE_RULE:") -> {
                    val id = command.substring(12)
                    rules.removeAll { it.id == id }
                    applicationContext?.let { saveRules(it) }
                    CommandResponse(true, message = "规则已删除")
                }
                command == "ENABLE" -> {
                    isEnabled = true
                    CommandResponse(true, message = "已启用")
                }
                command == "DISABLE" -> {
                    isEnabled = false
                    CommandResponse(true, message = "已禁用")
                }
                command == "STATUS" -> {
                    val status = mapOf(
                        "enabled" to isEnabled,
                        "rulesCount" to rules.size,
                        "recordsCount" to requestRecords.size
                    )
                    CommandResponse(true, Gson().toJson(status))
                }
                else -> CommandResponse(false, message = "未知命令")
            }
        } catch (e: Exception) {
            CommandResponse(false, message = "处理失败: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // 忽略
        }
        super.onDestroy()
    }
    
    data class CommandResponse(
        val success: Boolean,
        val data: String = "",
        val message: String = ""
    )
}
