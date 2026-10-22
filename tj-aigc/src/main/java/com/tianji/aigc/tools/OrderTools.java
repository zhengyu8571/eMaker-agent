package com.tianji.aigc.tools;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.tools.result.PrePlaceOrder;
import com.tianji.api.client.trade.TradeClient;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OrderTools {

    private final TradeClient tradeClient;

    @Tool(description = Constant.Tools.PRE_PLACE_ORDER)
    public PrePlaceOrder prePlaceOrder(@ToolParam(description = Constant.ToolParams.COURSE_IDS) List<Number> courseIds,
                                       ToolContext toolContext) {

        // 在当前上下文中设置当前登录的用户id
        var userId = MapUtil.getLong(toolContext.getContext(), Constant.USER_ID);
        UserContext.setUser(userId);
        // 调用预下单接口
        var orderConfirmVO = this.tradeClient.prePlaceOrder(CollStreamUtil.toList(courseIds, Number::longValue));

        return Optional.ofNullable(orderConfirmVO)
                .map(PrePlaceOrder::of)
                .map(prePlaceOrder -> {
                    // 获取请求id
                    var requestId = MapUtil.getStr(toolContext.getContext(), Constant.REQUEST_ID);
                    // 数据标识
                    var field = StrUtil.lowerFirst(PrePlaceOrder.class.getSimpleName());
                    // 数据存储到保持器中
                    ToolResultHolder.put(requestId, field, prePlaceOrder);
                    return prePlaceOrder;
                })
                .orElse(null);
    }

}
