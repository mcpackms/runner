// app/src/main/java/com/packetcapture/xposed/hooks/OkHttpHook.kt
package com.packetcapture.xposed.hooks

import com.packetcapture.xposed.models.RequestResult
import com.packetcapture.xposed.models.ResponseResult
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Proxy

class OkHttpHook {
    
    companion object {
        private const val TAG = "PacketCapture-OkHttp"
    }
    
    fun hook(
        classLoader: ClassLoader,
        onRequest: (String, String, Map<String, String>, String) -> RequestResult,
        onResponse: (String, Int, Map<String, String>, String) -> ResponseResult
    ) {
        try {
            val builderClass = classLoader.loadClass("okhttp3.OkHttpClient\$Builder")
            
            XposedHelpers.findAndHookMethod(
                builderClass,
                "build",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val builder = param.thisObject
                        addInterceptor(builder, classLoader, onRequest, onResponse)
                    }
                }
            )
            
            XposedBridge.log("[$TAG] OkHttp Builder.build() Hook 成功")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] Hook 失败: ${e.message}")
        }
    }
    
    private fun addInterceptor(
        builder: Any,
        classLoader: ClassLoader,
        onRequest: (String, String, Map<String, String>, String) -> RequestResult,
        onResponse: (String, Int, Map<String, String>, String) -> ResponseResult
    ) {
        try {
            val interceptorClass = classLoader.loadClass("okhttp3.Interceptor")
            
            val proxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(interceptorClass),
                OkHttpInterceptorHandler(classLoader, onRequest, onResponse)
            )
            
            XposedHelpers.callMethod(builder, "addInterceptor", proxy)
            
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] 添加拦截器失败: ${e.message}")
        }
    }
    
    private class OkHttpInterceptorHandler(
        private val classLoader: ClassLoader,
        private val onRequest: (String, String, Map<String, String>, String) -> RequestResult,
        private val onResponse: (String, Int, Map<String, String>, String) -> ResponseResult
    ) : java.lang.reflect.InvocationHandler {
        
        override fun invoke(proxy: Any?, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
            if (method.name != "intercept") {
                return null
            }
            
            val chain = args?.get(0) ?: return null
            val chainClass = classLoader.loadClass("okhttp3.Interceptor\$Chain")
            
            // 获取原始请求
            val request = XposedHelpers.callMethod(chain, "request")
            val requestClass = classLoader.loadClass("okhttp3.Request")
            val urlObj = XposedHelpers.callMethod(request, "url")
            val url = urlObj.toString()
            val methodName = XposedHelpers.callMethod(request, "method") as String
            
            // 获取请求头
            val headers = XposedHelpers.callMethod(request, "headers")
            val headersMap = extractHeaders(headers)
            
            // 获取请求体
            val bodyStr = extractRequestBody(request, classLoader)
            
            // 应用请求规则
            val requestResult = onRequest(url, methodName, headersMap, bodyStr)
            
            val finalRequest = when (requestResult) {
                is RequestResult.Modified -> {
                    buildModifiedRequest(request, requestResult, classLoader)
                }
                else -> request
            }
            
            // 执行请求
            val response = XposedHelpers.callMethod(chain, "proceed", finalRequest)
            
            // 处理响应
            return processResponse(response, url, classLoader)
        }
        
        private fun extractHeaders(headers: Any): Map<String, String> {
            val map = mutableMapOf<String, String>()
            try {
                val namesMethod = headers.javaClass.getMethod("names")
                val names = namesMethod.invoke(headers) as Set<String>
                for (name in names) {
                    val valueMethod = headers.javaClass.getMethod("get", String::class.java)
                    val value = valueMethod.invoke(headers, name) as? String
                    if (value != null) map[name] = value
                }
            } catch (e: Exception) {
                // 忽略
            }
            return map
        }
        
        private fun extractRequestBody(request: Any, classLoader: ClassLoader): String {
            return try {
                val body = XposedHelpers.callMethod(request, "body") ?: return ""
                val bufferClass = classLoader.loadClass("okio.Buffer")
                val buffer = XposedHelpers.newInstance(bufferClass)
                XposedHelpers.callMethod(body, "writeTo", buffer)
                buffer.toString()
            } catch (e: Exception) {
                ""
            }
        }
        
        private fun buildModifiedRequest(
            originalRequest: Any,
            result: RequestResult.Modified,
            classLoader: ClassLoader
        ): Any {
            val builder = XposedHelpers.callMethod(originalRequest, "newBuilder")
            
            // 如果有请求体，替换
            if (result.body.isNotEmpty()) {
                val mediaTypeClass = classLoader.loadClass("okhttp3.MediaType")
                val requestBodyClass = classLoader.loadClass("okhttp3.RequestBody")
                
                val contentType = XposedHelpers.callMethod(originalRequest, "body")?.let {
                    XposedHelpers.callMethod(it, "contentType")
                }
                
                val newBody = if (contentType != null) {
                    XposedHelpers.callStaticMethod(
                        requestBodyClass,
                        "create",
                        contentType,
                        result.body
                    )
                } else {
                    val jsonMediaType = XposedHelpers.callStaticMethod(
                        mediaTypeClass,
                        "parse",
                        "application/json; charset=utf-8"
                    )
                    XposedHelpers.callStaticMethod(
                        requestBodyClass,
                        "create",
                        jsonMediaType,
                        result.body
                    )
                }
                
                val method = XposedHelpers.callMethod(originalRequest, "method") as String
                XposedHelpers.callMethod(builder, "method", method, newBody)
            }
            
            // 更新请求头
            result.headers?.forEach { (key, value) ->
                XposedHelpers.callMethod(builder, "header", key, value)
            }
            
            return XposedHelpers.callMethod(builder, "build")
        }
        
        private fun processResponse(response: Any, url: String, classLoader: ClassLoader): Any {
            try {
                val code = XposedHelpers.callMethod(response, "code") as Int
                val headers = XposedHelpers.callMethod(response, "headers")
                val headersMap = extractHeaders(headers)
                
                // 读取响应体
                val body = XposedHelpers.callMethod(response, "body")
                val bodyString = if (body != null) {
                    val source = XposedHelpers.callMethod(body, "source")
                    XposedHelpers.callMethod(source, "request", Long.MAX_VALUE)
                    val buffer = XposedHelpers.callMethod(source, "buffer")
                    XposedHelpers.callMethod(buffer, "clone").toString()
                } else ""
                
                // 应用响应规则
                val responseResult = onResponse(url, code, headersMap, bodyString)
                
                return when (responseResult) {
                    is ResponseResult.Modified -> {
                        buildModifiedResponse(response, responseResult, classLoader)
                    }
                    else -> response
                }
                
            } catch (e: Exception) {
                XposedBridge.log("[$TAG] 处理响应失败: ${e.message}")
                return response
            }
        }
        
        private fun buildModifiedResponse(
            originalResponse: Any,
            result: ResponseResult.Modified,
            classLoader: ClassLoader
        ): Any {
            val builder = XposedHelpers.callMethod(originalResponse, "newBuilder")
            
            // 创建新的 ResponseBody
            val responseBodyClass = classLoader.loadClass("okhttp3.ResponseBody")
            val mediaTypeClass = classLoader.loadClass("okhttp3.MediaType")
            
            val originalBody = XposedHelpers.callMethod(originalResponse, "body")
            val contentType = originalBody?.let {
                XposedHelpers.callMethod(it, "contentType")
            }
            
            val newBody = if (contentType != null) {
                XposedHelpers.callStaticMethod(
                    responseBodyClass,
                    "create",
                    contentType,
                    result.body
                )
            } else {
                val jsonMediaType = XposedHelpers.callStaticMethod(
                    mediaTypeClass,
                    "parse",
                    "application/json; charset=utf-8"
                )
                XposedHelpers.callStaticMethod(
                    responseBodyClass,
                    "create",
                    jsonMediaType,
                    result.body
                )
            }
            
            XposedHelpers.callMethod(builder, "body", newBody)
            
            // 更新响应头
            result.headers?.forEach { (key, value) ->
                XposedHelpers.callMethod(builder, "header", key, value)
            }
            
            return XposedHelpers.callMethod(builder, "build")
        }
    }
}
