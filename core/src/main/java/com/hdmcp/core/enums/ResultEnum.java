/*
 *
 * MIT License
 *
 * Copyright (c) 2019 hdmcp.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.hdmcp.core.enums;

/**
 *
 *
 * @author WANGY
 */
public enum ResultEnum {

    PERMISSION_ERROR(5, "权限错误"),
    PARAM_ERROR(10, "参数错误"),
    TOKEN_EXPIRED(15, "Token已过期"),
    TOKEN_ERROR(16, "Token错误"),
    PERMISSION_DENIED(18, "无访问权限"),
    CART_EMPTY(20, "购物车为空"),
    PRODUCT_NOT_EXIST(30, "商品不存在"),
    STOCK_NOT_ENOUGH(40, "库存不足"),
    USER_NOT_EXIST(50, "用户不存在"),
    USER_EXISTED(55, "用户已存在"),
    DATA_NOT_EXIST(60, "数据不存在"),
    DATA_EXISTED(65, "数据已存在"),
    USER_AUTHENTICATED(70, "用户已登录"),
    ORDER_NOT_EXIST(80, "订单不存在"),
    ORDER_STATUS_ERROR(90, "订单状态异常"),
    PAY_STATUS_ERROR(100, "支付状态异常"),
    ORDER_DETAIL_NOT_EXIST(110, "订单详情不存在"),
    ;

    private Integer code;
    private String desc;

    ResultEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
