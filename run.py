#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import requests
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
import random
import sys
from datetime import datetime
import json

# 配置 - 针对 GitHub Actions 优化
BASE_URL = "http://jinshan2.resource.zhibaowan.com/downbag2/XT/2017/app/app-%s.apk"
DOWNLOAD_DIR = os.getenv('GITHUB_WORKSPACE', os.getcwd())
START_NUM = 7500
END_NUM = 7990
TOTAL_FILES = END_NUM - START_NUM + 10
MAX_WORKERS = 4

# 代理配置
PROXY_API_URL = "https://proxy.scdn.io/api/get_proxy.php?protocol=http&count=1"
PROXY_MAX_USES = 10
PROXY_TEST_URL = "http://httpbin.org/ip"
PROXY_TEST_TIMEOUT = 10

def get_timestamp():
    """获取带时间戳的日志前缀"""
    return f"[{datetime.now().strftime('%H:%M:%S')}]"


class ProxyManager:
    """代理管理器 - 轮换代理，每个代理使用指定次数"""
    
    def __init__(self):
        self.current_proxy = None
        self.use_count = 0
        self.proxy_list = []
        self.session = requests.Session()
        
    def get_proxies(self):
        """从API获取代理列表"""
        try:
            print(f"{get_timestamp()} 🔄 正在获取代理...")
            response = self.session.get(
                PROXY_API_URL,
                timeout=30,
                headers={
                    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
                }
            )
            response.raise_for_status()
            data = response.json()
            
            proxies = []
            
            if isinstance(data, list):
                proxies = data
            elif isinstance(data, dict):
                if 'data' in data and isinstance(data['data'], list):
                    proxies = [p.get('proxy') or p.get('ip') or p.get('address') for p in data['data']]
                elif 'proxy' in data:
                    proxies = [data['proxy']]
                elif 'proxies' in data:
                    proxies = data['proxies']
            
            proxies = [p for p in proxies if p]
            
            if proxies:
                print(f"{get_timestamp()} ✅ 获取到 {len(proxies)} 个代理")
                return proxies
            else:
                print(f"{get_timestamp()} ⚠️ API返回为空，使用直接连接")
                return []
                
        except Exception as e:
            print(f"{get_timestamp()} ❌ 获取代理失败: {e}，使用直接连接")
            return []
    
    def test_proxy(self, proxy):
        """测试代理是否可用"""
        try:
            proxies = {
                'http': f'http://{proxy}',
                'https': f'http://{proxy}'
            }
            resp = self.session.get(
                PROXY_TEST_URL,
                proxies=proxies,
                timeout=PROXY_TEST_TIMEOUT
            )
            if resp.status_code == 200:
                print(f"{get_timestamp()} ✅ 代理可用: {proxy}")
                return True
        except Exception as e:
            print(f"{get_timestamp()} ❌ 代理测试失败: {proxy} - {e}")
        return False
    
    def get_next_proxy(self):
        """获取下一个代理"""
        self.use_count = 0
        
        if not self.proxy_list:
            self.proxy_list = self.get_proxies()
            if not self.proxy_list:
                return None
        
        for proxy in self.proxy_list:
            if self.test_proxy(proxy):
                self.current_proxy = proxy
                return proxy
        
        print(f"{get_timestamp()} ⚠️ 所有代理测试失败，重新获取...")
        self.proxy_list = self.get_proxies()
        if not self.proxy_list:
            return None
        
        for proxy in self.proxy_list:
            if self.test_proxy(proxy):
                self.current_proxy = proxy
                return proxy
        
        return None
    
    def get_proxy(self):
        """获取当前使用的代理，如果达到使用次数限制则切换"""
        if self.use_count >= PROXY_MAX_USES:
            print(f"{get_timestamp()} 🔄 代理 {self.current_proxy} 已使用 {PROXY_MAX_USES} 次，切换新代理...")
            return self.get_next_proxy()
        
        if self.current_proxy is None:
            return self.get_next_proxy()
        
        return self.current_proxy
    
    def record_use(self):
        """记录一次代理使用"""
        self.use_count += 1
        print(f"{get_timestamp()} 📊 代理使用计数: {self.current_proxy} ({self.use_count}/{PROXY_MAX_USES})")

