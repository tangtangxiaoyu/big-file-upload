# 大文件上传项目

本项目实现了一个支持大文件上传、MD5校验和分片上传的Spring Boot应用程序。

## 功能特点

- 支持大文件上传（通过分片上传）
- MD5校验避免重复上传
- 使用PromisePool控制最大并发上传数（5个）
- 文件自动合并并存储在本地files目录中
- 前端友好的进度展示

## 技术架构

- 后端：Spring Boot 2.7.0
- 前端：纯HTML + JavaScript
- 分片大小：1MB
- 最大并发上传数：5个

## 运行方式

### 方式一：直接运行JAR包

```bash
java -jar target/big-file-upload-0.0.1-SNAPSHOT.jar
```

### 方式二：使用Maven运行

```bash
mvn spring-boot:run
```

### 访问应用

启动完成后，访问：[http://localhost:8080](http://localhost:8080)

## 核心功能说明

### 后端API

1. `/api/file/check` - 检查文件是否存在（基于MD5）
2. `/api/file/chunk` - 上传文件分片
3. `/api/file/upload` - 单文件上传（小文件）

### 前端特性

1. 文件选择与信息展示
2. 文件MD5计算
3. 文件切片处理
4. 并发上传控制（PromisePool）
5. 上传进度可视化
6. 分片状态指示器

## 项目结构

```
src/
├── main/
│   ├── java/
│   │   └── com/example/bigfileupload/
│   │       ├── BigFileUploadApplication.java
│   │       ├── controller/
│   │       │   └── FileUploadController.java
│   │       ├── service/
│   │       │   └── FileUploadService.java
│   │       └── config/
│   │           └── CorsConfig.java
│   └── resources/
│       ├── application.properties
│       └── static/
│           └── index.html
pom.xml
README.md
```

## 配置说明

- 上传文件存储目录：`./files` (相对于运行目录)
- 分片大小：1MB (可修改application.properties中的app.chunk.size)
- 服务器端口：8080 (可修改application.properties)

## 注意事项

- 上传的文件会根据其MD5值保存在files目录下
- 分片文件暂时保存在files/chunks目录下，在合并完成后删除
- 所有大文件都会被分割成1MB的块进行上传
- 并发上传数限制为5个，可通过修改前端代码调整