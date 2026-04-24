// app/src/main/java/com/packetcapture/xposed/hooks/ApacheHttpHook.kt
package com.packetcapture.xposed.hooks

import com.packetcapture.xposed.models.RequestResult
import com.packetcapture.xposed.models.ResponseResult
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

class ApacheHttpHook {
    
    companion object {
        private const val TAG = "PacketCapture-Apache"
        private val requestUrlThreadLocal = ThreadLocal<String>()
    }
    
    fun hook(
        classLoader: ClassLoader,
        onRequest: (String, String, Map<String, String>, String) -> RequestResult,
        onResponse: (String, Int, Map<String, String>, String) -> ResponseResult
    ) {
        try {
            val httpClientClass = classLoader.loadClass("org.apache.http.client.HttpClient")
            
            // Hook 所有 execute 重载
            val methods = httpClientClass.declaredMethods.filter { 
                it.name == "execute" && it.parameterTypes.isNotEmpty() 
            }
            
            methods.forEach { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handleBeforeExecute(param, classLoader, onRequest)
                    }
                    
                    override fun afterHookedMethod(param: MethodHookParam) {
                        handleAfterExecute(param, classLoader, onResponse)
                    }
                })
            }
            
            XposedBridge.log("[$TAG] Hook 了 ${methods.size} 个 execute 方法")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook 失败: ${e.message}")
        }
    }
    
    private fun handleBeforeExecute(
        param: MethodHookParam,
        classLoader: ClassLoader,
        onRequest: (String, String, Map<String, String>, String) -> RequestResult
    ) {
        try {
            val args = param.args
            var request: Any? = null
            
            // 找到 HttpUriRequest 参数
            for (arg in args) {
                if (arg != null && arg.javaClass.interfaces.any { 
                    it.name == "org.apache.http.client.methods.HttpUriRequest" 
                }) {
                    request = arg
                    break
                }
            }
            
            if (request == null) return
            
            val url = XposedHelpers.callMethod(request, "getURI").toString()
            val method = XposedHelpers.callMethod(request, "getMethod") as String
            
            requestUrlThreadLocal.set(url)
            
            // 获取请求头
            val headers = extractApacheHeaders(request)
            
            // 获取请求体
            var body = ""
            try {
                val entity = XposedHelpers.callMethod(request, "getEntity")
                if (entity != null) {
                    val entityClass = classLoader.loadClass("org.apache.http.util.EntityUtils")
                    body = XposedHelpers.callStaticMethod(entityClass, "toString", entity) as String
                }
            } catch (e: Exception) {
                // 可能没有实体
            }
            
            val result = onRequest(url, method, headers, body)
            
            if (result is RequestResult.Modified && body != result.body) {
                // 替换请求实体
                try {
                    val stringEntityClass = classLoader.loadClass("org.apache.http.entity.StringEntity")
                    val newEntity = XposedHelpers.newInstance(
                        stringEntityClass,
                        result.body,
                        "UTF-8"
                    )
                    XposedHelpers.callMethod(request, "setEntity", newEntity)
                } catch (e: Exception) {
                    XposedBridge.log("[$TAG] 替换请求体失败: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] beforeHookedMethod 错误: ${e.message}")
        }
    }
    
    private fun handleAfterExecute(
        param: MethodHookParam,
        classLoader: ClassLoader,
        onResponse: (String, Int, Map<String, String>, String) -> ResponseResult
    ) {
        try {
            val response = param.result ?: return
            val url = requestUrlThreadLocal.get() ?: "unknown"
            requestUrlThreadLocal.remove()
            
            // 获取状态码
            val statusLine = XposedHelpers.callMethod(response, "getStatusLine")
            val statusCode = XposedHelpers.callMethod(statusLine, "getStatusCode") as Int
            
            // 获取响应头
            val headers = try {
                val allHeaders = XposedHelpers.callMethod(response, "getAllHeaders") as Array<*>
                allHeaders.mapNotNull { header ->
                    val name = XposedHelpers.callMethod(header, "getName") as String
                    val value = XposedHelpers.callMethod(header, "getValue") as String
                    name to value
                }.toMap()
            } catch (e: Exception) {
                emptyMap<String, String>()
            }
            
            // 获取响应体
            var body = ""
            try {
                val entity = XposedHelpers.callMethod(response, "getEntity")
                if (entity != null) {
                    // 先复制一份，因为 EntityUtils.toString 会消费实体
                    val entityUtils = classLoader.loadClass("org.apache.http.util.EntityUtils")
                    body = XposedHelpers.callStaticMethod(entityUtils, "toString", entity, "UTF-8") as String
                    
                    // 替换实体
                    val result = onResponse(url, statusCode, headers, body)
                    
                    if (result is ResponseResult.Modified) {
                        val stringEntityClass = classLoader.loadClass("org.apache.http.entity.StringEntity")
                        val contentType = try {
                            XposedHelpers.callMethod(entity, "getContentType")
                        } catch (e: Exception) { null }
                        
                        val newEntity = if (contentType != null) {
                            val ctValue = XposedHelpers.callMethod(contentType, "getValue") as String
                            XposedHelpers.newInstance(stringEntityClass, result.body, ctValue)
                        } else {
                            XposedHelpers.newInstance(stringEntityClass, result.body, "application/json")
                        }
                        
                        XposedHelpers.callMethod(response, "setEntity", newEntity)
                    }
                }
            } catch (e: Exception) {
                XposedBridge.log("[$TAG] 处理响应体失败: ${e.message}")
            }
            
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] afterHookedMethod 错误: ${e.message}")
        }
    }
    
    private fun extractApacheHeaders(request: Any): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val allHeaders = XposedHelpers.callMethod(request, "getAllHeaders") as Array<*>
            allHeaders.forEach { header ->
                val name = XposedHelpers.callMethod(header, "getName") as String
                val value = XposedHelpers.callMethod(header, "getValue") as String
                map[name] = value
            }
        } catch (e: Exception) {
            // 忽略
        }
        return map
    }
}
