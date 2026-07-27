from flask import Flask, request, jsonify, render_template_string, send_from_directory
import logging
import time
import json
import os
from collections import deque
import socket

# 配置日志
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
logger = logging.getLogger(__name__)

app = Flask(__name__)

# ===================== 配置 =====================
UPLOAD_FOLDER = 'uploads/voice_help'          # 语音留言保存目录
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

# ===================== 数据存储 =====================
help_records = deque(maxlen=50)               # 求助记录
yolo_records = deque(maxlen=100)              # YOLO检测记录
voice_records = deque(maxlen=30)              # 语音留言记录（用于展示）

last_help_time = 0
last_yolo_time = 0
last_voice_time = 0

# ===================== 工具函数 =====================
def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
        return local_ip
    except:
        return "127.0.0.1"

# ===================== 1. 接收盲人求助定位 =====================
@app.route('/help-request', methods=['POST'])
def help_request():
    global last_help_time
    try:
        data = request.get_json()
        if not data:
            return jsonify({"status":"error","msg":"无数据"}), 400

        record = {
            "time": time.strftime("%Y-%m-%d %H:%M:%S"),
            "device": data.get("device_id", "未知设备"),
            "wake": data.get("wake_word", "我需要帮助"),
            "lat": data.get("location", {}).get("latitude", 0),
            "lng": data.get("location", {}).get("longitude", 0),
            "addr": data.get("location", {}).get("address", "未知地址")
        }
        help_records.append(record)
        last_help_time = time.time()
        logger.info(f"[求助] {record['device']} | {record['addr']}")
        return jsonify({"status":"success","msg":"求助已接收"}), 200
    except Exception as e:
        return jsonify({"status":"error","msg":str(e)}), 500

# ===================== 2. 接收求助语音留言（增加 transcript 字段） =====================
@app.route('/help-voice', methods=['POST'])
def help_voice():
    global last_voice_time
    try:
        if 'file' not in request.files:
            return jsonify({"status":"error","msg":"未找到音频文件"}), 400

        file = request.files['file']
        if file.filename == '':
            return jsonify({"status":"error","msg":"文件名为空"}), 400

        # 从表单中获取附加信息
        device_id = request.form.get('device_id', '未知设备')
        timestamp = request.form.get('timestamp', str(int(time.time()*1000)))
        token = request.form.get('token', '')
        transcript = request.form.get('transcript', '')  # 新增：接收识别文字

        # 生成唯一文件名：设备_时间戳_原始名
        safe_filename = f"{device_id}_{timestamp}_{file.filename}"
        save_path = os.path.join(UPLOAD_FOLDER, safe_filename)

        file.save(save_path)
        file_size = os.path.getsize(save_path)

        # 记录到内存
        record = {
            "time": time.strftime("%Y-%m-%d %H:%M:%S"),
            "device": device_id,
            "filename": safe_filename,
            "size": f"{file_size/1024:.1f} KB",
            "path": save_path,
            "transcript": transcript  # 保存识别文字
        }
        voice_records.append(record)
        last_voice_time = time.time()

        logger.info(f"[语音] {device_id} | 文件: {safe_filename} | 大小: {file_size/1024:.1f} KB | 文字: {transcript}")
        return jsonify({"status":"success","msg":"语音留言已保存"}), 200

    except Exception as e:
        logger.error(f"语音上传异常: {str(e)}")
        return jsonify({"status":"error","msg":str(e)}), 500

# ===================== 3. 接收YOLO目标检测 =====================
@app.route('/upload-results', methods=['POST'])
def upload_results():
    global last_yolo_time
    try:
        data = request.get_json()
        if not data:
            return jsonify({'error':'无数据'}), 400

        yolo_records.append(data)
        last_yolo_time = time.time()
        cnt = len(data.get('detections', []))
        logger.info(f"[YOLO] {cnt} 个目标")
        return jsonify({'status':'success','received':cnt}), 200
    except Exception as e:
        return jsonify({'error':str(e)}), 500

