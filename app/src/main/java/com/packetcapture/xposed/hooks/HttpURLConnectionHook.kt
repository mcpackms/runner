// app/src/main/java/com/packetcapture/xposed/hooks/HttpURLConnectionHook.kt
package com.packetcapture.xposed.hooks

import com.packetcapture.xposed.models.RequestResult
import com.packetcapture.xposed.models.ResponseResult
import com.packetcapture.xposed.utils.CachedInputStream
import com.packetcapture.xposed.utils.CapturingOutputStream
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.OutputStream
import java.net.HttpURLConnection

class HttpURLConnectionHook {
    
    companion object {
        private const val TAG = "PacketCapture-HttpURLConnection"
        private const val MAX_CAPTURE_SIZE = 5 * 1024 * 1024L // 5MB上限
    }
    
    fun hook(
        classLoader: ClassLoader,
        onRequest: (String, String, Map<String, String>, String) -> RequestResult,
        onResponse: (String, Int, Map<String, String>, String) -> ResponseResult
    ) {
        try {
            // Hook getOutputStream - 捕获请求体
            XposedHelpers.findAndHookMethod(
                HttpURLConnection::class.java,
                "getOutputStream",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val conn = param.thisObject as HttpURLConnection
                        val originalStream = param.result as? OutputStream ?: return
                        
                        val url = conn.url.toString()
                        val method = conn.requestMethod
                        
                        param.result = CapturingOutputStream(originalStream) { body ->
                            val headers = extractHeaders(conn)
                            onRequest(url, method, headers, body)
                        }
                    }
                }
            )
            
            // Hook getInputStream - 修改响应
            XposedHelpers.findAndHookMethod(
                HttpURLConnection::class.java,
                "getInputStream",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val conn = param.thisObject as HttpURLConnection
                        val originalStream = param.result as? java.io.InputStream ?: return
                        
                        val url = conn.url.toString()
                        val statusCode = try { conn.responseCode } catch (e: Exception) { 0 }
                        val headers = extractHeaders(conn)
                        
                        // 检查 Content-Length，跳过过大的响应
                        val contentLength = conn.contentLengthLong
                        if (contentLength > MAX_CAPTURE_SIZE) {
                            XposedBridge.log("[$TAG] 响应过大，跳过: $url ($contentLength bytes)")
                            return
                        }
                        
                        param.result = CachedInputStream(originalStream) { body ->
                            val result = onResponse(url, statusCode, headers, body)
                            when (result) {
                                is ResponseResult.Modified -> result.body
                                else -> body
                            }
                        }
                    }
                }
            )
            
            // Hook getErrorStream - 同样处理错误响应
            XposedHelpers.findAndHookMethod(
                HttpURLConnection::class.java,
                "getErrorStream",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val conn = param.thisObject as HttpURLConnection
                        val originalStream = param.result as? java.io.InputStream ?: return
                        
                        val url = conn.url.toString()
                        val statusCode = try { conn.responseCode } catch (e: Exception) { 0 }
                        val headers = extractHeaders(conn)
                        
                        param.result = CachedInputStream(originalStream) { body ->
                            val result = onResponse(url, statusCode, headers, body)
                            when (result) {
                                is ResponseResult.Modified -> result.body
                                else -> body
                            }
                        }
                    }
                }
            )
            
            XposedBridge.log("[$TAG] Hook 成功")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook 失败: ${e.message}")
        }
    }
    
    private fun extractHeaders(conn: HttpURLConnection): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            conn.headerFields?.forEach { (key, values) ->
                if (key != null && values.isNotEmpty()) {
                    map[key] = values.joinToString(", ")
                }
            }
        } catch (e: Exception) {
            // 忽略
        }
        return map
    }
}
