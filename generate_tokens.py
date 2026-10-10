"""
从 tb_user.csv 读取手机号，批量登录获取 token，保存到 tokens.txt
原理：发验证码 → 从 Redis 读验证码 → 用验证码登录
使用前请确保：
1. Spring Boot 应用已启动 (localhost:8080)
2. MySQL、Redis、RabbitMQ 已启动
3. 安装 redis 库: pip install redis
"""
import requests
import csv
import time
import redis

BASE_URL = "http://localhost:8080"
CSV_PATH = r"D:\dianping-master\tb_user.csv"
OUTPUT_PATH = r"D:\dianping-master\tokens.txt"

# Redis 连接（和你项目配置一致）
r = redis.Redis(host="127.0.0.1", port=6379, db=0, decode_responses=True)

tokens = []
phones = []

# 1. 从 CSV 读取手机号
with open(CSV_PATH, "r", encoding="utf-8") as f:
    reader = csv.reader(f)
    for row in reader:
        phone = row[1].strip()
        if phone and phone.isdigit() and len(phone) == 11:
            phones.append(phone)

print(f"读取到 {len(phones)} 个手机号")

# 2. 逐个发送验证码 → 从 Redis 读取 → 登录
print("正在批量发送验证码并登录...")
for i, phone in enumerate(phones):
    try:
        # 发送验证码（会写入 Redis: login:code:{phone}）
        requests.post(f"{BASE_URL}/user/code",
            json={"phone": phone}, timeout=5)

        # 从 Redis 读取验证码
        code = r.get(f"login:code:{phone}")
        if not code:
            print(f"  跳过: {phone} - Redis 中没有验证码")
            continue

        # 用验证码登录
        resp = requests.post(f"{BASE_URL}/user/login",
            json={"phone": phone, "code": code}, timeout=5)
        data = resp.json()
        token = data.get("data", {}).get("token", "")
        if token:
            tokens.append(token)
        else:
            if (i + 1) % 50 == 0:
                print(f"  登录失败: {phone}, 响应: {data}")
    except Exception as e:
        print(f"  异常: {phone}, {e}")

    if (i + 1) % 50 == 0:
        print(f"  进度: {i+1}/{len(phones)}, 成功 {len(tokens)} 个")

# 3. 保存 token 到文件
with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
    for t in tokens:
        f.write(t + "\n")

print(f"\n完成！共生成 {len(tokens)} 个 token，已保存到 {OUTPUT_PATH}")
print(f"成功率: {len(tokens)}/{len(phones)} = {len(tokens)*100//max(len(phones),1)}%")
