package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result secKillVoucher(Long voucherId);

    /**
     * 异步落库：由 Stream 消费线程调用，userId 从消息体（voucherOrder）中获取，
     * 不能用 UserHolder（异步线程无 ThreadLocal）
     */
    Result createVoucher(VoucherOrder voucherOrder);
}
