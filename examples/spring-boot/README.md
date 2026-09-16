# JavaThunder × Spring Boot 接入示例

独立 Maven 项目（**不在主 reactor 内**，parent 是 `spring-boot-starter-parent`）。演示四个接入姿势（详见主仓库 `docs/MANUAL.md` §6.6）：

| 姿势 | 落点 |
|---|---|
| `TorrentClient` 是重型资源 → 单例 Bean + 关闭回调 | `config/ThunderConfiguration#torrentClient`（`destroyMethod = "close"`） |
| 回调线程契约 → 注入专用 `listenerExecutor` | `config/ThunderConfiguration#thunderListenerExecutor`（2 线程池，不借 Tomcat 线程） |
| 进度暴露 → REST 轮询 + SSE 推送 | `web/DownloadController`（`GET /{id}` 用 `snapshot()`；`GET /{id}/events` 转发 `TaskListener` 事件） |
| 优雅停机 = `client.close()` 等任务收尾 | `destroyMethod = "close"`，应用关闭时由 Spring 调用 |

## 结构

```
src/main/java/com/example/thunder/
├── ThunderExampleApplication.java   # 入口（@ConfigurationPropertiesScan）
├── config/
│   ├── ThunderProperties.java       # javathunder.listen-port / max-concurrent-tasks
│   └── ThunderConfiguration.java    # TorrentClient Bean + 专用事件线程池
├── service/DownloadService.java     # 任务注册表（ConcurrentHashMap<UUID, DownloadTask>）
├── web/DownloadController.java      # REST + SSE
└── demo/DemoSeederRunner.java       # @Profile("demo")：回环 tracker+seeder，免外网冒烟
```

## 运行

前置：JDK 21；先把主库装进本地仓库（主仓库根目录执行，会一并安装 api/core/tracker/tools）：

```bash
mvn -B -ntp -pl javathunder-tools -am install -DskipTests
```

然后：

```bash
cd examples/spring-boot
mvn spring-boot:run                      # 普通模式（8080）
mvn spring-boot:run -Dspring-boot.run.profiles=demo   # demo 模式：自动起回环 1MB 种子源
```

> 生产环境不需要 demo profile，也不需要内嵌 tracker——tracker 是独立进程：
> `java -jar javathunder-tracker-*-with-dependencies.jar --port 6881`（见主仓库 ROADMAP/README）。

## curl 冒烟（demo 模式，应用日志里会打印现成命令）

```bash
# 1. 提交下载（.torrent 路径或 magnet: 均可；立即 202 + 任务 ID）
curl -s -X POST localhost:8080/api/downloads -H 'Content-Type: application/json' \
  -d '{"source": "<demo 目录下 demo-payload.bin.torrent 的绝对路径>", "targetDir": "demo/out"}'
# => {"id":"7dbf..."}     HTTP 202

# 2. 轮询进度（fraction/速率/peers/eta/state 来自 task.snapshot()）
curl -s localhost:8080/api/downloads/<id>
# => {"id":"...","state":"DOWNLOADING","fraction":0.75,...,"etaMillis":812}

# 3. SSE 事件流（连接即得 snapshot 帧；随后 progress/state 帧；终态后流自动结束）
curl -Ns localhost:8080/api/downloads/<id>/events

# 4. 取消（deleteData=true 连本地数据与断点一并删除；已完成任务是空操作）
curl -s -X DELETE 'localhost:8080/api/downloads/<id>?deleteData=false'
# => HTTP 204
```

## 配置（application.yml）

```yaml
javathunder:
  listen-port: 6881          # BitTorrent 入站端口
  max-concurrent-tasks: 3    # 全局并发任务槽
```
