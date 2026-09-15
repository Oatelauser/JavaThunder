# JavaThunder

一个供第三方使用的 Java BitTorrent 下载库：消费 .torrent / 磁力链接，产出可配置、可观测的下载任务。本文件是全项目唯一的领域术语表——代码、文档、API 命名必须与这里一致。

## Language

### 元数据

**种子文件（Torrent File / Metainfo）**:
Bencode 编码的 .torrent 文件，登记文件清单、Piece 大小和每个 Piece 的 SHA-1 哈希。
_Avoid_: 种子（单独用，与做种混淆）、元文件

**Info-Hash**:
种子 info 字典的 SHA-1 哈希，全网唯一标识一份具体内容。
_Avoid_: 哈希值（泛指）、 torrent hash

**磁力链接（Magnet Link）**:
以 `magnet:?xt=urn:btih:<info-hash>` 定位内容的 URI，元数据需从 DHT 获取。（第二阶段）

### 传输单位

**Piece（校验块）**:
种子文件中登记 SHA-1 哈希的校验单位，典型 256KB–4MB；是位图与稀缺度统计的粒度。
_Avoid_: 块、分块、分片（单独使用）

**Block（传输块）**:
Peer 之间请求与传输的单位，协议固定 16 KiB；若干 Block 凑齐并校验通过才构成一个 Piece。
_Avoid_: 子块、请求块

### 节点与网络

**Peer（对等节点）**:
参与同一 Swarm、同时上传与下载的客户端连接。
_Avoid_: 节点（与 DHT Node 混淆）、客户端

**Swarm（种群）**:
参与同一份内容的全部 Peer 的集合。
_Avoid_: 网络（泛指）、节点群

**Seed（做种者）**:
已持有全部数据的 Peer；做种（Seeding）指仅上传不下行。
_Avoid_: 源、完整节点

**Leecher（下载者）**:
尚未持有全部数据的 Peer。
_Avoid_: 下载端

**Tracker（追踪服务器）**:
向客户端告知 Swarm 内 Peer 列表的中心协调服务器，走 HTTP 或 UDP。
_Avoid_: 中心服务器、注册服务器

**DHT（分布式哈希表）**:
基于 Kademlia 的去中心节点发现网络，可替代 Tracker。（第二阶段）
_Avoid_: 去中心化网络（泛指）

### 进度与状态

**Bitfield（位图）**:
一个 Peer 已持有哪些 Piece 的位表示。
_Avoid_: 进度图、bitmap（代码外）

**断点续传（Resume）**:
程序重启后基于持久化进度继续未完成下载的能力。
_Avoid_: 恢复下载、续点

**Availability（健康度）**:
已连接 Peer 的 Bitfield 聚合后统计出的完整内容副本数（含本机已持有部分）。
_Avoid_: 可用度、种子数

### 节点行为

**互惠上传（Reciprocal Upload）**:
下载中的节点把已持有分片应答给他人请求的行为；上传的第一形态，与做种（完整持有后的持续上传）相对。
_Avoid_: 边下边传（口语）、上传服务（不存在此物）

**发现渠道（Discovery Channel）**:
tracker、DHT、PEX 三类"节点地址发现"途径的统称——只交换地址，从不承载文件数据。
_Avoid_: 服务器（易误解为存储方）

### 部署场景

**镜像分发（Mirror Distribution）**:
内网高吞吐场景：一个或少数源节点向大量目标节点分发大体积镜像（如大模型权重），
聚合下载吞吐是首要指标。是 C5 引擎线程模型深化的驱动场景。
_Avoid_: 文件同步（泛指）、推送分发
