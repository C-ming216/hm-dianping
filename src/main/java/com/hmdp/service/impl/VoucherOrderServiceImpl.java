package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import com.hmdp.utils.redisIdWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private redisIdWorker redisIdWorker;

    @Override
    @Transactional
    public Result secKillVoucher(Long voucherId) {
        //1.根据id查询优惠券--先直接从数据库查询，后续应该会优化
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断是否开始抢购：如果优惠券的开始时间在当前时间之后，说明抢购还未开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //我应该返回怎样的信息
            return Result.fail("抢购未开始");
        }
        //3.是否结束抢购
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("抢购已经结束");
        }
        //4.判断库存是否充足
        long stock = voucher.getStock();
        if(stock <=0){
            return Result.fail("秒杀券售罄");
        }
        //5.从库存扣除优惠券
        if(!seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                //乐观锁，解决库存超卖问题--适用于数据更新
                .gt("stock",0)
                .update()){
            return Result.fail("库存不足");
        }
        //6.创建订单--用户id，优惠券id，订单id--包装进VoucherOrder并写入数据库
        VoucherOrder voucherOrder = new VoucherOrder();
        //用户id
        voucherOrder.setUserId(UserHolder.getUser().getId());
        //优惠券id
        voucherOrder.setVoucherId(voucherId);
        //订单id
        voucherOrder.setId(redisIdWorker.nextId("order"));
        save(voucherOrder);
        //7.返回订单id
        return Result.ok(voucherOrder.getId());
    }
}