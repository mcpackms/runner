// app/src/main/java/com/packetcapture/xposed/hooks/SSLPinningBypass.kt
package com.packetcapture.xposed.hooks

import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SSLPinningBypass {
    
    companion object {
        private const val TAG = "PacketCapture-SSL"
    }
    
    fun hook(classLoader: ClassLoader) {
        try {
            bypassSSLContext(classLoader)
            bypassTrustManager(classLoader)
            bypassHostnameVerifier(classLoader)
            bypassOkHttpCertificatePinner(classLoader)
            bypassWebViewSSL(classLoader)
            XposedBridge.log("[$TAG] SSL Pinning 绕过已启用")
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] SSL 绕过失败: ${e.message}")
        }
    }
    
    private fun bypassSSLContext(classLoader: ClassLoader) {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            
            // Hook SSLContext.getDefault()
            XposedHelpers.findAndHookMethod(
                SSLContext::class.java,
                "getDefault",
                XC_MethodReplacement.returnConstant(sslContext)
            )
            
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] SSLContext 绕过失败: ${e.message}")
        }
    }
    
    private fun bypassTrustManager(classLoader: ClassLoader) {
        try {
            val x509Class = X509TrustManager::class.java
            
            XposedHelpers.findAndHookMethod(
                "javax.net.ssl.TrustManagerFactory",
                classLoader,
                "getTrustManagers",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Array<TrustManager> {
                        return arrayOf(object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                        })
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] TrustManager 绕过失败: ${e.message}")
        }
    }
    
    private fun bypassHostnameVerifier(classLoader: ClassLoader) {
        try {
            val verifierClass = classLoader.loadClass("javax.net.ssl.HostnameVerifier")
            
            XposedHelpers.findAndHookMethod(
                "javax.net.ssl.HttpsURLConnection",
                classLoader,
                "getDefaultHostnameVerifier",
                XC_MethodReplacement.returnConstant(
                    java.lang.reflect.Proxy.newProxyInstance(
                        classLoader,
                        arrayOf(verifierClass),
                        java.lang.reflect.InvocationHandler { _, method, _ ->
                            if (method.name == "verify") true else null
                        }
                    )
                )
            )
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] HostnameVerifier 绕过失败: ${e.message}")
        }
    }
    
    private fun bypassOkHttpCertificatePinner(classLoader: ClassLoader) {
        try {
            val pinnerClass = classLoader.loadClass("okhttp3.CertificatePinner")
            
            XposedHelpers.findAndHookMethod(
                pinnerClass,
                "check",
                String::class.java,
                List::class.java,
                XC_MethodReplacement.DO_NOTHING
            )
            
            // OkHttp 3.x 可能有不同的方法签名
            XposedHelpers.findAndHookMethod(
                pinnerClass,
                "check",
                String::class.java,
                java.security.cert.Certificate::class.java,
                XC_MethodReplacement.DO_NOTHING
            )
            
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] CertificatePinner 绕过失败: ${e.message}")
        }
    }
    
    private fun bypassWebViewSSL(classLoader: ClassLoader) {
        try {
            // Hook WebViewClient.onReceivedSslError
            val webViewClientClass = classLoader.loadClass("android.webkit.WebViewClient")
            
            XposedHelpers.findAndHookMethod(
                webViewClientClass,
                "onReceivedSslError",
                classLoader.loadClass("android.webkit.WebView"),
                classLoader.loadClass("android.webkit.SslErrorHandler"),
                classLoader.loadClass("android.net.http.SslError"),
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam) {
                        val handler = param.args[1]
                        XposedHelpers.callMethod(handler, "proceed")
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] WebView SSL 绕过失败: ${e.message}")
        }
    }
}
