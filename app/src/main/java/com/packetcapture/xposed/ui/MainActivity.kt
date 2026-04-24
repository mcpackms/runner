// app/src/main/java/com/packetcapture/xposed/ui/MainActivity.kt
package com.packetcapture.xposed.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.packetcapture.xposed.R
import com.packetcapture.xposed.models.ModificationRule
import com.packetcapture.xposed.service.ControlService
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class MainActivity : AppCompatActivity() {
    
    private lateinit var rulesAdapter: RulesAdapter
    private val rules = mutableListOf<ModificationRule>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        setupUI()
        startAutoRefresh()
    }
    
    private fun setupUI() {
        val rvRules = findViewById<RecyclerView>(R.id.rvRules)
        val fabAdd = findViewById<FloatingActionButton>(R.id.fabAddRule)
        val swEnabled = findViewById<SwitchCompat>(R.id.swEnabled)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        
        rulesAdapter = RulesAdapter(rules) { rule, action ->
            when (action) {
                "delete" -> deleteRule(rule)
                "edit" -> showEditRuleDialog(rule)
                "toggle" -> toggleRule(rule)
            }
        }
        
        rvRules.layoutManager = LinearLayoutManager(this)
        rvRules.adapter = rulesAdapter
        
        fabAdd.setOnClickListener { showAddRuleDialog() }
        
        swEnabled.setOnCheckedChangeListener { _, isChecked ->
            sendCommand(if (isChecked) "ENABLE" else "DISABLE") {
                runOnUiThread {
                    tvStatus.text = if (isChecked) "状态: 运行中" else "状态: 已停止"
                }
            }
        }
        
        // 初始状态
        sendCommand("STATUS") { response ->
            val status = Gson().fromJson(response.data, Map::class.java)
            runOnUiThread {
                swEnabled.isChecked = status["enabled"] as? Boolean ?: false
                tvStatus.text = if (swEnabled.isChecked) "状态: 运行中" else "状态: 已停止"
            }
        }
    }
    
    private fun startAutoRefresh() {
        refreshJob = scope.launch {
            while (isActive) {
                loadRules()
                delay(3000)
            }
        }
    }
    
    private fun loadRules() {
        sendCommand("GET_RULES") { response ->
            if (!response.success) return@sendCommand
            
            try {
                val type = object : TypeToken<List<ModificationRule>>() {}.type
                val loaded: List<ModificationRule> = Gson().fromJson(response.data, type)
                
                runOnUiThread {
                    rules.clear()
                    rules.addAll(loaded)
                    rulesAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private fun showAddRuleDialog() {
        showRuleDialog(null)
    }
    
    private fun showEditRuleDialog(rule: ModificationRule) {
        showRuleDialog(rule)
    }
    
    private fun showRuleDialog(existingRule: ModificationRule?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rule, null)
        
        val etName = dialogView.findViewById<EditText>(R.id.etRuleName)
        val etPattern = dialogView.findViewById<EditText>(R.id.etUrlPattern)
        val rgType = dialogView.findViewById<RadioGroup>(R.id.rgType)
        val etFind = dialogView.findViewById<EditText>(R.id.etFindText)
        val etReplace = dialogView.findViewById<EditText>(R.id.etReplaceText)
        val cbRegex = dialogView.findViewById<CheckBox>(R.id.cbRegex)
        val cbEnabled = dialogView.findViewById<CheckBox>(R.id.cbEnabled)
        
        // 填充现有数据
        existingRule?.let { rule ->
            etName.setText(rule.name)
            etPattern.setText(rule.urlPattern)
            rgType.check(if (rule.type == "request") R.id.rbRequest else R.id.rbResponse)
            etFind.setText(rule.findPattern)
            etReplace.setText(rule.replaceWith)
            cbRegex.isChecked = rule.isRegex
            cbEnabled.isChecked = rule.enabled
        }
        
        AlertDialog.Builder(this)
            .setTitle(if (existingRule == null) "添加规则" else "编辑规则")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val rule = ModificationRule(
                    id = existingRule?.id ?: System.currentTimeMillis().toString(),
                    name = etName.text.toString(),
                    urlPattern = etPattern.text.toString(),
                    type = if (rgType.checkedRadioButtonId == R.id.rbRequest) "request" else "response",
                    findPattern = etFind.text.toString(),
                    replaceWith = etReplace.text.toString(),
                    isRegex = cbRegex.isChecked,
                    enabled = cbEnabled.isChecked
                )
                
                if (existingRule == null) {
                    addRule(rule)
                } else {
                    updateRule(rule)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun addRule(rule: ModificationRule) {
        val json = Gson().toJson(rule)
        sendCommand("ADD_RULE:$json") {
            loadRules()
        }
    }
    
    private fun updateRule(rule: ModificationRule) {
        val json = Gson().toJson(rule)
        sendCommand("UPDATE_RULE:$json") {
            loadRules()
        }
    }
    
    private fun deleteRule(rule: ModificationRule) {
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除规则 \"${rule.name}\" 吗？")
            .setPositiveButton("删除") { _, _ ->
                sendCommand("REMOVE_RULE:${rule.id}") {
                    loadRules()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun toggleRule(rule: ModificationRule) {
        val updated = rule.copy(enabled = !rule.enabled)
        updateRule(updated)
    }
    
    private fun sendCommand(
        command: String,
        onResponse: ((CommandResponse) -> Unit)? = null
    ) {
        scope.launch {
            try {
                Socket("127.0.0.1", 28080).use { socket ->
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    
                    writer.println(command)
                    val responseJson = reader.readLine()
                    
                    val response = Gson().fromJson(responseJson, CommandResponse::class.java)
                    onResponse?.invoke(response)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    data class CommandResponse(
        val success: Boolean,
        val data: String = "",
        val message: String = ""
    )
    
    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
        scope.cancel()
    }
    
    // RecyclerView Adapter
    inner class RulesAdapter(
        private val items: List<ModificationRule>,
        private val onAction: (ModificationRule, String) -> Unit
    ) : RecyclerView.Adapter<RulesAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvRuleName)
            val tvPattern: TextView = view.findViewById(R.id.tvUrlPattern)
            val tvType: TextView = view.findViewById(R.id.tvRuleType)
            val swEnabled: SwitchCompat = view.findViewById(R.id.swRuleEnabled)
            val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_rule, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val rule = items[position]
            holder.tvName.text = rule.name
            holder.tvPattern.text = rule.urlPattern
            holder.tvType.text = if (rule.type == "request") "请求" else "响应"
            holder.swEnabled.isChecked = rule.enabled
            
            holder.swEnabled.setOnCheckedChangeListener { _, _ ->
                onAction(rule, "toggle")
            }
            holder.btnEdit.setOnClickListener { onAction(rule, "edit") }
            holder.btnDelete.setOnClickListener { onAction(rule, "delete") }
        }
        
        override fun getItemCount() = items.size
    }
}