class APKDownloader:
    def __init__(self):
        self.download_dir = DOWNLOAD_DIR
        self.found_urls = []
        self.downloaded_files = []
        self.checked = 0
        self.proxy_manager = ProxyManager()
        
        os.makedirs(self.download_dir, exist_ok=True)
        print(f"{get_timestamp()} 目标目录: {self.download_dir}")
        print(f"{get_timestamp()} 当前工作目录: {os.getcwd()}")
        
        self.session = requests.Session()
        
        self.user_agents = [
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36',
            'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36',
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0'
        ]
        
        self.update_headers()
    
    def update_headers(self):
        """更新请求头，模拟真实浏览器"""
        self.session.headers.update({
            'User-Agent': random.choice(self.user_agents),
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Accept-Encoding': 'gzip, deflate',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1',
            'Cache-Control': 'max-age=0',
            'Referer': 'http://jinshan2.resource.zhibaowan.com/',
            'Origin': 'http://jinshan2.resource.zhibaowan.com'
        })
    
    def check_url(self, num):
        """检查URL是否有效 - 使用更真实的请求"""
        url = BASE_URL % num
        
        time.sleep(random.uniform(0.2, 0.5))
        
        self.session.headers.update({
            'User-Agent': random.choice(self.user_agents)
        })
        
        proxy = self.proxy_manager.get_proxy()
        proxies = None
        if proxy:
            proxies = {
                'http': f'http://{proxy}',
                'https': f'http://{proxy}'
            }
            self.proxy_manager.record_use()
        
        try:
            head_response = self.session.head(
                url,
                timeout=20,
                allow_redirects=True,
                proxies=proxies
            )
            
            # 检查状态码
            if head_response.status_code == 200:
                # 获取文件大小
                content_length = head_response.headers.get('content-length')
                size = int(content_length) if content_length else 0
                
                # 再做一个小的GET请求验证内容类型
                get_response = self.session.get(
                    url,
                    stream=True,
                    timeout=25,
                    headers={
                        'Range': 'bytes=0-1023'  # 只请求前1024字节
                    }
                )
                
                if get_response.status_code in [200, 206]:
                    # 读取前几个字节验证
                    content = get_response.raw.read(8)
                    get_response.close()
                    
                    # APK文件是ZIP格式，以PK开头
                    if content.startswith(b'PK'):
                        return {
                            'num': num,
                            'url': url,
                            'valid': True,
                            'size': size,
                            'method': 'HEAD+GET'
                        }
                
                return {'num': num, 'valid': False}
            
            elif head_response.status_code == 403:
                # 403错误 - 等待5秒并记录日志
                print(f"{get_timestamp()} ⚠️ 遇到403错误 (app-{num}.apk)，等待5秒后继续...")
                time.sleep(5)
                
                # 尝试直接GET少量数据
                get_response = self.session.get(
                    url,
                    stream=True,
                    timeout=30,
                    headers={
                        'Range': 'bytes=0-1023'
                    }
                )
                
                if get_response.status_code in [200, 206]:
                    content = get_response.raw.read(8)
                    get_response.close()
                    
                    if content.startswith(b'PK'):
                        # 获取完整文件大小
                        content_length = get_response.headers.get('content-length')
                        if not content_length:
                            content_range = get_response.headers.get('content-range', '')
                            if '/' in content_range:
                                content_length = content_range.split('/')[-1]
                        
                        size = int(content_length) if content_length and content_length.isdigit() else 0
                        
                        return {
                            'num': num,
                            'url': url,
                            'valid': True,
                            'size': size,
                            'method': 'GET-RANGE'
                        }
                
                return {'num': num, 'valid': False}
            
            else:
                return {'num': num, 'valid': False}
            
        except requests.exceptions.RequestException as e:
            # 忽略超时等错误
            return {'num': num, 'valid': False, 'error': str(e)}
    
    def download_file(self, num, url):
        """下载文件 - 带重试机制"""
        filename = f"app-{num}.apk"
        filepath = os.path.join(self.download_dir, filename)
        
        if os.path.exists(filepath):
            size = os.path.getsize(filepath)
            print(f"{get_timestamp()} ⏺ 已存在: {filename} ({size} bytes)")
            return True
        
        max_retries = 3
        for retry in range(max_retries):
            try:
                time.sleep(random.uniform(2, 4))
                
                self.session.headers.update({
                    'User-Agent': random.choice(self.user_agents)
                })
                
                proxy = self.proxy_manager.get_proxy()
                proxies = None
                if proxy:
                    proxies = {
                        'http': f'http://{proxy}',
                        'https': f'http://{proxy}'
                    }
                    self.proxy_manager.record_use()
                
                print(f"{get_timestamp()} ⬇️ 下载中: {filename} (尝试 {retry + 1}/{max_retries})")
                
                response = self.session.get(
                    url,
                    stream=True,
                    timeout=120,
                    allow_redirects=True,
                    proxies=proxies
                )
                
                if response.status_code == 200:
                    # 获取文件大小
                    total_size = int(response.headers.get('content-length', 0))
                    
                    # 写入文件
                    downloaded = 0
                    with open(filepath, 'wb') as f:
                        for chunk in response.iter_content(chunk_size=8192):
                            if chunk:
                                f.write(chunk)
                                downloaded += len(chunk)
                                if total_size > 0:
                                    percent = downloaded * 100 // total_size
                                    print(f"\r{get_timestamp()} 进度: {percent}% ({downloaded}/{total_size} bytes)", end='')
                    
                    print()  # 换行
                    
                    # 验证文件
                    if os.path.exists(filepath) and os.path.getsize(filepath) > 0:
                        final_size = os.path.getsize(filepath)
                        # 验证文件头
                        with open(filepath, 'rb') as f:
                            header = f.read(8)
                            if header.startswith(b'PK'):
                                print(f"{get_timestamp()} ✅ 下载成功: {filename} ({final_size} bytes)")
                                return True
                            else:
                                print(f"{get_timestamp()} ❌ 文件损坏: {filename} - 不是有效的APK")
                                os.remove(filepath)
                                return False
                    else:
                        if os.path.exists(filepath):
                            os.remove(filepath)
                        print(f"{get_timestamp()} ❌ 下载失败: {filename} - 文件不完整")
                        
                elif response.status_code == 403 and retry < max_retries - 1:
                    print(f"{get_timestamp()} ⚠️ 遇到403，等待5秒后重试...")
                    time.sleep(5)  # 遇到403等待5秒
                    continue
                else:
                    print(f"{get_timestamp()} ❌ 下载失败: {filename} - HTTP {response.status_code}")
                    return False
                    
            except Exception as e:
                if retry < max_retries - 1:
                    print(f"{get_timestamp()} ⚠️ 下载出错: {str(e)}，等待3秒后重试...")
                    time.sleep(3)
                else:
                    print(f"{get_timestamp()} ❌ 下载失败: {filename} - {str(e)}")
                    if os.path.exists(filepath):
                        os.remove(filepath)
                    return False
        
        return False
    
    def run(self):
        """主运行函数"""
        print(f"{get_timestamp()} " + "=" * 60)
        print(f"{get_timestamp()} APK批量下载工具 (GitHub Actions优化版)")
        print(f"{get_timestamp()} 扫描范围: {START_NUM} 到 {END_NUM}")
        print(f"{get_timestamp()} 目标目录: {self.download_dir}")
        print(f"{get_timestamp()} 并发数: {MAX_WORKERS}")
        print(f"{get_timestamp()} 总文件数: {TOTAL_FILES}")
        print(f"{get_timestamp()} " + "=" * 60)
        
        start_time = time.time()
        
        # 生成数字列表 (6000-9999)
        numbers = [f"{i:04d}" for i in range(START_NUM, END_NUM + 1)]
        
        print(f"{get_timestamp()} \n第一阶段: 扫描有效链接...")
        print(f"{get_timestamp()} (GitHub Actions环境自动调整延迟参数)")
        
        # 使用线程池扫描
        with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
            # 提交所有任务
            futures = {executor.submit(self.check_url, num): num for num in numbers}
            
            # 处理结果
            for i, future in enumerate(as_completed(futures), 1):
                result = future.result()
                self.checked += 1
                
                # 显示进度
                if i % 20 == 0:  # 减少日志输出频率
                    percent = i * 100 // TOTAL_FILES
                    found_count = len(self.found_urls)
                    print(f"{get_timestamp()} 扫描进度: {percent}% ({i}/{TOTAL_FILES}) - 已发现: {found_count}")
                
                # 如果有效，记录
                if result['valid']:
                    self.found_urls.append(result)
                    method = result.get('method', 'unknown')
                    print(f"{get_timestamp()} ✨ 发现有效: app-{result['num']}.apk ({result['size']} bytes) [方法:{method}]")
        
        # 扫描完成
        scan_time = time.time() - start_time
        print(f"{get_timestamp()} \n扫描完成！耗时: {scan_time:.1f}秒")
        print(f"{get_timestamp()} 共发现 {len(self.found_urls)} 个有效APK")
        
        if not self.found_urls:
            print(f"{get_timestamp()} 没有找到任何有效APK文件")
            return
        
        # 第二阶段: 下载文件
        print(f"{get_timestamp()} \n第二阶段: 开始下载...")
        
        # 先检查已存在的文件
        existing = 0
        to_download = []
        
        for item in self.found_urls:
            filename = f"app-{item['num']}.apk"
            filepath = os.path.join(self.download_dir, filename)
            
            if os.path.exists(filepath):
                size = os.path.getsize(filepath)
                print(f"{get_timestamp()} ⏺ 已存在: {filename} ({size} bytes)")
                existing += 1
                self.downloaded_files.append(filename)
            else:
                to_download.append(item)
        
        print(f"{get_timestamp()} 待下载: {len(to_download)} 个, 已存在: {existing} 个")
        
        # 下载新文件
        if to_download:
            print(f"{get_timestamp()} \n开始下载新文件...")
            for i, item in enumerate(to_download, 1):
                print(f"{get_timestamp()} [{i}/{len(to_download)}] ", end='')
                success = self.download_file(item['num'], item['url'])
                if success:
                    self.downloaded_files.append(f"app-{item['num']}.apk")
        
        # 最终统计
        end_time = time.time()
        total_time = end_time - start_time
        
        print(f"{get_timestamp()} " + "\n" + "=" * 60)
        print(f"{get_timestamp()} 下载完成！")
        print(f"{get_timestamp()} 总耗时: {total_time:.1f}秒")
        print(f"{get_timestamp()} 扫描文件数: {self.checked}")
        print(f"{get_timestamp()} 发现有效链接: {len(self.found_urls)}")
        print(f"{get_timestamp()} 成功下载/已存在: {len(self.downloaded_files)}")
        print(f"{get_timestamp()} " + "=" * 60)
        
        # 列出所有APK文件
        if self.downloaded_files:
            print(f"{get_timestamp()} \n当前目录中的APK文件:")
            all_apks = sorted([f for f in os.listdir(self.download_dir) 
                              if f.startswith('app-') and f.endswith('.apk')])
            for f in all_apks:
                filepath = os.path.join(self.download_dir, f)
                size = os.path.getsize(filepath)
                print(f"{get_timestamp()}   • {f} ({size:,} bytes)")
        else:
            print(f"{get_timestamp()} \n目录中没有APK文件")

def main():
    """主函数"""
    print(f"{get_timestamp()} APK下载器启动...")
    print(f"{get_timestamp()} Python版本: {sys.version}")
    print(f"{get_timestamp()} GitHub Actions环境: {'GITHUB_ACTIONS' in os.environ}")
    
    # 检查存储权限
    test_file = os.path.join(DOWNLOAD_DIR, ".write_test")
    try:
        with open(test_file, 'w') as f:
            f.write('test')
        os.remove(test_file)
        print(f"{get_timestamp()} ✓ 存储权限正常")
    except (IOError, OSError) as e:
        print(f"{get_timestamp()} ✗ 错误：无法写入 {DOWNLOAD_DIR}")
        print(f"{get_timestamp()} 错误信息: {e}")
        print(f"{get_timestamp()} 请检查存储权限")
        return
    
    # 运行下载器
    downloader = APKDownloader()
    downloader.run()

if __name__ == "__main__":
    main()
