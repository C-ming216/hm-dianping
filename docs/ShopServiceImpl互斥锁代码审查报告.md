# ShopServiceImpl 互斥锁代码审查报告

> 审查对象：`src/main/java/com/hmdp/service/impl/ShopServiceImpl.java`
> 审查日期：2026-08-17
> 编译状态：**BUILD FAILURE**（1 个编译错误）

---

## 一、🔴 编译错误（不改无法运行）

### 1. `queryShopWithMutex` 缺少返回语句（第 140 行）

```java
} else {
    Thread.sleep(50);
    queryShopWithPassthrough(id);   // ← 没有 return！
}
// ... finally 之后方法就结束了，没有任何 return
```

**原因**：`else`（拿锁失败）分支只调用了方法但没有 `return`，且调用的是 `queryShopWithPassthrough` 而不是递归自己。方法声明返回 `Shop`，编译器发现存在"走 else 时没有返回值"的路径，直接编译失败。

**修改方向**：拿锁失败应当**递归重试**并返回：

```java
if (!tryLock(lockKey)) {
    Thread.sleep(50);
    return queryShopWithMutex(id);   // 递归，并且必须 return
}
```

---

## 二、🟠 逻辑错误（能编译但功能错误）

### 2. `StrUtil.toString(shop)` 存进缓存的是对象字符串，不是 JSON（第 126 行）

```java
stringRedisTemplate.opsForValue().set(key, StrUtil.toString(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
```

**原因**：`StrUtil.toString(shop)` 调用的是 `Shop` 对象的 `toString()`，结果是 `Shop(id=1, name=xxx, ...)` 这种**不是 JSON 的字符串**。下次请求命中缓存后执行 `JSONUtil.toBean(shopJson, Shop.class)` 会**解析失败抛异常**，整个查询接口会挂掉。

**修改方向**：和防穿透方法保持一致，用 `JSONUtil.toJsonStr(shop)`。

### 3. 查库为 null 时没有缓存空值（第 125-127 行）

```java
Shop shop = getById(id);
stringRedisTemplate.opsForValue().set(key, StrUtil.toString(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
return shop;
```

**原因**：`shop` 为 null（数据库没有这个商铺）时，没有走"缓存空值 + 短 TTL"分支，反而用正常 TTL 缓存了一个空值。既丢了防穿透能力，又和 `queryShopWithPassthrough` 的行为不一致。

**修改方向**：加上空值分支：

```java
Shop shop = getById(id);
if (shop == null) {
    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
    return null;
}
stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
```

### 4. `finally` 无条件释放锁 → 会误删别人的锁，互斥失效（第 134-135 行）

```java
try {
    if (tryLock(lockKey)) {
        // 临界区...
    } else {
        Thread.sleep(50);
        queryShopWithPassthrough(id);
    }
} finally {
    unlock(lockKey);   // ← 拿锁失败的线程也会执行这里！
}
```

**这是最隐蔽也最严重的问题。**

**原因**：`finally` 无论是否拿到锁都会执行。假设线程 A 抢到锁进入临界区，线程 B 没抢到锁，B 走完 `else` 后也会执行 `unlock(lockKey)`——**把 A 的锁删掉了**！于是线程 C 又能抢到锁，多个线程同时进入临界区查库，互斥锁形同虚设，击穿防护失效。

**修改方向**：用布尔变量标记"是否持锁"，只有持锁的线程才释放：

```java
boolean locked = false;
try {
    locked = tryLock(lockKey);
    if (!locked) {
        Thread.sleep(50);
        return queryShopWithMutex(id);
    }
    // ...临界区...
} finally {
    if (locked) {
        unlock(lockKey);   // 只有抢到锁才释放
    }
}
```

---

## 三、🟡 规范问题（不影响功能，但不符合主流/项目风格）

### 5. `throws InterruptedException` 与内部 try-catch 矛盾（第 99 / 132 行）

方法声明了 `throws InterruptedException`，但方法体里已经把 `InterruptedException` 用 try-catch 处理了。声明和实现自相矛盾。应该去掉方法签名上的 `throws`。

另外 catch 里的处理可以更规范（恢复中断状态）：

```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // 恢复中断状态
    throw new RuntimeException("查询商铺被中断", e);
}
```

### 6. import 冗余且风格不统一（第 10 / 17 / 102 行）

- 同时存在 `import com.hmdp.utils.RedisConstants;` 和 `import static com.hmdp.utils.RedisConstants.*;`，通配符 `.*` 也是项目其他类没用的写法
- 第 70 行用静态导入的 `CACHE_SHOP_KEY`，第 102 行又用全限定名 `RedisConstants.LOCK_SHOP_KEY`，风格混乱

**修改方向**：统一用显式静态导入，去掉通配符和全限定名：

```java
import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;
```

### 7. 残留的 TODO 注释（第 27-31 / 95-98 / 143-146 / 155-158 行）

类顶部和三个方法上的 `TODO` 提示是给你开发用的，写完要删掉，保持代码干净。

### 8. 错别字和含糊注释

- 第 113 行 "枪锁" → 应为"抢锁"
- 第 77 行 "如果key存在但是为不正常数据" → 表述不清，建议改为"key 存在但值为空 = 命中空值标记"
- 第 116 行 "防止睡眠时数据被修改" → 应为"防止等待期间别人已重建缓存"（double-check 的语义）

### 9. 进阶建议（学习项目可暂不处理）

