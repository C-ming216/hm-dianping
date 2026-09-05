package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LoginResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.JwtUtils;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    ///为什么这两个没写@Resource或@Autowired
    //因为是构造器注入方式
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtils jwtUtils;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate, JwtUtils jwtUtils) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtUtils = jwtUtils;
    }

    @Override
    public Result sendCode(String phone) {
        /*
        * 问题一，手机号码输入错误了还需要等60秒
        *
        * */
        //1.校验手机号否合法--工具包中的正则表达式
        //卡在RegexPatterns.PHONE_REGEX();，不知如何将phone校验
        //用错类了，是RegexUtils中的
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.如果是错误的，返回手机号有误--但是我要返回什么格式？String？还是message
            //返回统一结果Result类，里面有3中ok，1中fail
            return Result.fail("手机号有误");
        }
        //3.生成校验码，利用hutool生成随机六位数--怎么调用hutool中的方法
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        //session.setAttribute("code",code);
        //session.setAttribute("phone",phone);

        // JWT->将验证码保存到redis中
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                //设置时间，使用常量
                RedisConstants.LOGIN_CODE_TTL,
                //设置时间单位
                TimeUnit.MINUTES
        );
        //5.发送验证码
        log.info("发送验证码成功，验证码：{},手机号：{}",code,phone);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        //1.将发送验证码的手机号和当前输入框的手机号进行比对
        //  --如何获取发送验证码的手机号？--将phone也放入session中
        //也就是假如我登录时手机号与提交验证码时的手机号不一致，那么我根据登录时提交的手机号就查询不到对应的验证码，对应的验证就不会通过
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        // 先校验手机号格式，避免非法手机号白白查询 Redis
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号非法，请重新填写");
        }
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误，请重新获取");
        }
        // 验证码一次性使用：校验通过立即删除，防止同一验证码被重复登录
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        //2.根据手机号查询用户是否存在，不存在则创建新用户，存在则保存到session
        //  --利用mbp查询,绑定表的配置在哪？调用什么方法？
        User user = query().eq("phone",phone).one();
        if(user == null){
            //创建新用户--定义一个方法单独写创建新用户
            user = createNewUser(phone);
        }
        //将user转成UserDto，隐藏关键信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        LoginResultDTO resultDTO = issueTokens(userDTO);
        return Result.ok(resultDTO);
    }

    @Override
    public Result logout(String accessToken, String refreshToken) {
        // 去掉 Authorization 头可能携带的 "Bearer " 前缀，否则验签会失败
        if (accessToken != null && accessToken.startsWith("Bearer ")) {
            accessToken = accessToken.substring(7);
        }
        //解析并提取jti，删除白名单中的token
        JwtUtils.JwtPayload accessPayload = jwtUtils.parseAndVerify(accessToken,JwtUtils.TYPE_ACCESS);
        JwtUtils.JwtPayload refreshPayload = jwtUtils.parseAndVerify(refreshToken,JwtUtils.TYPE_REFRESH);
        //判断jti是否为null，不为null则删除
        ///疑问：删除了jti是否意味着token也被删除
        if(accessPayload != null){
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + accessPayload.getJti());
        }
        if(refreshPayload != null){
            stringRedisTemplate.delete(RedisConstants.LOGIN_REFRESH_KEY + refreshPayload.getJti());
        }
        return Result.ok();
    }


    @Override
    public Result refresh(String refreshToken) {
        // 1. 解析并校验刷新令牌是否合法且未过期
        JwtUtils.JwtPayload payload = jwtUtils.parseAndVerify(refreshToken, JwtUtils.TYPE_REFRESH);
        if (payload == null) {
            return Result.fail("刷新令牌无效或已过期，请重新登录");
        }
        // 2. 根据令牌中的 jti 查询 Redis 白名单，检查刷新令牌是否仍然有效
        String userIdStr = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_REFRESH_KEY + payload.getJti());
        if (userIdStr == null) {
            return Result.fail("刷新令牌已失效，请重新登录");
        }
        // 3. 根据用户 ID 查询用户是否存在
        User user = getById(Long.valueOf(userIdStr));
        if (user == null) {
            return Result.fail("用户不存在");
        }
        // 4. 将 User 转为 UserDTO，隐藏敏感字段
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

        // 5. 删除旧的刷新令牌白名单记录，实现一次性使用，防止令牌被重复利用
        stringRedisTemplate.delete(RedisConstants.LOGIN_REFRESH_KEY + payload.getJti());

        // 6. 签发新的双令牌（accessToken + refreshToken），实现令牌续期
        LoginResultDTO resultDTO = issueTokens(userDTO);
        return Result.ok(resultDTO);
    }

    private LoginResultDTO issueTokens(UserDTO userDTO) {
        String accessJti = IdUtil.fastSimpleUUID();
        String refreshJti = IdUtil.fastSimpleUUID();
        String accessToken = jwtUtils.createAccessToken(userDTO.getId(), accessJti);
        String refreshToken = jwtUtils.createRefreshToken(userDTO.getId(), refreshJti);

        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_USER_KEY + accessJti,
                JSONUtil.toJsonStr(userDTO),
                RedisConstants.LOGIN_USER_TTL,
                TimeUnit.MINUTES);
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_REFRESH_KEY + refreshJti,
                String.valueOf(userDTO.getId()),
                RedisConstants.LOGIN_REFRESH_TTL,
                TimeUnit.DAYS);

        return new LoginResultDTO(accessToken, refreshToken, userDTO);
    }

    //根据手机号创建一个新用户并存入数据库
    //  --是将整个用户对象传进去吗？--是的，通过mbp的save方法
    public User createNewUser(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}