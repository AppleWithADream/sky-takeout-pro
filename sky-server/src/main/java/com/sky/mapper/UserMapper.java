package com.sky.mapper;

import com.sky.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper {

    /**
     * 根据openid查询用户
     * @param openid
     * @return
     */
    @Select("select * from user where openid = #{openid}")
    User getByOpenid(String openid);

    /**
     * 插入用户数据
     * @param user
     */
    void insert(User user);

    /**
     * 根据动态条件统计用户数量
     * @param start
     * @param end
     */
    List<Map<String, Object>> countNewUserByDay(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 查询截止指定时间前所有用户总量
     * @param time
     */
    Integer countTotalUserBeforeTime(LocalDateTime time);
}
