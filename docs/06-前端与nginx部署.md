# 前端与 nginx 部署

> 说明：前端是演示级实现，面试重心不在样式，而在“前后端分离如何联调、跨域怎么处理、静态资源怎么部署”。

## 1. 前端结构（Vue 3 + Vite）

```
frontend/
├── src/
│   ├── api.js          # axios 封装：自动带 token、统一拆 Result、失败弹窗
│   ├── router.js       # history 模式，4 个页面
│   ├── App.vue         # 顶部导航 + 登录态
│   └── views/
│       ├── Login.vue        # 手机号 + 验证码（验证码看后端日志）
│       ├── Home.vue         # 分类 + 场馆列表 + 浏览器定位按距离排序
│       ├── VenueDetail.vue  # 场馆信息 + UV + 在售演出 + 抢票
│       └── Orders.vue       # 我的订单，5 秒轮询观察延迟关单
├── vite.config.js      # 开发模式 /api 代理到 8081
└── nginx/              # 生产模式 nginx 配置与启停脚本
```

关键点：

- `api.js` 统一处理 `{success, data, errorMsg}`，业务代码只处理成功路径
- token 存 localStorage，请求头 `authorization`（和后端拦截器约定一致）
- 订单页轮询是为了可视化演示“未支付 60 秒后自动取消”，不是生产做法（生产用 WebSocket/推送）

## 2. 为什么用 nginx 反代而不是后端开 CORS

| 方案 | 问题 |
| --- | --- |
| 后端 `@CrossOrigin` / CORS 配置 | 预检请求增加延迟；带凭证时 `Access-Control-Allow-Origin` 不能为 `*`；生产通常也不允许任意来源 |
| **nginx 同源反代（选用）** | 前端请求 `/api/**`，nginx 转发到 8081，浏览器看来同源，无跨域；和生产部署方式一致 |

配置位于 `frontend/nginx/conf/nginx.conf`：`/` 指向 `dist`（`try_files` 回退 `index.html` 支持 history 路由），`/api/` 反代到 `http://127.0.0.1:8081/`（末尾斜杠会剥掉 `/api` 前缀，这是最容易踩的坑）。

## 3. 运行方式

开发模式（热更新，Vite 自带代理）：

```bash
cd frontend && npm run dev     # http://localhost:5173
```

生产模式（nginx 托管构建产物）：

```bash
cd frontend && npm run build
frontend/nginx/start.bat       # http://127.0.0.1:8080
```

nginx 以项目目录为 prefix 启动（`-p`），不修改 nginx 安装目录的配置，多个项目可以各自带配置共存。

## 4. 部署拓扑（本项目实际）

```
浏览器 → nginx:8080 ─┬─ /        → frontend/dist
                     └─ /api/**  → backend:8081
backend:8081 → MySQL 本机 / Redis 虚拟机 / RabbitMQ 虚拟机(docker, vhost show-ticket)
```

## 5. 多项目共存的三种方式

| 方式 | 访问形式 | 前端改动 | 适用 |
| --- | --- | --- | --- |
| 多端口 | :8080 / :8090 | 无 | 本地最简单 |
| 多域名（hosts） | show-ticket.local | 无 | 最接近生产 |
| 同域名多路径 | /show-ticket/ | 要配 vite base、router base、axios baseURL | 必须共用域名时 |

## 6. 已知不足

- 前端没有做权限分级（管理员入口）、图片上传、响应式适配
- 后端没有 Dockerfile，未接 CI/CD
- 未配置 HTTPS 和域名，仅本机演示

## 7. 面试追问

- Q：history 路由刷新 404 怎么解决？→ nginx `try_files $uri $uri/ /index.html;`
- Q：token 存在 localStorage 有什么风险？→ XSS 可以读取；更安全的是 HttpOnly cookie + CSRF token，但前后端分离项目普遍用 localStorage，需要配合前端转义和 CSP。
- Q：前端怎么处理接口报错？→ axios 响应拦截器统一判断 `success`，失败弹窗并把错误信息透传；401 时清除 token 并跳登录页。
- Q：为什么订单页轮询 5 秒？→ 演示用；生产应使用 WebSocket 或服务端推送，轮询会浪费请求。