- **递归重试无上限**：极端并发下可能栈溢出。可加最大重试次数或用循环。
- **锁 value 固定 "1"**：严格做法是存 UUID，释放时校验是否是自己的锁（配合问题 4 的 locked 标志）。
- **锁的 TTL 与业务时间**：`LOCK_SHOP_TTL = 10s` 已够用，但要注意业务如果超过 10s，锁会提前失效导致重复查库（当前场景不会，了解一下即可）。

---

## 四、参考正确写法（修复全部问题后的 `queryShopWithMutex`）

```java
private Shop queryShopWithMutex(Long id) {
    String key = CACHE_SHOP_KEY + id;
    // 1. 查缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    if (StrUtil.isNotBlank(shopJson)) {
        return JSONUtil.toBean(shopJson, Shop.class);
    }
    if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
        return null; // 命中空值标记
    }
    // 2. 抢锁
    String lockKey = LOCK_SHOP_KEY + id;
    boolean locked = false;
    try {
        locked = tryLock(lockKey);
        if (!locked) {
            // 3. 没抢到 → 休眠后递归重试
            Thread.sleep(50);
            return queryShopWithMutex(id);
        }
        // 4. 抢到 → double-check（等待期间可能已被别人重建）
        shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return null;
        }
        // 5. 查库 + 写缓存（空值短 TTL / 数据正常 TTL）
        Shop shop = getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("查询商铺被中断", e);
    } finally {
        // 6. 只有抢到锁才释放（关键！防止误删别人的锁）
        if (locked) {
            unlock(lockKey);
        }
    }
}
```

`tryLock` / `unlock` 你已经写对了（setIfAbsent + 防 NPE + delete），不需要改。

---

## 五、修改优先级建议

| 优先级 | 问题 | 影响 |
|---|---|---|
| P0 | #1 缺少 return | 编译不过 |
| P0 | #2 StrUtil.toString | 运行必炸 |
| P0 | #4 finally 误删锁 | 互斥失效 |
| P1 | #3 空值未缓存 | 防穿透缺失 |
| P1 | #5 throws 矛盾 | 规范 |
| P2 | #6~#9 | 整洁 |

---

## 六、第二轮检查（2026-08-17 14:17，编译已通过）

上一轮的问题已修复 ✅：缺少 return、StrUtil.toString、空值缓存、throws 矛盾（部分）、TODO 部分清理。但互斥锁核心逻辑仍有 **2 个 P0 严重 bug + 1 个 P1**：

### 🔴 P0-1：`finally` 释放锁条件写反了（第 142-147 行）

```java
} finally {
    if (!isLock) {       // ← 写反了！应该是 if (isLock)
        unlock(lockKey);
    }
}
```

**后果分析**（两条路径都错）：
- **拿到锁**（isLock=true）→ 走 `if` 分支返回 → finally 里 `!isLock` 为 false → **锁永远不释放** → 后续所有请求都抢不到锁，全部 sleep 重试，形成死锁/无限递归。
- **没拿到锁**（isLock=false）→ 走 `else` 分支 → finally 里 `!isLock` 为 true → **删掉持锁线程的锁** → 第三个线程又能抢到，多个线程同时进临界区，互斥失效。

**修正**：
```java
} finally {
    if (isLock) {        // 只有抢到锁才释放
        unlock(lockKey);
    }
}
```

### 🔴 P0-2：入口还在调防穿透，互斥锁没生效（第 54 行）

```java
Shop shop = queryShopWithPassthrough(id);   // ← 应该 queryShopWithMutex(id)
```

`queryShopById` 还没切换到互斥锁方法，你辛苦写的 `queryShopWithMutex` 目前是死代码，击穿防护没启用。

**修正**：
```java
Shop shop = queryShopWithMutex(id);
```

### 🟠 P1：拿锁失败分支调错了方法（第 134-137 行）

```java
} else {
    Thread.sleep(50);
    return queryShopWithPassthrough(id);   // ← 应该递归 queryShopWithMutex(id)！
}
```

拿锁失败应该**递归重试互斥锁**，而不是调用"无锁的防穿透查询"。现在的写法导致：拿锁失败的线程全部绕过锁直接查库，**击穿防护依然失效**（和没写锁一样）。

**修正**：
```java
} else {
    Thread.sleep(50);
    return queryShopWithMutex(id);   // 递归重试，继续抢锁
}
```

### ✅ 已改对的部分（表扬）

- 空值缓存分支（127-130 行）✅
- `JSONUtil.toJsonStr(shop)` ✅
- `tryLock` 的 `Boolean.TRUE.equals(flag)` 防 NPE ✅
- 抢锁提到 try 外面（114 行）——这其实是个好习惯，比放 try 里更清晰

### 🟡 优化建议（P2）

1. **`import` 冗余且风格不统一**：`import com.hmdp.utils.RedisConstants;`（10 行）和 `import static ...RedisConstants.*;`（17 行）重复；第 102 行用全限定名 `RedisConstants.LOCK_SHOP_KEY`，和其余静态导入不一致。建议统一显式静态导入，去掉通配符 `.*` 和单独 import。
2. **`throws InterruptedException` 还在**（99 行）：方法体已 try-catch 处理，声明应删掉。
3. **TODO 注释残留**：三个方法上的 TODO 写完要删。
4. **递归重试无上限**：极端并发可能栈溢出。可加最大重试次数或改 while 循环（进阶）。
5. **锁 value "1" 可换 UUID**：严格做法存 UUID，释放时校验归属（进阶）。
6. **注释措辞**：116 行"防止睡眠时数据被修改"→ 应为"防止等待期间别人已重建缓存"（double-check 语义）。
