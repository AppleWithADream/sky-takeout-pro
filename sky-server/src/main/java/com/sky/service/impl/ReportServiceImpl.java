package com.sky.service.impl;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.vo.OrderReportVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ReportServiceImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    /**
     * 统计指定时间区间内的营业额统计
     * @return
     */
    @Override
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        // 创建一个List集合，封装每天日期
        List<LocalDate> dateList = new ArrayList<>();

        dateList.add(begin);
        while (!begin.equals(end)){
            // 日期加1
            begin=begin.plusDays(1);
            dateList.add(begin);
        }
        // 创建一个List集合，封装每天对应的营业额
        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate date : dateList) {
            // 封装查询条件，查询指定日期的营业额
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            Map map=new HashMap();
            map.put("begin",beginTime);
            map.put("end",endTime);
            map.put("status", Orders.COMPLETED);
            Double turnover=orderMapper.sumByMap(map);
            turnover=turnover==null?0.0:turnover; // 避免null
            turnoverList.add(turnover);
        }
        return TurnoverReportVO
                .builder()
                .dateList(StringUtils.join(dateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的用户数据
     * @return
     */
    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        // 创建一个List集合，封装每天日期
        List<LocalDate> dateList = new ArrayList<>();

        // 组装时间边界
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);

        // 基数时间：查询区间第一天前一天的总用户
        LocalDate baseDate = begin.minusDays(1);
        LocalDateTime baseTime = LocalDateTime.of(baseDate, LocalTime.MAX);
        // 查询1：区间第一天之前的累计总用户（基数）
        Integer totalUser = userMapper.countTotalUserBeforeTime(baseTime);
        totalUser = totalUser == null ? 0 : totalUser;

        // 查询2：一次性查出区间内每一天新增用户（GROUP BY分组，1次SQL）
        List<Map<String, Object>> dayNewUserDB = userMapper.countNewUserByDay(beginTime, endTime);

        // 创建一个List集合，封装每天对应的新用户数
        List<Integer> newUserList = new ArrayList<>();
        // 创建一个List集合，封装每天对应的总用户数
        List<Integer> totalUserList = new ArrayList<>();


        // 3. 把数据库查到的【日期-当日新增】转为Map，方便匹配
        Map<LocalDate, Integer> dayNewUserMap = new HashMap<>();
        for (Map<String, Object> map : dayNewUserDB) {
            // 修复日期转换
            java.sql.Date sqlDate = (java.sql.Date) map.get("day");
            LocalDate day = sqlDate.toLocalDate();
            // 统计人数转换
            Long countLong = (Long) map.get("newUser");
            Integer newNum = countLong.intValue();
            dayNewUserMap.put(day, newNum);
        }

        //生成完整日期列表
        dateList.add(begin);
        while (!begin.equals(end)){
            // 日期加1
            begin=begin.plusDays(1);
            dateList.add(begin);
        }
        // 用基数做累加变量
        Integer currentTotal =totalUser;

        for (LocalDate date : dateList) {
            // 当天新增，无数据补0
            Integer dayNew = dayNewUserMap.getOrDefault(date, 0);
            newUserList.add(dayNew);
            // 累计总用户 = 之前总量 + 今日新增
            currentTotal = currentTotal + dayNew;
            totalUserList.add(currentTotal);
        }


        return UserReportVO
                .builder()
                .dateList(StringUtils.join(dateList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .build();
    }

    /**
     * 统计指定时间区间内的订单数据
     * @return
     */
    @Override
    public OrderReportVO getOrderStatistics(LocalDate begin, LocalDate end) {
        // 创建一个List集合，封装每天日期
        List<LocalDate> dateList = new ArrayList<>();

        dateList.add(begin);
        while (!begin.equals(end)){
            // 日期加1
            begin=begin.plusDays(1);
            dateList.add(begin);
        }

        // 创建一个List集合，封装每天对应的订单数
        List<Integer> orderCountList = new ArrayList<>();
        List<Integer> validOrderCountList = new ArrayList<>();

        //遍历dataList集合，查询每一天的有效订单数和订单总数
        for (LocalDate date : dateList) {
            //查询每天的订单总数
            LocalDateTime beginTime = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(date, LocalTime.MAX);

            Integer orderCount = getOrderCount(beginTime, endTime, null);
            //查询每天的有效订单数
            Integer validOrderCount = getOrderCount(beginTime, endTime, Orders.COMPLETED);

            orderCountList.add(orderCount);
            validOrderCountList.add(validOrderCount);
        }
        // 订单总数和有效订单数的总和
        Integer totalOrderCount = orderCountList.stream().mapToInt(Integer::intValue).sum();
        Integer totalValidOrderCount = validOrderCountList.stream().mapToInt(Integer::intValue).sum();

        Double orderCompletionRate = 0.0;
        if (totalOrderCount != 0) {
            // 订单完成率 = 有效订单数 / 订单总数
            orderCompletionRate = totalValidOrderCount.doubleValue() / totalOrderCount;
        }

        return OrderReportVO.builder()
                .dateList(StringUtils.join(dateList, ","))
                .orderCountList(StringUtils.join(orderCountList, ","))
                .validOrderCountList(StringUtils.join(validOrderCountList, ","))
                .totalOrderCount(totalOrderCount)
                .validOrderCount(totalValidOrderCount)
                .orderCompletionRate(orderCompletionRate)
                .build();
    }

    /**
     * 查询指定时间区间的订单数
     * @param begin
     * @param end
     * @param status
     * @return
     */
    private Integer getOrderCount(LocalDateTime begin, LocalDateTime end, Integer status) {
        Map map = new HashMap();
        map.put("begin", begin);
        map.put("end", end);
        map.put("status", status);

        return orderMapper.countByMap(map);
    }
}
