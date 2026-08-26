package com.sky.service;

import com.sky.vo.OrderReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import java.time.LocalDate;

public interface ReportService {

    /**
     * 统计指定时间区间内的营业额统计
     * @return
     */
    TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计指定时间区间内的用户数据
     * @return
     */
    UserReportVO getUserStatistics(LocalDate begin, LocalDate end);

    /**
     * 统计指定时间区间内的订单数据
     * @return
     */
    OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end);
}
