# 接口定义
 
## 前端发送给后端的信息
1. 当用户点击"Link"按钮后，发送```0 + " " + ipv6 + " " + port```
2. 当用户点击"UnLink"按钮后，发送```1```

## 后端发送给前端的信息
1. 当后端与服务器连接成功时，发送```0 + 包括ip地址等的数据包``
2. 当后端连接中，发送```1 + 下载速度等信息```
3. 当后端断开连接后，发送```2``·