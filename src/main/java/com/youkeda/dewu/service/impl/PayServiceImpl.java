package com.youkeda.dewu.service.impl;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeWapPayRequest;
import com.alipay.api.response.AlipayTradeWapPayResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.dewu.model.*;
import com.youkeda.dewu.param.PaymentParam;
import com.youkeda.dewu.param.PaymentRecordQueryParam;
import com.youkeda.dewu.service.OrderService;
import com.youkeda.dewu.service.PayService;
import com.youkeda.dewu.service.PaymentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PayServiceImpl implements PayService {
    private static final Logger logger = LoggerFactory.getLogger(PayServiceImpl.class);

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentRecordService paymentRecordService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @Value("${alipay.app.id}")
    private String aliPayAppId;

    @Value("${alipay.app.privatekey}")
    private String aliPayAppPrivateKey;

    @Value("${alipay.publickey}")
    private String aliPayPublicKey;

    public Result aliPay(String orderId, PaymentParam paymentParam) {
        Result result = new Result();
        result.setSuccess(true);

        //根据订单id查询出订单信息
        // Order order = orderService.getByOrderNumber(orderId);
        // if (order == null) {
        //     result.setSuccess(false);
        //     result.setMessage("未查询到任何订单信息");
        //     return result;
        // }
        //实例化客户端
        AlipayClient alipayClient = new DefaultAlipayClient("https://openapi.alipay.com/gateway.do", this.aliPayAppId,
                                                            this.aliPayAppPrivateKey, "json", "UTF-8",
                                                            this.aliPayPublicKey, "RSA2");
        //创建API对应的request
        AlipayTradeWapPayRequest request = getAlipayTradeWapPayRequest(orderId, paymentParam);

        AlipayTradeWapPayResponse response = null;
        try {
            //调用SDK生成表单
            response = alipayClient.pageExecute(request);
        } catch (AlipayApiException e) {
            logger.error("e is:", e);
        }
        if (response.isSuccess()) {
            String channelId = response.getTradeNo();
            //更新支付记录
            updatePayRecord(null, channelId, PayType.ALIPAY.toString(), paymentParam.getOrderNumber(),
                            PaymentStatus.PENDING);
        }
        return result;
    }

    @Override
    public Result alipayCallBack(Map<String, String> mapParam) {
        Result result = new Result();
        result.setSuccess(true);
        String status = mapParam.get("trade_status");
        String orderNum = mapParam.get("out_trade_no");
        Order order = orderService.getByOrderNumber(orderNum);
        String endTime = mapParam.get("gmt_close");
        if (order != null) {
            //交易成功
            if ("TRADE_SUCCESS".equals(status)) {
                // 更新订单状态
                orderService.updateOrderStatus(orderNum, OrderStatus.TRADE_PAID_SUCCESS);

                //更新支付流水
                PaymentRecordQueryParam queryParam = new PaymentRecordQueryParam();
                queryParam.setOrderNumber(orderNum);
                List<PaymentRecord> paymentRecords = paymentRecordService.query(queryParam);
                if (!CollectionUtils.isEmpty(paymentRecords)) {
                    PaymentRecord paymentRecord = paymentRecords.get(0);
                    paymentRecord.setPayStatus(PaymentStatus.SUCCESS);
                    //更新支付流水状态
                    paymentRecordService.updateStatus(paymentRecord);
                }
                //更新商品付款人数
                orderService.updateProductPersonNumber(orderNum);
            }

            //交易关闭
            if ("TRADE_CLOSED".equals(status)) {
                // 更新订单状态
                orderService.updateOrderStatus(orderNum, OrderStatus.TRADE_PAID_FAILED);

                //更新支付流水
                PaymentRecordQueryParam queryParam = new PaymentRecordQueryParam();
                queryParam.setOrderNumber(orderNum);
                List<PaymentRecord> paymentRecords = paymentRecordService.query(queryParam);
                if (!CollectionUtils.isEmpty(paymentRecords)) {
                    PaymentRecord paymentRecord = paymentRecords.get(0);
                    paymentRecord.setPayStatus(PaymentStatus.FAILURE);
                    //更新支付流水状态
                    paymentRecordService.updateStatus(paymentRecord);
                }
            }
        }
        return result;
    }

    /**
     * 组装支付宝支付参数
     *
     * @param orderId      订单号
     * @param paymentParam 支付参数
     * @return AlipayTradeWapPayRequest
     */
    private AlipayTradeWapPayRequest getAlipayTradeWapPayRequest(String orderId, PaymentParam paymentParam) {
        Map<String, Object> bizContentMap = new HashMap<>();
        AlipayTradeWapPayRequest request = new AlipayTradeWapPayRequest();
        request.setApiVersion("1.0");
        request.setReturnUrl("");
        request.setNotifyUrl("");
        bizContentMap.put("out_trade_no", orderId);
        bizContentMap.put("total_amount", paymentParam.getPayAmount());
        bizContentMap.put("quit_url", "www.youkeda.com");
        bizContentMap.put("product_code", "QUICK_WAP_WAY");

        try {
            request.setBizContent(objectMapper.writeValueAsString(bizContentMap));
        } catch (JsonProcessingException e) {
            logger.error("e is:", e);
        }
        return request;
    }

    @Override
    public Result payOrder(PaymentParam paymentParam) {
        Result result = new Result<>();
        switch (paymentParam.getPayType()) {
            case ALIPAY:
                result = this.aliPay(paymentParam.getOrderNumber(), paymentParam);
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * 更新支付记录信息
     *
     * @param endTime       支付结束时间
     * @param orderNum      订单号
     * @param channelId     渠道id
     * @param channelType   渠道类型
     * @param paymentStatus 支付状态
     */
    private void updatePayRecord(String endTime, String channelId, String channelType, String orderNum,
                                 PaymentStatus paymentStatus) {
        PaymentRecordQueryParam param = new PaymentRecordQueryParam();
        param.setOrderNumber(orderNum);
        List<PaymentRecord> paymentList = paymentRecordService.query(param);
        if (!CollectionUtils.isEmpty(paymentList)) {
            PaymentRecord paymentRecord = paymentList.get(0);
            paymentRecord.setPayStatus(paymentStatus);
            if (!StringUtils.isEmpty(endTime)) {
                paymentRecord.setPayEndTime(endTime);
            }
            if (!StringUtils.isEmpty(channelId)) {
                paymentRecord.setChannelPaymentId(channelId);
            }
            if (!StringUtils.isEmpty(channelType)) {
                paymentRecord.setChannelType(channelType);
            }
            PaymentRecord paymentRecordResult = paymentRecordService.save(paymentRecord);
            if (paymentRecordResult == null) {
                logger.error("更新支付记录失败！" + "paymentRecordId is: " + paymentRecordResult.getId());
            }
        }
    }
}