# ===================== 4. 仪表盘 =====================
@app.route('/')
def dashboard():
    # 求助数据
    help_cnt = len(help_records)
    last_help = help_records[-1] if help_records else None
    help_time = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(last_help_time)) if last_help_time else "无"

    # 语音数据
    voice_cnt = len(voice_records)
    last_voice = voice_records[-1] if voice_records else None
    voice_time = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(last_voice_time)) if last_voice_time else "无"

    # YOLO数据
    yolo_cnt = len(yolo_records)
    last_yolo = yolo_records[-1] if yolo_records else None
    yolo_time = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(last_yolo_time)) if last_yolo_time else "无"
    obj_cnt = len(last_yolo.get('detections', [])) if last_yolo else 0

    ip = get_local_ip()
    return render_template_string("""
<html>
<head>
    <title>导航+检测监控平台</title>
    <meta http-equiv="refresh" content="2">
    <style>
        body{font-family:微软雅黑;margin:30px;background:#f5f5f5}
        .box{max-width:900px;margin:0 auto}
        .card{background:white;padding:20px;border-radius:10px;margin:15px 0;box-shadow:0 2px 5px #00000010}
        .help{border-left:5px solid #f56c6c;background:#fff8f8}
        .voice{border-left:5px solid #ff9800;background:#fffaf5}
        .yolo{border-left:5px solid #4cd964;background:#f8fff8}
        .title{font-size:20px;margin-bottom:10px}
        .label{color:#666;font-weight:bold}
        .alert{color:#f56c6c;font-size:18px;font-weight:bold}
        .ok{color:#4cd964}
        .transcript{background:#f0f0f0;padding:10px;border-radius:5px;margin-top:10px;font-style:italic}
    </style>
</head>
<body>
    <div class="box">
        <h1 align="center">盲人导航 + YOLO检测</h1>

        <!-- 求助定位模块 -->
        <div class="card help">
            <div class="title">🚨 求助定位</div>
            <p><span class="label">总求助：</span>{{help_cnt}} 次</p>
            <p><span class="label">最后求助：</span>{{help_time}}</p>
            {% if last_help %}
                <p class="alert">⚠️ 收到求助！</p>
                <p>设备：{{last_help.device}}</p>
                <p>唤醒词：{{last_help.wake}}</p>
                <p>坐标：{{last_help.lat}} , {{last_help.lng}}</p>
                <p>地址：{{last_help.addr}}</p>
            {% else %}
                <p class="ok">✅ 暂无求助</p>
            {% endif %}
        </div>

        <!-- 语音留言模块 -->
        <div class="card voice">
            <div class="title">🎤 语音留言</div>
            <p><span class="label">总留言：</span>{{voice_cnt}} 条</p>
            <p><span class="label">最后留言：</span>{{voice_time}}</p>
            {% if last_voice %}
                <p>设备：{{last_voice.device}}</p>
                <p>文件：{{last_voice.filename}}</p>
                <p>大小：{{last_voice.size}}</p>
                {% if last_voice.transcript %}
                <div class="transcript">
                    <strong>📝 识别文字：</strong> {{last_voice.transcript}}
                </div>
                {% endif %}
                <p><a href="/uploads/voice_help/{{last_voice.filename}}" target="_blank">点击播放/下载</a></p>
            {% else %}
                <p class="ok">✅ 暂无语音留言</p>
            {% endif %}
        </div>

        <!-- YOLO检测模块 -->
        <div class="card yolo">
            <div class="title">📷 YOLO目标检测</div>
            <p><span class="label">总检测：</span>{{yolo_cnt}} 次</p>
            <p><span class="label">最后检测：</span>{{yolo_time}}</p>
            {% if last_yolo %}
                <p><span class="label">本次目标：</span>{{obj_cnt}} 个</p>
                <p><span class="label">设备：</span>{{last_yolo.get('device_id','未知')}}</p>
            {% else %}
                <p class="ok">✅ 等待检测数据</p>
            {% endif %}
        </div>

        <!-- 接口信息 -->
        <div class="card">
            <p><span class="label">求助接口：</span>http://{{ip}}:5000/help-request</p>
            <p><span class="label">语音接口：</span>http://{{ip}}:5000/help-voice</p>
            <p><span class="label">YOLO接口：</span>http://{{ip}}:5000/upload-results</p>
            <p><span class="label">语音文件目录：</span>/uploads/voice_help/</p>
        </div>
    </div>
</body>
</html>
    """,
    help_cnt=help_cnt, last_help=last_help, help_time=help_time,
    voice_cnt=voice_cnt, last_voice=last_voice, voice_time=voice_time,
    yolo_cnt=yolo_cnt, last_yolo=last_yolo, yolo_time=yolo_time, obj_cnt=obj_cnt,
    ip=ip
    )

# ===================== 静态文件访问（用于播放音频） =====================
@app.route('/uploads/voice_help/<filename>')
def serve_voice(filename):
    return send_from_directory(UPLOAD_FOLDER, filename)

# ===================== 启动 =====================
if __name__ == '__main__':
    ip = get_local_ip()
    print("="*60)
    print("服务器已启动｜支持求助定位、语音留言、YOLO检测")
    print(f"求助接口：http://{ip}:5000/help-request")
    print(f"语音接口：http://{ip}:5000/help-voice")
    print(f"YOLO接口：http://{ip}:5000/upload-results")
    print(f"监控页面：http://{ip}:5000")
    print(f"语音文件保存至：{os.path.abspath(UPLOAD_FOLDER)}")
    print("="*60)
    app.run(host='0.0.0.0', port=5000, debug=True, threaded=True)