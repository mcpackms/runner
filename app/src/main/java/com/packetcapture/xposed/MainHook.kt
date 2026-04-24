// app/src/main/java/com/packetcapture/xposed/MainHook.kt
package com.packetcapture.xposed

import android.app.Application
import android.content.Context
import com.packetcapture.xposed.hooks.*
import com.packetcapture.xposed.models.RequestResult
import com.packetcapture.xposed.models.ResponseResult
import com.packetcapture.xposed.service.ControlService
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicInteger

class MainHook : IXposedHookLoadPackage {
    
    companion object {
        private const val TAG = "PacketCapture"
        private val requestCounter = AtomicInteger(0)
    }
    
    private val hooks = listOf(
        OkHttpHook(),
        HttpURLConnectionHook(),
        ApacheHttpHook()
    )
    
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 只 Hook 目标应用
        val targetPackage = "com.example.targetapp" // 修改为你的目标包名
        if (lpparam.packageName != targetPackage && targetPackage != "*") {
            return
        }
        
        XposedBridge.log("[$TAG] 开始 Hook 应用: ${lpparam.packageName}")
        
        // Hook Application.onCreate 以获取 Context
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as Application
                    val context = app.applicationContext
                    
                    // 加载规则
                    ControlService.loadRules(context)
                    
                    // 启动控制服务
                    val intent = Intent(context, ControlService::class.java)
                    context.startService(intent)
                    
                    // 应用所有网络 Hook
                    applyHooks(lpparam.classLoader)
                    
                    // 启用 SSL Pinning 绕过
                    SSLPinningBypass().hook(lpparam.classLoader)
                }
            }
        )
    }
    
    private fun applyHooks(classLoader: ClassLoader) {
        hooks.forEach { hook ->
            try {
                hook.hook(classLoader, ::onRequest, ::onResponse)
                XposedBridge.log("[$TAG] Hook 成功: ${hook.javaClass.simpleName}")
            } catch (e: Throwable) {
                XposedBridge.log("[$TAG] Hook 失败: ${hook.javaClass.simpleName} - ${e.message}")
            }
        }
    }
    
    private fun onRequest(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String
    ): RequestResult {
        if (!ControlService.isEnabled) return RequestResult.Continue
        
        val requestId = System.currentTimeMillis().toString() + "_" + requestCounter.incrementAndGet()
        
        XposedBridge.log("[$TAG] 📥 [$requestId] $method $url")
        if (body.isNotEmpty()) {
            XposedBridge.log("[$TAG] 📄 Body: ${body.take(500)}")
        }
        
        // 记录请求
        val requestInfo = com.packetcapture.xposed.models.RequestInfo(
            id = requestId,
            url = url,
            method = method,
            headers = headers,
            body = body
        )
        ControlService.requestRecords[requestId] = requestInfo
        
        // 检查规则
        val matchedRule = ControlService.rules.find { rule ->
            rule.enabled && rule.type == "request" && url.contains(rule.urlPattern)
        }
        
        return if (matchedRule != null) {
            XposedBridge.log("[$TAG] ✏️ 应用请求规则: ${matchedRule.name}")
            val newBody = if (matchedRule.isRegex) {
                body.replace(Regex(matchedRule.findPattern), matchedRule.replaceWith)
            } else {
                body.replace(matchedRule.findPattern, matchedRule.replaceWith)
            }
            RequestResult.Modified(newBody)
        } else {
            RequestResult.Continue
        }
    }
    
    private fun onResponse(
        url: String,
        statusCode: Int,
        headers: Map<String, String>,
        body: String
    ): ResponseResult {
        if (!ControlService.isEnabled) return ResponseResult.Continue
        
        XposedBridge.log("[$TAG] 📤 响应: $statusCode $url")
        
        // 检查规则
        val matchedRule = ControlService.rules.find { rule ->
            rule.enabled && rule.type == "response" && url.contains(rule.urlPattern)
        }
        
        return if (matchedRule != null) {
            XposedBridge.log("[$TAG] ✏️ 应用响应规则: ${matchedRule.name}")
            val newBody = if (matchedRule.isRegex) {
                body.replace(Regex(matchedRule.findPattern), matchedRule.replaceWith)
            } else {
                body.replace(matchedRule.findPattern, matchedRule.replaceWith)
            }
            ResponseResult.Modified(newBody)
        } else {
            ResponseResult.Continue
        }
    }
}
