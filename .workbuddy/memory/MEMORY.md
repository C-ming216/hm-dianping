# HM-DianPing 项目约定

## 基础环境
- **Java**：1.8（`<java.version>1.8</java.version>`）—— **系统实际安装的是 JDK 21**，但项目本身用 1.8 编译（`javax.annotation`/`javax.servlet` 是 Java EE 8 时代的写法，不能升 jakarta）
- **Spring Boot 父版本**：`2.7.18`
- **构建**：Maven（绝对路径 `E:\SDK\apache-maven-3.9.16\bin\mvn.cmd`，**PATH 里直接调 mvn 会找不到 launcher**）
- **路径**：`E:/JAVA/Project/hm-dianping`
- **Maven 镜像**：已配阿里云（alimaven），联网可用

## 关键依赖版本（已固化）
- `mysql:mysql-connector-java`**显式** `8.0.33`（Spring Boot 2.7 BOM 在某些环境里不接管，老老实实显式写）
- `com.baomidou:mybatis-plus-boot-starter`:**3.5.5**（不归 Spring Boot BOM 管，必须显式写）
- `cn.hutool:hutool-all`:**5.8.38**
- Lombok 通过 spring-boot-maven-plugin 排除时固定 `1.18.34`

## 易踩坑清单（按踩坑顺序积累）
1. `target/classes` 不存在 `com/` 目录 = 源码没编译，先 `mvn clean compile` 再启动
2. pom 父版本错（如 `4.1.0`、`4.0.7`）→ BOM 加载失败，所有依赖报 "version missing"。本地 `.m2/repository/org/springframework/boot/spring-boot-starter-parent/` 下只有缓存的版本可用，先查这个再选版本
3. IDEA 里 Maven Reload 没反应 → 实际是 mvn 命令不可用，直接用绝对路径 `E:\SDK\apache-maven-3.9.16\bin\mvn.cmd` 跑
4. 源码用的是 `javax.annotation.Resource` + `javax.servlet.http.HttpSession`（Java EE 8 写法），所以**必须** Spring Boot 2.7.x，不能升级到 3.x / 4.x（后者是 jakarta）
5. 离线模式 (`-o`) 可能拉不到没缓存的依赖，去掉 `-o` 联网让 Maven 自己下

## 当前状态
- 项目编译通过（`mvn clean compile`），63 源文件全部编译
- `target/classes/com/hmdp/HmDianPingApplication.class` 已生成
- Spring Boot 服务可启动（2026-08-09 夜首次成功，监听 8081）
- 用户访问根路径会 404——黑马点评没有根路由，需要用具体业务接口测试

## 认证方案
- 当前：Session 登录（`HttpSession` 存 code/userDTO）
- 已设计 JWT+Redis 双 Token 方案文档：`docs/JWT+Redis企业级登录认证方案.md`（2026-08-11）
- 核心：hutool 内置 JWTUtil（零新增依赖）+ Access/Refresh 双 Token + Redis 白名单（`login:token:{jti}` / `login:refresh:{jti}`）
- 待落地实施（用户确认后按文档 Checklist 改造）

## pom.xml 当前正确配置
```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>
    <relativePath/>
</parent>
<properties>
    <java.version>1.8</java.version>
</properties>
```
